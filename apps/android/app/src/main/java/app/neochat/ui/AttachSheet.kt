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
    val video get() = mime.startsWith("video/")
    /** Pictures and videos can be sent as such or as plain files. */
    val media get() = image || video
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
 * [more] asks the chat to pick additional items; [send] gets the final set, the caption and what
 * the sender asked of a picture — hidden until tapped, or shown only once.
 */
class AttachSheet(
    private val act: MainActivity,
    initial: List<Pick>,
    private val asPhotos: Boolean,
    private val more: ((List<Pick>) -> Unit) -> Unit,
    private val send: (List<Pick>, Boolean, String, Boolean, Long) -> Unit,
) {
    private val picks = initial.toMutableList()
    private val sheet = Sheet(act, "")
    private val previewBox = FrameLayout(act)
    private val asFile = ToggleRow(act, "Отправить как файл")
    private val spoiler = ToggleRow(act, "Скрыто")
    private var timerMs = 0L  // 0: never, -1: one view, positive: countdown after opening.
    private val timerLabel = act.label("Удаление по таймеру: Никогда", 15f, 500)
    private val timerRow = LinearLayout(act).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(16), act.dp(4), act.dp(14), act.dp(4))
        background = act.storyPanel()
        minimumHeight = act.dp(52)
        isClickable = true
        isFocusable = true
        pressFeedback()
        contentDescription = "Удаление по таймеру"
        val icon = ImageView(act).apply {
            setImageResource(app.neochat.R.drawable.ic_clock)
            imageTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent)
        }
        addView(icon, lp(act.dp(24), act.dp(24)))
        addView(timerLabel, lp(0, WRAP, 1f).apply { marginStart = act.dp(12) })
        addView(ImageView(act).apply {
            setImageResource(app.neochat.R.drawable.ic_more)
            imageTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent)
        }, lp(act.dp(20), act.dp(20)))
        setOnClickListener { openTimerMenu() }
    }
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
        sheet.add(spoiler)
        sheet.add(timerRow)
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
            val hidden = photos && spoiler.checked
            val ttl = if (photos) timerMs else 0L
            sheet.dismiss()
            send(chosen, photos, text, hidden, ttl)
        }
        refresh()
        sheet.show()
    }

    private fun refreshTimerLabel() {
        val selected = when (timerMs) {
            0L -> "Никогда"
            -1L -> "Один просмотр"
            3_000L -> "3 секунды"
            10_000L -> "10 секунд"
            30_000L -> "30 секунд"
            else -> if (timerMs < 60_000) "${timerMs / 1000} секунд"
                else if (timerMs % 3_600_000 == 0L) "${timerMs / 3_600_000} ч"
                else "${timerMs / 60_000} мин"
        }
        timerLabel.text = "Удаление по таймеру: $selected"
        timerRow.contentDescription = timerLabel.text
    }

    private fun openTimerMenu() {
        val menu = PopupMenu(act, timerRow)
        for ((label, millis) in listOf(
            "Один просмотр" to -1L,
            "3 секунды" to 3_000L,
            "10 секунд" to 10_000L,
            "30 секунд" to 30_000L,
            "Никогда" to 0L
        )) {
            menu.add(label, checked = timerMs == millis) {
                timerMs = millis
                refreshTimerLabel()
            }
        }
        menu.add("Другой срок…") { customTimer() }
        menu.show()
    }

    private fun customTimer() {
        val dialog = Sheet(act, "Другой срок")
        val field = FloatingField(act, "Секунды (от 1 до 86400)")
        field.edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        field.edit.filters = arrayOf(InputFilter.LengthFilter(5))
        if (timerMs > 0) field.setText((timerMs / 1000).toString())
        dialog.add(field)
        dialog.add(PillButton(act, "Применить").apply {
            setOnClickListener {
                val seconds = field.text.trim().toLongOrNull()
                if (seconds == null || seconds !in 1L..86_400L) {
                    act.toast("Введите срок от 1 секунды до 24 часов")
                    return@setOnClickListener
                }
                timerMs = seconds * 1_000L
                refreshTimerLabel()
                dialog.dismiss()
            }
        })
        dialog.show()
    }

    private fun allMedia() = picks.all { it.media }

    // Pictures and videos can go as such or as files; with anything else in the set there is no choice to offer.
    private fun photosNow() = if (allMedia()) !asFile.checked else asPhotos

    private fun refresh() {
        val media = allMedia()
        asFile.visibility = if (media) View.VISIBLE else View.GONE
        val photos = photosNow() && media
        // A spoiler and a view-once mark belong to a picture or a video, never to a document.
        spoiler.visibility = if (photos) View.VISIBLE else View.GONE
        timerRow.visibility = if (photos) View.VISIBLE else View.GONE
        if (!photos) {
            spoiler.setChecked(false, false)
            timerMs = 0
            refreshTimerLabel()
        }
        val videos = picks.all { it.video }
        sheet.setTitle(
            when {
                photos && videos && picks.size == 1 -> "Отправить видео"
                photos && videos -> "Отправить видео: ${picks.size}"
                photos && picks.any { it.video } -> "Отправить медиа: ${picks.size}"
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
        val first = picks.first()
        val noun = if (first.video) "Видео" else "Фото"
        previewBox.contentDescription = if (picks.size > 1) "Первое из ${picks.size}: ${noun.lowercase()}" else noun
        val mine = ++token
        val uri = first.uri
        Thread {
            val bmp = if (first.video) Videos.frame(act, uri, 900) else decodePhotoUri(act, uri, 900)
            act.runOnUiThread { if (mine == token && bmp != null) img.setImageBitmap(bmp) }
        }.start()
        if (first.video) {
            val play = ImageView(act)
            play.setImageResource(app.neochat.R.drawable.ic_play)
            play.setColorFilter(0xFFFFFFFF.toInt())
            play.setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
            play.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            play.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            previewBox.addView(play, FrameLayout.LayoutParams(act.dp(52), act.dp(52), Gravity.CENTER))
        }
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

}

internal fun decodePhotoUri(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
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
