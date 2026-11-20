package app.neochat.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.text.TextPaint
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.VideoView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val STORY_LIMIT = 50L shl 20
private const val VIDEO_LIMIT_MS = 60_000
internal const val STORY_THUMBNAILS = 8
private val STORY_SURFACE = 0xff101722.toInt()
internal val STORY_PANEL = 0xff212c3c.toInt()
internal val STORY_MUTED = 0xffbbcadc.toInt()

internal fun Context.storyPanel(color: Int = STORY_PANEL) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dpf(16)
}

private fun Context.storyLabel(text: String, size: Float, weight: Int = 500) =
    label(text, size, weight, Theme.Role.ON_ACCENT)

private fun storyTypeface(index: Int) = when (index) {
    1 -> Typeface.create("serif", Typeface.BOLD)
    2 -> Typeface.create("monospace", Typeface.BOLD)
    else -> weightFace(700)
}

@UnstableApi
internal fun exportStoryClip(
    context: Context,
    input: File,
    output: File,
    start: Int,
    end: Int,
    muted: Boolean,
    done: (Boolean) -> Unit,
): Transformer {
    val source = MediaItem.Builder().setUri(Uri.fromFile(input))
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(start.toLong()).setEndPositionMs(end.toLong()).build())
        .build()
    val clip = EditedMediaItem.Builder(source).setRemoveAudio(muted).build()
    return Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) = done(true)
            override fun onError(composition: Composition, result: ExportResult, error: ExportException) = done(false)
        }).build().apply { start(clip, output.path) }
}

internal open class StoryPage(protected val act: MainActivity, name: String) : Screen {
    private val root = FrameLayout(act).apply { setBackgroundColor(STORY_SURFACE) }
    override val view: View get() = root
    protected val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    protected val bar = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
    protected val title = act.storyLabel(name, 18f, 700).apply { heading(); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val back = IconButton(act, R.drawable.ic_back, "Назад", iconRole = Theme.Role.ON_ACCENT)

    init {
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        column.setPadding(act.dp(12), 0, act.dp(12), 0)
        bar.addView(back.apply {
            setOnClickListener { if (!this@StoryPage.onBack()) act.closePage() }
        })
        bar.addView(title, lp(0, WRAP, 1f))
        column.addView(bar, lp(MATCH, act.dp(56)))
    }

    override fun onShown() = act.barAppearance(true)

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        column.setPadding(act.dp(12), top, act.dp(12), maxOf(bottom, ime))
    }
}

internal class StoryRail(private val act: MainActivity) : HorizontalScrollView(act) {
    private val entries = LinearLayout(act)
    private var generation = 0
    private val expiry = Runnable { reload() }

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(4))
        addView(entries, FrameLayout.LayoutParams(WRAP, WRAP))
        val create = item("История", "Создать историю", act.label("+", 28f, 500, Theme.Role.ACCENT))
        create.setOnClickListener { pickMedia(create) }
    }

    override fun onDetachedFromWindow() {
        ++generation
        removeCallbacks(expiry)
        super.onDetachedFromWindow()
    }

    private fun item(name: String, description: String, face: View): View {
        val cell = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = description
            isFocusable = true
            isClickable = true
            background = GlassDrawable(act, GlassDrawable.Kind.NONE, act.dpf(16), rim = false)
            pressFeedback()
        }
        val rim = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                cornerRadius = act.dpf(19)
                setColor(Theme.p.glass)
                setStroke(act.dp(2), Theme.p.accent)
            }
            if (face is android.widget.TextView) face.gravity = Gravity.CENTER
            addView(face, FrameLayout.LayoutParams(if (face is AvatarView) act.dp(44) else MATCH,
                if (face is AvatarView) act.dp(44) else MATCH, Gravity.CENTER))
        }
        cell.addView(rim, lp(act.dp(52), act.dp(52)))
        cell.addView(act.label(name, 11.5f, 600).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp().apply { topMargin = act.dp(4) })
        face.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        entries.addView(cell, lp(act.dp(72), WRAP))
        return cell
    }

    fun reload() {
        val request = ++generation
        CoreClient.call("list_stories") { result, error ->
            if (!isAttachedToWindow || request != generation || error != null) return@call
            val stories = (result as? JSONArray)?.objects().orEmpty()
            removeCallbacks(expiry)
            stories.minOfOrNull { it.optLong("expires_at") }?.let {
                postDelayed(expiry, (it - System.currentTimeMillis()).coerceAtLeast(1))
            }
            if (entries.childCount > 1) entries.removeViews(1, entries.childCount - 1)
            val byAuthor = stories.groupBy { it.str("author_id") }.values
            val sequence = byAuthor.flatten()
            var index = 0
            for (published in byAuthor) {
                val story = published.first()
                val first = index
                index += published.size
                val author = story.str("author")
                val avatar = AvatarView(act, 44)
                avatar.set(author, story.str("author_id"), story.str("avatar_path"))
                item(author, "Истории: $author", avatar).setOnClickListener { act.openPage(StoryViewer(act, sequence, first)) }
            }
        }
    }

    private fun pickMedia(button: View) {
        val filePicker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        val gallery = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES) else filePicker
        val pick = if (gallery.resolveActivity(act.packageManager) != null) gallery else filePicker
        val account = CoreClient.accountId
        act.startForResult(pick) { code, result ->
            val uri = result?.data ?: return@startForResult
            if (code != Activity.RESULT_OK) return@startForResult
            button.isEnabled = false
            Thread {
                var temporary: File? = null
                val prepared = runCatching {
                    val data = act.pickOf(uri)
                    check(data.media) { "Выберите фото или видео" }
                    check(data.size <= STORY_LIMIT) { "Файл больше 50 МБ" }
                    val file = File.createTempFile("story-", ".media", act.cacheDir)
                    temporary = file
                    val input = checkNotNull(act.contentResolver.openInputStream(uri)) { "Не удалось открыть файл" }
                    input.use { source ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = source.read(buffer)
                                if (n <= 0) break
                                total += n
                                check(total <= STORY_LIMIT) { "Файл больше 50 МБ" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    val photo = if (data.image) checkNotNull(Photos.decode(file.path, 1440)) { "Не удалось открыть фото" } else null
                    val duration = if (data.video) checkNotNull(Videos.info(file.path)) { "Не удалось открыть видео" }
                        .durationMs.coerceAtMost(86_400_000).toInt() else 0
                    check(!data.video || duration > 0) { "Не удалось прочитать длину видео" }
                    StoryInput(file, data.name, data.mime, photo, duration)
                }
                act.runOnUiThread {
                    button.isEnabled = true
                    val item = prepared.getOrNull()
                    val active = isShown && CoreClient.accountId == account
                    if (!active || item == null) {
                        temporary?.delete()
                        if (active) act.toast(prepared.exceptionOrNull()?.message.orEmpty())
                    } else act.openPage(StoryEditor(act, item))
                }
            }.start()
        }
    }
}

internal class StoryInput(val file: File, val name: String, val mime: String, val photo: Bitmap?, val duration: Int)

