package app.neochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A file or picture picked for sending, with what the system tells about it. */
class Pick(val uri: Uri, val name: String, val mime: String, val size: Long) {
    val image get() = mime.startsWith("image/")
}

fun Context.pickOf(uri: Uri): Pick {
    var name = "file"
    var size = -1L
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    return Pick(uri, name, contentResolver.getType(uri) ?: "application/octet-stream", size)
}

/**
 * The window every attachment passes through: preview, "send as a file" for pictures, a caption.
 * [more] asks the chat to pick additional items; [send] gets the final set and the caption.
 */
class AttachSheet(
    private val act: MainActivity,
    initial: List<Pick>,
    private val asPhotos: Boolean,
    private val more: ((List<Pick>) -> Unit) -> Unit,
    private val send: (List<Pick>, Boolean, String) -> Unit,
) {
    private val picks = initial.toMutableList()
    private val sheet = Sheet(act, "")
    private val previewBox = FrameLayout(act)
    private val asFile = ToggleRow(act, "Отправить как файл")
    private val caption = FloatingField(act, "Подпись")
    private var token = 0

    fun show() {
        caption.edit.filters = arrayOf(InputFilter.LengthFilter(1000))
        val add = PillButton(act, "Добавить", primary = false)
        val go = PillButton(act, "Отправить")
        val row = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(add, lp(0, WRAP, 1f))
            addView(go, lp(0, WRAP, 1f).apply { marginStart = act.dp(10) })
        }
        sheet.add(previewBox)
        sheet.add(asFile)
        sheet.add(caption)
        sheet.add(row, 14)
        asFile.setChecked(!asPhotos, false)
        asFile.onToggle = { refresh() }
        add.setOnClickListener {
            more { extra ->
                for (p in extra) if (picks.none { it.uri == p.uri }) picks += p
                refresh()
            }
        }
        go.setOnClickListener {
            val chosen = picks.toList()
            val photos = photosNow()
            val text = caption.text.trim()
            sheet.dismiss()
            send(chosen, photos, text)
        }
        refresh()
        sheet.show()
    }

    private fun allImages() = picks.all { it.image }

    // Pictures can go as photos or as files; with anything else in the set there is no choice to offer.
    private fun photosNow() = if (allImages()) !asFile.checked else asPhotos

    private fun refresh() {
        val images = allImages()
        asFile.visibility = if (images) View.VISIBLE else View.GONE
        val photos = photosNow() && images
        sheet.setTitle(
            when {
                photos && picks.size == 1 -> "Отправить фото"
                photos -> "Отправить фото: ${picks.size}"
                picks.size == 1 -> "Отправить файл"
                else -> "Отправить файлы: ${picks.size}"
            }
        )
        previewBox.removeAllViews()
        if (photos) previewImage() else previewFiles()
    }

    private fun previewImage() {
        val img = ImageView(act)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = GradientDrawable().apply { setColor(Theme.p.rim); cornerRadius = act.dpf(14) }
        img.clipToOutline = true
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, act.dpf(14))
        }
        img.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        previewBox.addView(img, FrameLayout.LayoutParams(MATCH, act.dp(220)))
        previewBox.contentDescription = if (picks.size > 1) "Первое из ${picks.size} фото" else "Фото"
        val mine = ++token
        val uri = picks.first().uri
        Thread {
            val bmp = decodeUri(act, uri, 900)
            act.runOnUiThread { if (mine == token && bmp != null) img.setImageBitmap(bmp) }
        }.start()
        if (picks.size > 1) {
            val badge = act.label("+${picks.size - 1}", 13f, 600)
            badge.setTextColor(0xFFFFFFFF.toInt())
            badge.gravity = Gravity.CENTER
            badge.setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(4))
            badge.background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = act.dpf(14) }
            previewBox.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, act.dp(10), act.dp(10)) })
        }
    }

    private fun previewFiles() {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        for (p in picks.take(4)) {
            val row = LinearLayout(act)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, act.dp(4), 0, act.dp(4))
            val icon = ImageView(act)
            icon.setImageResource(app.neochat.R.drawable.ic_attach)
            icon.setColorFilter(Theme.p.accent)
            icon.setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(10))
            icon.background = GradientDrawable().apply { setColor(Theme.p.rim); cornerRadius = act.dpf(12) }
            row.addView(icon, lp(act.dp(44), act.dp(44)))
            val texts = LinearLayout(act)
            texts.orientation = LinearLayout.VERTICAL
            val name = act.label(p.name, 15f, 600)
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            texts.addView(name)
            texts.addView(act.label(if (p.size >= 0) sizeText(p.size) else "", 12.5f, 400, Theme.Role.MUTED))
            row.addView(texts, lp(0, WRAP, 1f).apply { marginStart = act.dp(12) })
            col.addView(row, lp())
        }
        if (picks.size > 4) col.addView(act.label("и ещё ${picks.size - 4}", 13f, 400, Theme.Role.MUTED).apply { setPadding(0, act.dp(4), 0, 0) })
        previewBox.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        previewBox.contentDescription = null
    }

    private fun decodeUri(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val deg = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }.getOrNull()
}