/** Both the editor and the viewer use the same portrait canvas and normalized positions. */
internal class StoryStage(ctx: Context) : FrameLayout(ctx) {
    private val frame = FrameLayout(ctx)
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    var video: VideoView? = null
        private set
    var style = JSONObject()
    var drawing = false
    var edit = false
    var brushColor = 0xffffff
    var brushKind = 0
    private var textEditor: EditText? = null
    private var textHandles: View? = null
    private var stroke: JSONArray? = null
    private var selectedSticker: Int? = null
    private var selectedText = false
    private var gesture = 0 // 0 none, 1 drag sticker, 2 drag text, 3 resize/rotate sticker, 4 resize/rotate text
    private var touchX = 0f
    private var touchY = 0f
    private var pivotX = 0f
    private var pivotY = 0f
    private var pointerAngle = 0f
    private var pointerDistance = 1f
    private var initialScale = 1000
    private var initialAngle = 0
    private val textBounds = RectF()
    private val outlineInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = ctx.dpf(2)
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.dpf(8), ctx.dpf(6)), 0f)
    }
    private val handleInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff79b5ff.toInt() }
    fun selectSticker(index: Int) { selectedSticker = index; selectedText = false; markings.invalidate() }
    fun selectText() { selectedSticker = null; selectedText = style.str("text").isNotEmpty(); markings.invalidate() }
    fun clearSelection() { selectedSticker = null; selectedText = false; markings.invalidate() }

    private fun outline(canvas: Canvas, halfWidth: Float, halfHeight: Float) {
        val gap = context.dpf(9)
        canvas.drawRoundRect(-halfWidth-gap, -halfHeight-gap, halfWidth+gap, halfHeight+gap,
            context.dpf(7), context.dpf(7), outlineInk)
        // Drag the lower-right circular handle to resize and rotate the selected item.
        canvas.drawCircle(halfWidth+gap, halfHeight+gap, context.dpf(12), handleInk)
        canvas.drawCircle(halfWidth+gap, halfHeight+gap, context.dpf(5), outlineInk)
    }
    private fun radial(x: Float, y: Float, cx: Float, cy: Float): Float =
        kotlin.math.atan2(y-cy, x-cx)
    private fun distance(x: Float, y: Float, cx: Float, cy: Float): Float =
        kotlin.math.hypot(x-cx, y-cy).coerceAtLeast(1f)
    private fun local(x: Float, y: Float, cx: Float, cy: Float, scale: Float, degrees: Float): Pair<Float, Float> {
        val angle = Math.toRadians(degrees.toDouble()).toFloat()
        val co = kotlin.math.cos(angle)
        val si = kotlin.math.sin(angle)
        val dx = x-cx
        val dy = y-cy
        return Pair((dx*co + dy*si)/scale.coerceAtLeast(0.1f),
            (-dx*si + dy*co)/scale.coerceAtLeast(0.1f))
    }
    private fun beginGesture(x: Float, y: Float, cx: Float, cy: Float, scale: Int, angle: Int, handle: Boolean, text: Boolean) {
        pivotX = cx
        pivotY = cy
        touchX = x
        touchY = y
        pointerAngle = radial(x, y, cx, cy)
        pointerDistance = distance(x, y, cx, cy)
        initialScale = scale
        initialAngle = angle
        gesture = if (handle) { if (text) 4 else 3 } else { if (text) 2 else 1 }
    }
    private fun handleHit(x: Float, y: Float, cx: Float, cy: Float, halfW: Float, halfH: Float,
        scale: Float, angle: Float): Boolean {
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        val w = (halfW + context.dpf(9)) * scale
        val h = (halfH + context.dpf(9)) * scale
        val hx = cx + w*kotlin.math.cos(rad) - h*kotlin.math.sin(rad)
        val hy = cy + w*kotlin.math.sin(rad) + h*kotlin.math.cos(rad)
        return distance(x, y, hx, hy) < context.dpf(16)
    }
    private val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.style = Paint.Style.STROKE
    }
    private val lettering = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = weightFace(700)
    }
    private val path = Path()
    private val markings = object : View(ctx) {
        override fun onDraw(c: Canvas) {
            if (width <= 0 || height <= 0) return
            val color = Color.BLACK or style.optInt("color", 0xffffff)
            val lines = style.optJSONArray("lines") ?: JSONArray()
            val brushes = style.optJSONArray("brushes")
            for (i in 0 until lines.length()) {
                val line = lines.optJSONArray(i) ?: continue
                val brush = brushes?.optJSONObject(i)
                val kind = brush?.optInt("kind") ?: 0
                ink.color = Color.BLACK or (brush?.optInt("color") ?: color)
                ink.alpha = if (kind == 2) 145 else 255
                ink.strokeWidth = width * when (kind) { 1 -> 0.022f; 2 -> 0.038f; else -> 0.01f }
                if (line.length() == 1) {
                    val point = line.optJSONArray(0) ?: continue
                    c.drawPoint(point.optInt(0) * width / 1000f, point.optInt(1) * height / 1000f, ink)
                    continue
                }
                path.rewind()
                for (j in 0 until line.length()) {
                    val point = line.optJSONArray(j) ?: continue
                    val x = point.optInt(0) * width / 1000f
                    val y = point.optInt(1) * height / 1000f
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, ink)
            }
            val stickers = style.optJSONArray("stickers")
            stickerPaint.color = Color.WHITE
            stickerPaint.textSize = width * 0.12f
            for (i in 0 until (stickers?.length() ?: 0)) {
                val sticker = stickers?.optJSONObject(i) ?: continue
                c.save()
                c.translate(sticker.optInt("x") * width / 1000f, sticker.optInt("y") * height / 1000f)
                c.rotate(sticker.optInt("angle").toFloat())
                c.scale(sticker.optInt("scale", 1000) / 1000f, sticker.optInt("scale", 1000) / 1000f)
                c.drawText(sticker.str("emoji"), 0f, stickerPaint.textSize / 3f, stickerPaint)
                if (edit && !drawing && textEditor == null && selectedSticker == i)
                    outline(c, stickerPaint.textSize * 0.72f, stickerPaint.textSize * 0.75f)
                c.restore()
            }
            val text = style.str("text")
            textBounds.setEmpty()
            if (text.isEmpty() || textEditor != null) return
            lettering.color = Color.BLACK or style.optInt("text_color", color)
            lettering.typeface = storyTypeface(style.optInt("text_font"))
            lettering.textSize = width * 0.075f
            lettering.setShadowLayer(width * 0.007f, 0f, width * 0.003f, Color.BLACK)
            val alignment = when (style.optInt("text_align")) {
                1 -> Layout.Alignment.ALIGN_NORMAL
                2 -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_CENTER
            }
            val block = StaticLayout.Builder.obtain(text, 0, text.length, lettering, (width * 0.84f).toInt().coerceAtLeast(1))
                .setAlignment(alignment).setMaxLines(6).build()
            val centerX = width * style.optInt("text_x", 500) / 1000f
            val centerY = height * style.optInt("text_y", 500) / 1000f
            textBounds.set(centerX-block.width/2f, centerY-block.height/2f,
                centerX+block.width/2f, centerY+block.height/2f)
            c.save()
            c.translate(centerX, centerY)
            c.rotate(style.optInt("text_angle").toFloat())
            val scale = style.optInt("text_scale", 1000) / 1000f
            c.scale(scale, scale)
            c.translate(-block.width / 2f, -block.height / 2f)
            val backdrop = style.optInt("text_background")
            if (backdrop != 0) {
                stickerPaint.color = if (backdrop == 1) 0xb3151c28.toInt() else 0xff151c28.toInt()
                val inset = width * 0.025f
                c.drawRoundRect(-inset, -inset, block.width.toFloat()+inset, block.height.toFloat()+inset,
                    inset, inset, stickerPaint)
            }
            block.draw(c)
            if (edit && !drawing && selectedText) {
                c.translate(block.width / 2f, block.height / 2f)
                outline(c, block.width / 2f, block.height / 2f)
            }
            c.restore()
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val stickers = style.optJSONArray("stickers")
            if (!edit || (!drawing && style.str("text").isEmpty() && (stickers?.length() ?: 0) == 0)
                || width == 0 || height == 0) return false
            val x = (event.x * 1000 / width).toInt().coerceIn(0, 1000)
            val y = (event.y * 1000 / height).toInt().coerceIn(0, 1000)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                stroke = null
                gesture = 0
                return true
            }
            if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_MOVE) return true
            if (!drawing) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // The same transformed bounds are used for hit-testing and painting.
                    gesture = 0
                    stickerPaint.textSize = width * 0.12f
                    val halfSticker = stickerPaint.textSize * 0.75f
                    for (i in (0 until (stickers?.length() ?: 0)).reversed()) {
                        val obj = stickers?.optJSONObject(i) ?: continue
                        val cx = obj.optInt("x") * width / 1000f
                        val cy = obj.optInt("y") * height / 1000f
                        val scale = obj.optInt("scale", 1000) / 1000f
                        val angle = obj.optInt("angle").toFloat()
                        val point = local(event.x, event.y, cx, cy, scale, angle)
                        val handle = selectedSticker == i && handleHit(event.x, event.y, cx, cy,
                            halfSticker, halfSticker, scale, angle)
                        if (handle || (kotlin.math.abs(point.first) < halfSticker + context.dpf(12)
                                && kotlin.math.abs(point.second) < halfSticker + context.dpf(12))) {
                            selectedSticker = i
                            selectedText = false
                            beginGesture(event.x, event.y, cx, cy,
                                obj.optInt("scale", 1000), obj.optInt("angle"), handle, false)
                            invalidate()
                            return true
                        }
                    }
                    if (style.str("text").isNotEmpty() && !textBounds.isEmpty) {
                        val cx = textBounds.centerX()
                        val cy = textBounds.centerY()
                        val scale = style.optInt("text_scale", 1000) / 1000f
                        val angle = style.optInt("text_angle").toFloat()
                        val point = local(event.x, event.y, cx, cy, scale, angle)
                        val hw = textBounds.width() / 2f
                        val hh = textBounds.height() / 2f
                        val handle = selectedText && handleHit(event.x, event.y, cx, cy, hw, hh, scale, angle)
                        if (handle || (kotlin.math.abs(point.first) <= hw + context.dpf(12)
                                && kotlin.math.abs(point.second) <= hh + context.dpf(12))) {
                            selectedSticker = null
                            selectedText = true
                            beginGesture(event.x, event.y, cx, cy, style.optInt("text_scale", 1000),
                                style.optInt("text_angle"), handle, true)
                            invalidate()
                            return true
                        }
                    }
                    clearSelection()
                    return false
                }
                if (gesture == 0) return false
                val sticker = selectedSticker?.let { stickers?.optJSONObject(it) }
                if (gesture == 1 || gesture == 2) {
                    val dx = ((event.x - touchX) * 1000f / width).toInt()
                    val dy = ((event.y - touchY) * 1000f / height).toInt()
                    if (gesture == 1 && sticker != null) {
                        sticker.put("x", (sticker.optInt("x") + dx).coerceIn(0, 1000))
                        sticker.put("y", (sticker.optInt("y") + dy).coerceIn(0, 1000))
                    } else if (gesture == 2) {
                        style.put("text_x", (style.optInt("text_x", 500) + dx).coerceIn(0, 1000))
                        style.put("text_y", (style.optInt("text_y", 500) + dy).coerceIn(0, 1000))
                    }
                    touchX = event.x
                    touchY = event.y
                } else {
                    val ratio = distance(event.x, event.y, pivotX, pivotY) / pointerDistance
                    val scale = (initialScale * ratio).toInt().coerceIn(400, 2500)
                    val degrees = ((radial(event.x, event.y, pivotX, pivotY) - pointerAngle) * 180f /
                        Math.PI.toFloat()).toInt()
                    val angle = (((initialAngle + degrees + 180) % 360 + 360) % 360) - 180
                    if (gesture == 3 && sticker != null) {
                        sticker.put("scale", scale)
                        sticker.put("angle", angle)
                    } else if (gesture == 4) {
                        style.put("text_scale", scale)
                        style.put("text_angle", angle)
                    }
                }
            } else {
                val lines = style.optJSONArray("lines") ?: JSONArray().also { style.put("lines", it) }
                val points = (0 until lines.length()).sumOf { lines.optJSONArray(it)?.length() ?: 0 }
                if (points >= 400) return true
                if (event.actionMasked == MotionEvent.ACTION_DOWN && lines.length() < 24) {
                    val brushes = style.optJSONArray("brushes") ?: JSONArray().also { style.put("brushes", it) }
                    while (brushes.length() < lines.length()) {
                        brushes.put(JSONObject().put("color", style.optInt("color", 0xffffff)).put("kind", 0))
                    }
                    brushes.put(JSONObject().put("color", brushColor).put("kind", brushKind))
                    stroke = JSONArray().also { lines.put(it) }
                }
                stroke?.let { line ->
                    val last = line.optJSONArray(line.length() - 1)
                    if (last == null || kotlin.math.abs(last.optInt(0) - x) + kotlin.math.abs(last.optInt(1) - y) >= 7)
                        line.put(JSONArray().put(x).put(y))
                }
            }
            invalidate()
            return true
        }
    }

    init {
        frame.background = ctx.storyPanel(0xff0c111a.toInt())
        frame.clipToOutline = true
        frame.addView(image, LayoutParams(MATCH, MATCH))
        frame.addView(markings, LayoutParams(MATCH, MATCH))
        addView(frame, LayoutParams(0, 0, Gravity.CENTER))
    }

    override fun onMeasure(w: Int, h: Int) {
        val availableWidth = MeasureSpec.getSize(w)
        val availableHeight = MeasureSpec.getSize(h)
        val side = if (textEditor != null) availableWidth else minOf(availableWidth, availableHeight * 9 / 16)
        frame.layoutParams.width = side
        frame.layoutParams.height = side * 16 / 9
        textEditor?.let {
            val textSize = (side * 0.075f).coerceAtLeast(context.dpf(16))
            if (it.textSize != textSize) it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            it.layoutParams.width = (side * 0.89f).toInt()
            it.maxHeight = (minOf(frame.layoutParams.height, availableHeight) * 0.9f).toInt()
        }
        super.onMeasure(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyLiveTextTransform()
    }

    private fun applyLiveTextTransform() {
        textEditor?.let {
            val room = ((minOf(frame.height, height) - it.height) / 2f).coerceAtLeast(0f)
            it.translationX = frame.width * (style.optInt("text_x", 500) - 500) / 1000f
            it.translationY = (frame.height * (style.optInt("text_y", 500) - 500) / 1000f).coerceIn(-room, room)
            val scale = style.optInt("text_scale", 1000) / 1000f
            it.scaleX = scale
            it.scaleY = scale
            it.rotation = style.optInt("text_angle").toFloat()
            textHandles?.invalidate()
        }
    }

    fun editText(field: EditText?) {
        textHandles?.let { frame.removeView(it) }
        textHandles = null
        textEditor?.let { frame.removeView(it) }
        textEditor = field
        field?.let {
            frame.addView(it, LayoutParams(WRAP, WRAP, Gravity.CENTER))
            val handleLayer = object : View(context) {
                override fun onDraw(c: Canvas) {
                    val editor = textEditor ?: return
                    if (editor.width == 0 || editor.height == 0) return
                    val cx = editor.left + editor.width/2f + editor.translationX
                    val cy = editor.top + editor.height/2f + editor.translationY
                    c.save()
                    c.translate(cx, cy)
                    c.rotate(editor.rotation)
                    c.scale(editor.scaleX, editor.scaleY)
                    outline(c, editor.width / 2f, editor.height / 2f)
                    c.restore()
                }

                @android.annotation.SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(e: MotionEvent): Boolean {
                    val editor = textEditor ?: return false
                    val cx = editor.left + editor.width/2f + editor.translationX
                    val cy = editor.top + editor.height/2f + editor.translationY
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (!handleHit(e.x, e.y, cx, cy, editor.width / 2f, editor.height / 2f,
                                editor.scaleX, editor.rotation)) return false
                        beginGesture(e.x, e.y, cx, cy, style.optInt("text_scale", 1000),
                            style.optInt("text_angle"), true, true)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (gesture != 4) return false
                    if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                        val ratio = distance(e.x, e.y, pivotX, pivotY) / pointerDistance
                        style.put("text_scale", (initialScale * ratio).toInt().coerceIn(400, 2500))
                        val change = ((radial(e.x, e.y, pivotX, pivotY) - pointerAngle) *
                            180f / Math.PI.toFloat()).toInt()
                        style.put("text_angle", (((initialAngle + change + 180) % 360 + 360) % 360) - 180)
                        applyLiveTextTransform()
                        return true
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                        gesture = 0
                        return true
                    }
                    return true
                }
            }
            textHandles = handleLayer
            frame.addView(handleLayer, LayoutParams(MATCH, MATCH))
            field.post { applyLiveTextTransform() }
        }
        markings.invalidate()
    }

    fun changes() {
        val saturation = 1f + style.optInt("saturation") / 100f
        val contrast = 1f + style.optInt("contrast") / 100f
        val shift = style.optInt("brightness") * 1.75f + (1f - contrast) * 128f
        val matrix = ColorMatrix().apply {
            setSaturation(saturation)
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, shift, 0f, contrast, 0f, 0f, shift,
                0f, 0f, contrast, 0f, shift, 0f, 0f, 0f, 1f, 0f,
            )))
        }
        if (image.drawable != null) image.colorFilter = ColorMatrixColorFilter(matrix)
        markings.invalidate()
    }

    fun photo(bitmap: Bitmap?) {
        stop()
        image.setImageBitmap(bitmap)
        changes()
    }

    fun movie(path: String, prepared: (android.media.MediaPlayer) -> Unit, finished: () -> Unit, error: () -> Unit) {
        photo(null)
        val player = object : VideoView(context) {
            private var resumeOnShow = false

            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                if (visibility != VISIBLE && isPlaying) {
                    resumeOnShow = true
                    pause()
                } else if (visibility == VISIBLE && resumeOnShow) {
                    resumeOnShow = false
                    start()
                }
            }
        }
        video = player
        frame.addView(player, 1, LayoutParams(MATCH, MATCH, Gravity.CENTER))
        player.setOnPreparedListener { prepared(it) }
        player.setOnCompletionListener { finished() }
        player.setOnErrorListener { _, _, _ -> error(); true }
        player.setVideoPath(path)
    }

    fun stop() {
        video?.let { it.stopPlayback(); frame.removeView(it) }
        video = null
    }
}

@UnstableApi
internal class StoryEditor(act: MainActivity, private val input: StoryInput, private val revision: JSONObject? = null) :
    StoryPage(act, if (revision == null) "Новая история" else "Изменить историю") {
    private val stage = StoryStage(act).apply {
        if (revision != null) style = JSONObject(revision.optJSONObject("style")?.toString() ?: "{}")
    }
    private val style = stage.style
    private val isVideo = input.duration > 0
    // Published video can change its overlays and mute flag, but not its original media bytes.
    private val timeline = if (isVideo && revision == null) StoryTimeline(act, input.duration) else null
    private val workspace = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val footer = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val tools = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val audiencePane = StoryAudience(act, { title.text = it }, ::submit)
    private val captionButton = action("Добавить подпись").apply {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(act.dp(14), 0, act.dp(8), 0)
        setTag(Theme.TAG_ROLE, null)
        setTextColor(STORY_MUTED)
    }
    private val timer = action("24 ч").apply { minimumWidth = act.dp(64); contentDescription = "Срок истории, 24 часа" }
    private val play = icon(R.drawable.ic_pause, "Пауза")
    private val sound = icon(R.drawable.ic_speaker, "Выключить звук")
    private var caption = revision?.str("caption").orEmpty()
    private var hours = 24
    private var updating = false
    @Volatile private var gone = false
    private var player: android.media.MediaPlayer? = null
    private var temporary: File? = null
    private var export: Transformer? = null
    private var closeTool: ((Boolean) -> Unit)? = null
    private val clipPreview = object : Runnable {
        override fun run() {
            val movie = stage.video ?: return
            val strip = timeline ?: return
            if (!movie.isPlaying) return
            if (movie.currentPosition >= strip.last) resetPreview()
            else {
                strip.position = movie.currentPosition
                stage.postDelayed(this, 80)
            }
        }
    }

    init {
        stage.edit = true
        if (revision == null) style.put("color", 0xffffff)
        column.isFocusableInTouchMode = true
        column.addView(workspace, lp(MATCH, 0, 1f))
        column.addView(audiencePane, lp(MATCH, 0, 1f))
        workspace.addView(stage, lp(MATCH, 0, 1f))
        timeline?.let { strip ->
            workspace.addView(strip, lp(MATCH, act.dp(62)))
            strip.onSeek = { position ->
                pausePreview()
                player?.seekTo(position.coerceAtMost(input.duration - 1).toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
            }
        }
        workspace.addView(footer, lp().apply { topMargin = act.dp(6); bottomMargin = act.dp(6) })
        footer.addView(tools, lp())

        val captionRow = LinearLayout(act).apply { background = act.storyPanel() }
        captionRow.addView(captionButton, lp(0, act.dp(48), 1f))
        captionRow.addView(timer, lp(WRAP, act.dp(48)))
        tools.addView(captionRow, lp())
        if (revision != null) {
            timer.visibility = View.GONE
            captionButton.text = caption.ifEmpty { "Добавить подпись" }
            captionButton.setTextColor(if (caption.isEmpty()) STORY_MUTED else Color.WHITE)
        }
        captionButton.setOnClickListener { editCaption() }
        timer.setOnClickListener {
            val menu = PopupMenu(act, timer)
            for (value in listOf(6, 12, 24, 48)) {
                val label = "$value " + if (value == 24) "часа" else "часов"
                menu.add(label, checked = value == hours) {
                    hours = value
                    timer.text = "$value ч"
                    timer.contentDescription = "Срок истории, $label"
                }
            }
            menu.show()
        }

        val controls = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(icon(R.drawable.ic_edit, "Рисовать").apply { setOnClickListener { markup() } })
        controls.addView(icon(R.drawable.ic_smile, "Добавить стикер").apply { setOnClickListener { markup("Стикеры") } })
        controls.addView(action("T").apply {
            textSize = 24f
            contentDescription = "Текст на кадре"
            tooltipText = contentDescription
            setOnClickListener { editOverlay() }
        }, lp(act.dp(48), act.dp(48)))
        if (!isVideo) controls.addView(icon(R.drawable.ic_palette, "Цвет фото").apply { setOnClickListener { photoAdjustments() } })
        controls.addView(View(act), lp(0, 1, 1f))
        controls.addView(PillButton(act, if (revision == null) "Далее" else "Сохранить").apply {
            setOnClickListener { if (revision == null) showAudience() else saveRevision(this) }
        }, lp(WRAP, act.dp(48)))
        tools.addView(controls, lp().apply { topMargin = act.dp(8) })
        if (isVideo) {
            bar.addView(play)
            bar.addView(sound)
            play.setOnClickListener {
                val movie = stage.video ?: return@setOnClickListener
                if (movie.isPlaying) pausePreview()
                else {
                    timeline?.let { strip ->
                        if (strip.position !in strip.first until strip.last) movie.seekTo(strip.first)
                    }
                    startPreview()
                }
            }
            sound.setIcon(if (style.optBoolean("muted")) R.drawable.ic_mic_off else R.drawable.ic_speaker,
                if (style.optBoolean("muted")) "Включить звук" else "Выключить звук")
            sound.setOnClickListener {
                val muted = !style.optBoolean("muted")
                style.put("muted", muted)
                val gain = if (muted) 0f else 1f
                player?.setVolume(gain, gain)
                sound.setIcon(if (muted) R.drawable.ic_mic_off else R.drawable.ic_speaker,
                    if (muted) "Включить звук" else "Выключить звук")
            }
            previewVideo()
            loadThumbnails()
        } else stage.photo(input.photo)

    }

    private fun icon(resource: Int, label: String) =
        IconButton(act, resource, label, iconRole = Theme.Role.ON_ACCENT).apply { effect = null }

    private fun action(text: String) = act.storyLabel(text, 14f, 600).apply {
        gravity = Gravity.CENTER
        minimumHeight = act.dp(48)
        isFocusable = true
        isClickable = true
        pressFeedback()
    }

    private fun startTool(panel: View, chrome: LinearLayout? = null, done: (Boolean) -> Unit) {
        tools.visibility = View.GONE
        timeline?.visibility = View.GONE
        pausePreview()
        val oldHeader = (0 until bar.childCount).map { bar.getChildAt(it) to bar.getChildAt(it).visibility }
        val actions = if (chrome == null) LinearLayout(act).apply {
            addView(action("Отмена").apply { setOnClickListener { closeTool?.invoke(false) } }, lp(0, WRAP, 1f))
            addView(action("Готово").apply { setOnClickListener { closeTool?.invoke(true) } }, lp(0, WRAP, 1f))
        } else null
        if (chrome != null) {
            oldHeader.forEach { (view, _) -> view.visibility = View.GONE }
            bar.addView(chrome, lp(MATCH, MATCH))
        }
        footer.addView(panel, lp())
        actions?.let { footer.addView(it, lp()) }
        closeTool = { accepted ->
            closeTool = null
            finishInput()
            footer.removeView(panel)
            actions?.let { footer.removeView(it) }
            chrome?.let {
                bar.removeView(it)
                oldHeader.forEach { (view, visibility) -> view.visibility = visibility }
            }
            tools.visibility = View.VISIBLE
            timeline?.visibility = View.VISIBLE
            done(accepted)
        }
    }

    private fun finishInput() {
        act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(column.windowToken, 0)
        column.requestFocus()
    }

    private fun focusText(field: EditText) {
        field.requestFocus()
        field.post {
            if (field.hasFocus()) act.getSystemService(InputMethodManager::class.java)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun EditText.onStoryTextChanged(changed: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(text: Editable?) = changed(text.toString())
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
    })

    private fun storyField(hint: String, initial: String, limit: Int) = EditText(act).apply {
        this.hint = hint
        contentDescription = hint
        setTextColor(Color.WHITE)
        setHintTextColor(STORY_MUTED)
        setTextSize(16f)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(limit))
        setText(initial)
        setSelection(length())
    }

    private fun editCaption() {
        val field = storyField("Подпись к истории", caption, 240).apply {
            setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
            minimumHeight = act.dp(48)
            background = act.storyPanel()
            maxLines = 3
        }
        startTool(field) { accepted ->
            if (accepted) {
                caption = field.text.toString().trim()
                captionButton.text = caption.ifEmpty { "Добавить подпись" }
                captionButton.setTextColor(if (caption.isEmpty()) STORY_MUTED else Color.WHITE)
            }
        }
        focusText(field)
    }

    private fun editOverlay(returnToMarkup: Boolean = false) {
        val keys = listOf("text", "text_color", "text_align", "text_font", "text_background")
        val before = keys.associateWith { style.opt(it) }
        val field = storyField("Текст", style.str("text"), 100).apply {
            setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6))
            maxLines = 6
        }
        val formats = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; background = act.storyPanel() }
        val tint = action("●").apply {
            textSize = 24f
            setTag(Theme.TAG_ROLE, null)
            contentDescription = "Цвет текста"
        }
        val align = action("Центр").apply { textSize = 12f; minWidth = act.dp(72) }
        val fill = action("A").apply { textSize = 20f }
        val font = action("").apply { setPadding(act.dp(8), 0, act.dp(8), 0); maxLines = 1 }
        val fonts = listOf("Системный", "С засечками", "Моно")
        val alignments = listOf("По центру", "По левому краю", "По правому краю")
        val fills = listOf("Без фона", "Полупрозрачный фон", "Сплошной фон")
        fun format() {
            val color = Color.BLACK or style.optInt("text_color", style.optInt("color", 0xffffff))
            val alignIndex = style.optInt("text_align").coerceIn(0, 2)
            val fillIndex = style.optInt("text_background").coerceIn(0, 2)
            val fontIndex = style.optInt("text_font").coerceIn(0, 2)
            tint.setTextColor(color)
            field.setTextColor(color)
            field.typeface = storyTypeface(fontIndex)
            field.gravity = Gravity.CENTER_VERTICAL or when (alignIndex) {
                1 -> Gravity.START
                2 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            field.background = GradientDrawable().apply {
                cornerRadius = act.dpf(8)
                setStroke(act.dp(1), 0xffb0c8ec.toInt(), act.dpf(6), act.dpf(4))
                setColor(when (fillIndex) { 1 -> 0xb3151c28.toInt(); 2 -> 0xff151c28.toInt(); else -> Color.TRANSPARENT })
            }
            align.text = listOf("Центр", "Слева", "Справа")[alignIndex]
            align.contentDescription = "Выравнивание текста: ${alignments[alignIndex]}"
            fill.contentDescription = fills[fillIndex]
            fill.background = if (fillIndex == 0) null else act.storyPanel(0xff41516c.toInt())
            font.text = fonts[fontIndex]
            font.contentDescription = "Шрифт, ${fonts[fontIndex]}"
        }
        tint.setOnClickListener {
            chooseColor(field.currentTextColor) { value -> style.put("text_color", value); format() }
        }
        align.setOnClickListener {
            val menu = PopupMenu(act, align)
            alignments.forEachIndexed { i, name ->
                menu.add(name, checked = style.optInt("text_align") == i) {
                    style.put("text_align", i)
                    format()
                }
            }
            menu.show()
        }
        fill.setOnClickListener { style.put("text_background", (style.optInt("text_background") + 1) % 3); format() }
        font.setOnClickListener {
            val menu = PopupMenu(act, font)
            fonts.forEachIndexed { i, name ->
                menu.add(name, checked = i == style.optInt("text_font")) { style.put("text_font", i); format() }
            }
            menu.show()
        }
        formats.addView(tint, lp(act.dp(42), act.dp(48)))
        formats.addView(align, lp(act.dp(72), act.dp(48)))
        formats.addView(fill, lp(act.dp(42), act.dp(48)))
        formats.addView(icon(R.drawable.ic_smile, "Вставить эмодзи").apply {
            setOnClickListener {
                Emoji.sheet(act, "Эмодзи") { emoji ->
                    val first = minOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
                    val last = maxOf(field.selectionStart, field.selectionEnd).coerceAtLeast(first)
                    field.text.replace(first, last, emoji)
                }
            }
        })
        formats.addView(View(act), lp(0, 1, 1f))
        formats.addView(font, lp(WRAP, act.dp(48)))

        val chrome = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        chrome.addView(action("Очистить").apply {
            setPadding(act.dp(8), 0, act.dp(8), 0)
            setOnClickListener { field.text.clear() }
        }, lp(WRAP, act.dp(48)))
        chrome.addView(View(act), lp(0, 1, 1f))
        chrome.addView(action("Готово").apply {
            setPadding(act.dp(8), 0, act.dp(8), 0)
            setOnClickListener { closeTool?.invoke(true) }
        }, lp(WRAP, act.dp(48)))
        field.onStoryTextChanged { style.put("text", it) }
        format()
        stage.editText(field)
        startTool(formats, chrome) { accepted ->
            if (accepted) style.put("text", field.text.toString().trim())
            else before.forEach { (key, value) -> style.put(key, value) }
            stage.editText(null)
            stage.selectText()
            stage.changes()
            if (returnToMarkup) markup("Текст")
        }
        focusText(field)
    }

    private fun chooseColor(color: Int, chosen: (Int) -> Unit) {
        val sheet = Sheet(act, "Цвет")
        val picker = ColorPickerView(act).apply { setColor(Color.BLACK or color) }
        sheet.add(picker)
        sheet.add(PillButton(act, "Применить").apply {
            setOnClickListener {
                chosen(picker.color and 0xffffff)
                sheet.dismiss()
            }
        })
        sheet.show()
    }

    private fun markup(initial: String = "Рисунок") {
        val keys = listOf("lines", "brushes", "stickers")
        val original = keys.associateWith { style.optJSONArray(it)?.let { data -> JSONArray(data.toString()) } }
        val dock = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; background = act.storyPanel() }
        val tabs = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val chrome = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        var mode = initial
        val undo = icon(R.drawable.ic_reply, "Отменить последнее действие")
        val clear = action("Очистить всё").apply { setPadding(act.dp(8), 0, act.dp(8), 0) }
        val modes = listOf("Рисунок", "Стикеры", "Текст")
        val switches = modes.associateWith { name -> action(name).apply { textSize = 13f } }

        fun addSticker(emoji: String) {
            val stickers = style.optJSONArray("stickers") ?: JSONArray().also { style.put("stickers", it) }
            val index = stickers.length()
            if (index >= 8) return act.toast("Можно добавить до восьми стикеров")
            stickers.put(JSONObject().put("emoji", emoji).put("x", 260 + index % 3 * 240).put("y", 260 + index / 3 * 140)
                .put("scale", 1000).put("angle", 0))
            stage.selectSticker(index)
            stage.changes()
        }
        fun renderTools() {
            val drawing = mode == "Рисунок"
            stage.drawing = drawing
            toolbar.removeAllViews()
            undo.visibility = if (mode == "Текст") View.GONE else View.VISIBLE
            clear.visibility = undo.visibility
            switches.forEach { (name, tab) ->
                tab.isSelected = name == mode
                tab.background = if (tab.isSelected) act.storyPanel() else null
                tab.describeState(if (tab.isSelected) "Выбрано" else "")
            }
            when (mode) {
                "Рисунок" -> {
                    val sample = action("●").apply {
                        textSize = 28f
                        setTag(Theme.TAG_ROLE, null)
                        setTextColor(Color.BLACK or stage.brushColor)
                        contentDescription = "Цвет кисти"
                        setOnClickListener { chooseColor(stage.brushColor) { value -> stage.brushColor = value; setTextColor(Color.BLACK or value) } }
                    }
                    toolbar.addView(sample, lp(0, act.dp(48), 1f))
                    listOf("Перо", "Кисть", "Маркер").forEachIndexed { index, name ->
                        toolbar.addView(action("━").apply {
                            textSize = 16f + index * 5f
                            contentDescription = name
                            isSelected = index == stage.brushKind
                            if (isSelected) background = act.storyPanel(0xff41516c.toInt())
                            setOnClickListener { stage.brushKind = index; renderTools() }
                        }, lp(0, act.dp(48), 1f))
                    }
                }
                "Стикеры" -> {
                    for (emoji in Emoji.QUICK.take(5)) {
                        toolbar.addView(action(emoji).apply { textSize = 22f; setOnClickListener { addSticker(emoji) } },
                            lp(0, act.dp(48), 1f))
                    }
                    toolbar.addView(icon(R.drawable.ic_smile, "Все стикеры").apply {
                        setOnClickListener { Emoji.sheet(act, "Стикеры", ::addSticker) }
                    })
                }
                else -> toolbar.addView(action(if (style.str("text").isEmpty()) "Добавить текст" else "Изменить текст").apply {
                    setOnClickListener { closeTool?.invoke(true); editOverlay(returnToMarkup = true) }
                }, lp())
            }
        }
        undo.setOnClickListener {
            val changed = if (mode == "Рисунок") listOf("lines", "brushes") else listOf("stickers")
            for (key in changed) style.optJSONArray(key)?.let { if (it.length() > 0) it.remove(it.length() - 1) }
            stage.changes()
        }
        clear.setOnClickListener {
            if (mode == "Рисунок") { style.remove("lines"); style.remove("brushes") }
            else style.remove("stickers")
            stage.changes()
        }
        chrome.addView(undo)
        chrome.addView(View(act), lp(0, 1, 1f))
        chrome.addView(clear, lp(WRAP, MATCH))
        tabs.addView(icon(R.drawable.ic_close, "Отмена").apply {
            setOnClickListener { closeTool?.invoke(false) }
        })
        for ((name, tab) in switches) {
            tab.setOnClickListener {
                if (name == "Текст") { closeTool?.invoke(true); editOverlay(returnToMarkup = true) }
                else { mode = name; renderTools() }
            }
            tabs.addView(tab, lp(0, act.dp(48), 1f))
        }
        tabs.addView(icon(R.drawable.ic_check, "Готово").apply {
            setOnClickListener { closeTool?.invoke(true) }
        })
        dock.addView(toolbar, lp())
        dock.addView(tabs, lp().apply { topMargin = act.dp(8) })
        startTool(dock, chrome) { accepted ->
            stage.drawing = false
            if (!accepted) original.forEach { (key, value) -> style.put(key, value) }
            stage.changes()
        }
        renderTools()
    }

    private fun photoAdjustments() {
        val panel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val labels = listOf("Яркость" to "brightness", "Контраст" to "contrast", "Насыщенность" to "saturation")
        val initial = labels.associate { (_, key) -> key to style.optInt(key) }
        for ((name, key) in labels) {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(act.storyLabel(name, 13f), lp(act.dp(104), WRAP))
            row.addView(SeekBar(act).apply {
                max = 200
                progress = initial.getValue(key) + 100
                contentDescription = name
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) { style.put(key, value - 100); stage.changes() }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }, lp(0, act.dp(40), 1f))
            panel.addView(row, lp())
        }
        startTool(panel) { accepted ->
            if (!accepted) initial.forEach { (key, value) -> style.put(key, value) }
            stage.changes()
        }
    }

    private fun pausePreview() {
        stage.video?.pause()
        stage.removeCallbacks(clipPreview)
        if (play.contentDescription != "Воспроизвести") play.setIcon(R.drawable.ic_play, "Воспроизвести")
    }

    private fun startPreview() {
        stage.video?.start()
        play.setIcon(R.drawable.ic_pause, "Пауза")
        stage.removeCallbacks(clipPreview)
        stage.postDelayed(clipPreview, 80)
    }

    private fun resetPreview() {
        val strip = timeline ?: return
        pausePreview()
        stage.video?.seekTo(strip.first)
        strip.position = strip.first
    }

    private fun previewVideo() {
        val strip = timeline
        player = null
        play.isEnabled = false
        sound.isEnabled = false
        stage.movie(input.file.path, { media ->
            player = media
            play.isEnabled = true
            sound.isEnabled = true
            val gain = if (style.optBoolean("muted")) 0f else 1f
            media.setVolume(gain, gain)
            if (strip != null) stage.video?.seekTo(strip.position.coerceIn(strip.first, strip.last - 1))
            if (workspace.visibility == View.VISIBLE && closeTool == null) startPreview()
            else pausePreview()
        }, {
            if (strip != null) resetPreview()
            else { pausePreview(); stage.video?.seekTo(0) }
        }, { act.toast("Не удалось воспроизвести видео") })
    }

    private fun loadThumbnails() {
        val strip = timeline ?: return
        Thread {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(input.file.path)
                    for (index in 0 until STORY_THUMBNAILS) {
                        if (gone) break
                        val time = input.duration * 1_000L * (2 * index + 1) / (2 * STORY_THUMBNAILS)
                        val frame = runCatching {
                            if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 84, 84)
                                ?: retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 84, 84)
                            else retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                                Bitmap.createScaledBitmap(full, 84, 84, true).also { if (it !== full) full.recycle() }
                            }
                        }.getOrNull() ?: continue
                        act.runOnUiThread {
                            if (gone) frame.recycle() else strip.thumbnail(index, frame)
                        }
                    }
                } finally { retriever.release() }
            }
        }.start()
    }

    private fun saveRevision(button: View) {
        val published = revision ?: return
        if (updating) return
        updating = true
        button.isEnabled = false
        stage.clearSelection()
        pausePreview()
        val updatedStyle = JSONObject(style.toString())
        val updatedCaption = caption.trim()
        CoreClient.call("manage_story", JSONObject()
            .put("story_id", published.str("id"))
            .put("caption", updatedCaption)
            .put("style", updatedStyle)) { _, error ->
            if (gone) return@call
            updating = false
            button.isEnabled = true
            if (error != null) act.toast(error.message)
            else {
                published.put("caption", updatedCaption)
                published.put("style", updatedStyle)
                act.toast("Изменения сохранены")
                act.closePage()
            }
        }
    }

    override fun onShown() {
        super.onShown()
        if (revision?.optJSONObject("options")?.optBoolean("screenshots", true) == false)
            act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun showAudience() {
        pausePreview()
        finishInput()
        stage.clearSelection()
        workspace.visibility = View.GONE
        play.visibility = View.GONE
        sound.visibility = View.GONE
        audiencePane.open()
    }

    private fun submit(ids: List<String>) {
        if (updating) return
        updating = true
        audiencePane.setBusy(true)
        val draft = JSONObject(style.toString())
        val clipStart = timeline?.first ?: 0
        val clipEnd = timeline?.last ?: 0
        val payload = JSONObject().put("conversation_ids", JSONArray(ids)).put("hours", hours)
            .put("caption", caption).put("style", draft).put("options", audiencePane.options())
            .put("_account_id", CoreClient.accountId)
        fun send(file: File, name: String, mime: String) {
            payload.put("path", file.path).put("name", name).put("mime", mime)
            if (isVideo) payload.put("duration", clipEnd - clipStart)
            Thread {
                val (_, error) = CoreClient.callBlocking("publish_story", payload)
                if (file !== input.file) file.delete()
                if (error == null) input.file.delete()
                act.runOnUiThread {
                    if (gone) { input.file.delete(); return@runOnUiThread }
                    if (error == null) {
                        act.toast("История в очереди отправки")
                        act.closePage()
                    } else recover(error.message)
                }
            }.start()
        }
        if (!isVideo || (clipStart == 0 && clipEnd == input.duration && !draft.optBoolean("muted"))) {
            send(input.file, input.name, input.mime)
            return
        }
        player = null
        stage.stop()
        val out = runCatching { File.createTempFile("story-clip-", ".mp4", act.cacheDir) }.getOrElse {
            recover("Не удалось создать файл видео")
            return
        }
        temporary = out
        export = runCatching {
            exportStoryClip(act, input.file, out, clipStart, clipEnd, draft.optBoolean("muted")) { success ->
                export = null
                temporary = null
                if (gone) { out.delete(); return@exportStoryClip }
                val error = when {
                    !success -> "Не удалось обрезать видео"
                    out.length() !in 1..STORY_LIMIT -> "Обрезанное видео больше 50 МБ"
                    else -> null
                }
                if (error != null) { out.delete(); recover(error) }
                else send(out, input.name.substringBeforeLast('.') + ".mp4", "video/mp4")
            }
        }.getOrElse {
            temporary = null
            out.delete()
            recover("Не удалось обрезать видео")
            null
        }
    }

    private fun recover(message: String) {
        updating = false
        audiencePane.setBusy(false)
        act.toast(message)
    }

    override fun onBack(): Boolean {
        if (updating) {
            act.toast("История готовится")
            return true
        }
        closeTool?.let { it(false); return true }
        if (audiencePane.visibility != View.VISIBLE) return false
        finishInput()
        if (audiencePane.back()) return true
        audiencePane.collapse()
        workspace.visibility = View.VISIBLE
        title.text = "Новая история"
        if (isVideo) {
            play.visibility = View.VISIBLE
            sound.visibility = View.VISIBLE
            if (stage.video == null) previewVideo()
        }
        return true
    }

    override fun onHidden() {
        gone = true
        audiencePane.collapse()
        finishInput()
        player = null
        stage.removeCallbacks(clipPreview)
        stage.stop()
        if (revision != null) Prefs.applySecure(act)
        if (export != null) {
            export?.cancel()
            temporary?.delete()
            input.file.delete()
        } else if (!updating) input.file.delete()
    }
}

internal class StoryViewer(act: MainActivity, private val stories: List<JSONObject>, private var index: Int) :
    StoryPage(act, "") {
    private val stage = StoryStage(act)
    private val progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    private val caption = act.storyLabel("", 15f).apply {
        gravity = Gravity.CENTER
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        background = act.storyPanel()
    }
    private val quickActions = LinearLayout(act).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val play = IconButton(act, R.drawable.ic_pause, "Пауза", iconRole = Theme.Role.ON_ACCENT)
    private val more = IconButton(act, R.drawable.ic_more, "Меню истории", iconRole = Theme.Role.ON_ACCENT)
    private var generation = 0
    private var active = false
    private var elapsed = 0L
    private var last = 0L
    private var duration = 6_000L
    private var playing = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            val story = stories[index]
            if (System.currentTimeMillis() >= story.optLong("display_until", story.optLong("expires_at"))) return next(1)
            val now = SystemClock.uptimeMillis()
            if (playing && stage.isShown && stage.hasWindowFocus()) elapsed += now - last
            last = now
            progress.progress = (elapsed * 1000 / duration).toInt().coerceAtMost(1000)
            if (elapsed >= duration) return next(1)
            stage.postDelayed(this, 50)
        }
    }

    init {
        bar.addView(play)
        bar.addView(more)
        more.setOnClickListener { openMenu() }
        column.addView(progress, lp(MATCH, act.dp(3)))
        val media = FrameLayout(act)
        media.addView(stage, FrameLayout.LayoutParams(MATCH, MATCH))
        val zones = LinearLayout(act)
        for ((label, offset) in listOf("Предыдущая история" to -1, "Следующая история" to 1)) {
            zones.addView(View(act).apply {
                contentDescription = label
                isFocusable = true
                setOnClickListener { next(offset) }
            }, lp(0, MATCH, if (offset < 0) 1f else 2f))
        }
        media.addView(zones, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(media, lp(MATCH, 0, 1f).apply { topMargin = act.dp(6); bottomMargin = act.dp(6) })
        column.addView(caption, lp(MATCH, WRAP).apply { bottomMargin = act.dp(8) })
        quickActions.addView(PillButton(act, "Реакция", primary = false, icon = R.drawable.ic_smile).apply {
            setOnClickListener { react(selectedStory()) }
        }, lp(0, WRAP, 1f))
        quickActions.addView(PillButton(act, "Комментировать", primary = false, icon = R.drawable.ic_chat).apply {
            setOnClickListener { comment(selectedStory()) }
        }, lp(0, WRAP, 1f).apply { marginStart = act.dp(8) })
        column.addView(quickActions, lp(MATCH, WRAP).apply { bottomMargin = act.dp(8) })
        play.setOnClickListener {
            playing = !playing
            last = SystemClock.uptimeMillis()
            play.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Пауза" else "Смотреть")
            if (playing) stage.video?.start() else stage.video?.pause()
        }
    }


    private fun pauseForMenu() {
        playing = false
        stage.video?.pause()
        play.setIcon(R.drawable.ic_play, "Продолжить")
    }

    private fun selectedStory(): JSONObject = stories[index]

    private fun openMenu() {
        pauseForMenu()
        val story = selectedStory()
        val mine = story.optBoolean("mine")
        val options = story.optJSONObject("options") ?: JSONObject()
        val popup = PopupMenu(act, more)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = act.storyPanel(0xf0222935.toInt())
            setPadding(act.dp(6), act.dp(8), act.dp(6), act.dp(8))
        }
        fun entry(icon: Int, text: String, detail: String = "", danger: Boolean = false, action: () -> Unit) {
            val line = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = act.dp(if (detail.isEmpty()) 54 else 64)
                pressFeedback()
                contentDescription = if (detail.isEmpty()) text else text + ". " + detail
                setOnClickListener { popup.dismiss(); action() }
            }
            val symbol = ImageView(act).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (danger) 0xfffa6973.toInt() else Color.WHITE)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            line.addView(symbol, lp(act.dp(24), act.dp(24)).apply {
                marginStart = act.dp(14); marginEnd = act.dp(16)
            })
            val labels = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(act.storyLabel(text, 15f, 600).apply {
                setTextColor(if (danger) 0xfffa6973.toInt() else Color.WHITE)
            })
            if (detail.isNotEmpty()) labels.addView(act.storyLabel(detail, 12f, 400).apply {
                setTextColor(STORY_MUTED)
            }, lp().apply { topMargin = act.dp(3) })
            line.addView(labels, lp(0, WRAP, 1f))
            panel.addView(line, lp(MATCH, WRAP))
        }
        if (mine) {
            entry(R.drawable.ic_file, "Добавить в альбом") { editAlbum(story) }
            val audience = when (options.str("audience")) {
                "contacts" -> "Мои контакты"
                "friends" -> "Близкие друзья"
                "selected" -> "Выбранные пользователи"
                else -> "Все"
            }
            entry(R.drawable.ic_people, "Кто видит историю", audience) { showAudience(story) }
            entry(R.drawable.ic_edit, "Изменить историю") { openRevisionEditor(story) }
            entry(R.drawable.ic_profile, if (options.optBoolean("profile")) "Убрать из профиля"
                else "Опубликовать в профиле") {
                manageStory(story, JSONObject().put("profile", !options.optBoolean("profile")))
            }
        } else {
            entry(R.drawable.ic_reply, "Комментировать") { comment(story) }
            entry(R.drawable.ic_smile, "Поставить реакцию") { react(story) }
        }
        if (options.optBoolean("screenshots", true)) {
            entry(R.drawable.ic_download, if (story.str("mime").startsWith("video/")) "Сохранить видео" else "Сохранить фото") {
                saveStory(story)
            }
        }
        val hidden = Prefs.of(act).getBoolean("story_hide_views", false)
        entry(R.drawable.ic_shield, if (hidden) "Показывать просмотры" else "Скрыть просмотр") {
            Prefs.of(act).edit().putBoolean("story_hide_views", !hidden).apply()
            act.toast(if (hidden) "Просмотры новых историй будут отмечаться"
                else "Новые просмотры будут скрыты; уже отправленные отметки не отменяются")
        }
        if (mine) entry(R.drawable.ic_trash, "Удалить", danger = true) { confirmDelete(story) }
        popup.header(panel).show(true)
    }

    private var revisionLoading = false

    /** Reopen the original creation editor with the published overlays already populated. */
    private fun openRevisionEditor(story: JSONObject) {
        if (revisionLoading) return
        val storyId = story.str("id")
        val accountId = CoreClient.accountId
        val mime = story.str("mime")
        val video = mime.startsWith("video/")
        revisionLoading = true
        act.toast("Открываем редактор…")
        CoreClient.call("open_story", JSONObject().put("story_id", storyId)) { value, error ->
            if (!active || CoreClient.accountId != accountId) {
                revisionLoading = false
                return@call
            }
            if (error != null) {
                revisionLoading = false
                act.toast(error.message)
                return@call
            }
            val source = File((value as JSONObject).str("path"))
            Thread {
                var temporary: File? = null
                val prepared = runCatching {
                    require(mime.startsWith("image/") || video) { "История не содержит фото или видео" }
                    check(source.isFile) { "Не удалось открыть медиа истории" }
                    val file = File.createTempFile("story-revision-", if (video) ".mp4" else ".jpg", act.cacheDir)
                    temporary = file
                    source.inputStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var length = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                length += count
                                check(length <= STORY_LIMIT) { "История больше 50 МБ" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(file.length() > 0) { "Файл истории пуст" }
                    val bitmap = if (!video) checkNotNull(Photos.decode(file.path, 1440)) {
                        "Не удалось прочитать фото"
                    } else null
                    val duration = if (video) checkNotNull(Videos.info(file.path)) {
                        "Не удалось открыть видео"
                    }.durationMs.toInt() else 0
                    check(!video || duration in 1..VIDEO_LIMIT_MS) { "Неподдерживаемая длительность видео" }
                    StoryInput(file, if (video) "story.mp4" else "story.jpg", mime, bitmap, duration)
                }
                act.runOnUiThread {
                    revisionLoading = false
                    val editorInput = prepared.getOrNull()
                    val stillCurrent = active && CoreClient.accountId == accountId
                        && stories.getOrNull(index)?.str("id") == storyId
                    if (!stillCurrent || editorInput == null) {
                        temporary?.delete()
                        editorInput?.photo?.recycle()
                        if (stillCurrent) act.toast(prepared.exceptionOrNull()?.message ?: "Не удалось открыть редактор")
                    } else act.openPage(StoryEditor(act, editorInput, story))
                }
            }.start()
        }
    }

    private fun manageStory(story: JSONObject, changes: JSONObject, onDone: () -> Unit = {}) {
        changes.put("story_id", story.str("id"))
        CoreClient.call("manage_story", changes) { result, error ->
            if (error != null) { act.toast(error.message); return@call }
            (result as? JSONObject)?.optJSONObject("options")?.let { story.put("options", it) }
            if (changes.has("caption")) story.put("caption", changes.str("caption"))
            act.toast("История обновлена")
            onDone()
        }
    }

    private fun editStoryCaption(story: JSONObject) {
        val sheet = Sheet(act, "Подпись истории")
        val field = FloatingField(act, "Подпись до 240 символов")
        field.setText(story.str("caption"))
        field.edit.filters = arrayOf(InputFilter.LengthFilter(240))
        sheet.add(field)
        sheet.add(PillButton(act, "Сохранить").apply {
            setOnClickListener {
                isEnabled = false
                manageStory(story, JSONObject().put("caption", field.text.trim())) {
                    sheet.dismiss()
                    caption.text = story.str("caption")
                    caption.visibility = if (caption.text.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        })
        sheet.show()
    }

    private fun editAlbum(story: JSONObject) {
        val sheet = Sheet(act, "Альбом в профиле")
        val field = FloatingField(act, "Название альбома")
        field.setText(story.optJSONObject("options")?.str("album").orEmpty())
        field.edit.filters = arrayOf(InputFilter.LengthFilter(48))
        sheet.add(field)
        sheet.add(PillButton(act, "Сохранить").apply {
            setOnClickListener {
                isEnabled = false
                manageStory(story, JSONObject().put("album", field.text.trim())) { sheet.dismiss() }
            }
        })
        sheet.show()
    }

    private fun confirmDelete(story: JSONObject) {
        val sheet = Sheet(act, "Удалить историю?")
        sheet.add(act.label("История будет удалена у получателей и из профиля.", 14f, 400))
        sheet.add(PillButton(act, "Удалить", primary = false).apply {
            setOnClickListener {
                isEnabled = false
                manageStory(story, JSONObject().put("delete", true)) {
                    sheet.dismiss()
                    act.closePage()
                }
            }
        })
        sheet.show()
    }

    private fun showAudience(story: JSONObject) {
        CoreClient.call("story_audience", JSONObject().put("story_id", story.str("id"))) { result, error ->
            if (error != null) return@call act.toast(error.message)
            val names = (result as? JSONArray)?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
            }.orEmpty()
            val sheet = Sheet(act, "Аудитория истории")
            sheet.add(act.label("Просмотры: " + story.optInt("views") + " · Реакции: " + story.optInt("reactions"), 15f, 600))
            sheet.add(act.label(if (names.isEmpty()) "Нет доступных собеседников" else names.joinToString("\n"),
                14f, 400))
            sheet.show()
        }
    }

    private fun react(story: JSONObject) {
        pauseForMenu()
        Emoji.sheet(act, "Реакция на историю") { emoji ->
            CoreClient.call("story_feedback", JSONObject().put("story_id", story.str("id"))
                .put("reaction", emoji).put("viewed", !Prefs.of(act).getBoolean("story_hide_views", false))) { _, error ->
                if (error != null) act.toast(error.message)
                else {
                    story.put("my_reaction", emoji)
                    act.toast("Реакция отправлена")
                }
            }
        }
    }

    private fun comment(story: JSONObject) {
        pauseForMenu()
        val sheet = Sheet(act, "Комментарий к истории")
        val field = FloatingField(act, "Ваш комментарий")
        field.edit.filters = arrayOf(InputFilter.LengthFilter(500))
        sheet.add(field)
        sheet.add(PillButton(act, "Отправить").apply {
            setOnClickListener {
                val message = field.text.trim()
                if (message.isEmpty()) return@setOnClickListener
                isEnabled = false
                CoreClient.call("comment_story", JSONObject().put("story_id", story.str("id"))
                    .put("text", message)) { _, error ->
                    isEnabled = true
                    if (error != null) act.toast(error.message)
                    else { sheet.dismiss(); act.toast("Комментарий отправлен") }
                }
            }
        })
        sheet.show()
    }

    private fun saveStory(story: JSONObject) {
        if (story.optJSONObject("options")?.optBoolean("screenshots", true) == false) return
        val mime = story.str("mime").ifEmpty { "image/jpeg" }
        val extension = if (mime.startsWith("video/")) ".mp4" else ".jpg"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime).putExtra(Intent.EXTRA_TITLE, "NeoChat-story-" + story.optLong("sent_at") + extension)
        act.startForResult(intent) { code, data ->
            val destination = data?.data
            if (code != Activity.RESULT_OK || destination == null) return@startForResult
            CoreClient.call("open_story", JSONObject().put("story_id", story.str("id"))) { result, error ->
                if (error != null) return@call act.toast(error.message)
                val src = File((result as JSONObject).str("path"))
                Thread {
                    val saved = runCatching {
                        act.contentResolver.openOutputStream(destination)?.use { output ->
                            src.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        } ?: throw java.io.IOException("Не удалось открыть файл")
                    }.isSuccess
                    act.runOnUiThread { act.toast(if (saved) "История сохранена" else "Не удалось сохранить историю") }
                }.start()
            }
        }
    }

    override fun onShown() { super.onShown(); active = true; showStory() }
    override fun onHidden() {
        active = false
        ++generation
        stage.removeCallbacks(ticker)
        stage.stop()
        Prefs.applySecure(act)
    }

    private fun next(offset: Int) {
        if (index + offset < 0) return
        if (index + offset >= stories.size) return act.closePage()
        index += offset
        showStory()
    }

    private fun showStory() {
        val request = ++generation
        val story = stories[index]
        if (story.optJSONObject("options")?.optBoolean("screenshots", true) == false)
            act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else Prefs.applySecure(act)
        title.text = story.str("author")
        quickActions.visibility = if (story.optBoolean("mine")) View.GONE else View.VISIBLE
        caption.visibility = View.VISIBLE
        caption.text = "Загрузка…"
        stage.style = story.optJSONObject("style") ?: JSONObject()
        stage.photo(null)
        stage.removeCallbacks(ticker)
        elapsed = 0
        duration = 6_000
        progress.progress = 0
        playing = false
        play.setIcon(R.drawable.ic_pause, "Пауза")
        play.isEnabled = false
        last = SystemClock.uptimeMillis()
        stage.post(ticker)
        CoreClient.call("open_story", JSONObject().put("story_id", story.str("id"))) { result, error ->
            if (!active || request != generation) return@call
            if (error != null) { caption.text = error.message; return@call }
            val file = (result as JSONObject).str("path")
            fun ready() {
                caption.text = story.str("caption")
                caption.visibility = if (caption.text.isEmpty()) View.GONE else View.VISIBLE
                last = SystemClock.uptimeMillis()
                playing = true
                play.isEnabled = true
                if (!story.optBoolean("mine") && !Prefs.of(act).getBoolean("story_hide_views", false))
                    CoreClient.call("story_feedback",
                        JSONObject().put("story_id", story.str("id")).put("viewed", true)) { _, _ -> }
            }
            if (story.str("mime").startsWith("video/")) {
                stage.movie(file, { player ->
                    duration = player.duration.toLong().coerceIn(1L, VIDEO_LIMIT_MS.toLong())
                    val gain = if (stage.style.optBoolean("muted")) 0f else 1f
                    player.setVolume(gain, gain)
                    ready()
                    stage.video?.start()
                }, { if (active && request == generation) next(1) }, { caption.text = "Видео недоступно" })
            } else Thread {
                val bitmap = Photos.decode(file, 1440)
                act.runOnUiThread {
                    if (!active || request != generation) return@runOnUiThread
                    if (bitmap == null) { caption.text = "Фото недоступно"; return@runOnUiThread }
                    stage.photo(bitmap)
                    ready()
                }
            }.start()
        }
    }
}
