package app.neochat.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Real Android touch dispatch, without a network account or additional test libraries. */
class InteractionInstrumentation : Instrumentation() {
    private var archive = false
    private var fixes = false
    private var push = false
    private var art = false
    private var chatMotion = false
    private var storyClip = ""
    private var storyCanvasOnly = false
    private var profileServer = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); archive = arguments?.getString("archive") == "true"; fixes = arguments?.getString("fixes") == "true"; push = arguments?.getString("push") == "true"; art = arguments?.getString("art") == "true"; chatMotion = arguments?.getString("chat_motion") == "true"; storyClip = arguments?.getString("story_clip").orEmpty(); storyCanvasOnly = arguments?.getString("story_canvas_only") == "true"; profileServer=arguments?.getString("profile_server") ?: ""; start() }

    override fun onStart() {
        val result = Bundle()
        try {
            if (storyCanvasOnly) {
                runOnMainSync { checkStoryCanvas(); checkStoryTransforms() }
                result.putString("stream", "OK: story canvas rendering, sticker/text drag, resize and rotation\n")
                finish(-1, result)
                return
            }
            runOnMainSync {
                val ctx = targetContext
                val parent = FrameLayout(ctx)
                var replies = 0
                val row = SwipeToReplyLayout(ctx, { true }, { replies++ })
                val hitbox = FrameLayout(ctx).apply { isClickable = true }
                hitbox.addView(View(ctx).apply { isClickable = true }, FrameLayout.LayoutParams(ctx.dp(80), ctx.dp(48)))
                row.attach(hitbox)
                parent.addView(row, FrameLayout.LayoutParams(ctx.dp(360), ctx.dp(96)))
                parent.measure(View.MeasureSpec.makeMeasureSpec(ctx.dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ctx.dp(96), View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, ctx.dp(360), ctx.dp(96))
                val oldReduced = Theme.forceReduced
                Theme.forceReduced = true
                fun gesture(x: Int, dx: Int, dy: Int = 0, cancel: Boolean = false) {
                    val start = SystemClock.uptimeMillis()
                    fun send(action: Int, px: Int, py: Int, delay: Long) {
                        val ev = MotionEvent.obtain(start, start + delay, action, ctx.dpf(px), ctx.dpf(py), 0)
                        row.dispatchTouchEvent(ev)
                        ev.recycle()
                    }
                    send(MotionEvent.ACTION_DOWN, x, 48, 0)
                    send(MotionEvent.ACTION_MOVE, x + dx / 2, 48 + dy / 2, 30)
                    send(MotionEvent.ACTION_MOVE, x + dx, 48 + dy, 60)
                    send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x + dx, 48 + dy, 90)
                }
                try {
                    // The bubble ends at x=80; the gesture starts in empty space.
                    gesture(220, 90)
                    check(replies == 1) { "Reply did not start outside the bubble" }
                    check(hitbox.translationX == 0f)
                    gesture(220, -90)
                    check(replies == 2) { "Opposite swipe did not reply" }
                    gesture(220, 12, 70)
                    check(replies == 2) { "Vertical scroll triggered a reply" }
                    gesture(8, 90)
                    check(replies == 2) { "System back edge triggered a reply" }
                    gesture(220, 90, cancel = true)
                    check(replies == 2) { "Cancelled gesture triggered a reply" }
                } finally { Theme.forceReduced = oldReduced; row.reset() }
            }
            checkUpdateProvider()
            if (archive) archiveInteraction()
            if (push) pushInteraction()
            if (fixes) screenAndFileRegressions()
            if (art) qrArtChecks()
            if (chatMotion) chatNavigationMotion()
            if (storyClip.isNotEmpty()) {
                checkStoryClip()
                runOnMainSync { checkStoryCanvas() }
                checkStoryEditor()
            }
            result.putString("stream", "OK: reply gestures; archive follows finger, partial/cancelled pull, scroll hiding, navigation cleanup, inline undo; tab bar lens lifts, follows and lands" +
                (if (push) "; FCM job and socket fallback" else "") + (if (art) "; QR patterns, inks and the photo hole" else "") + (if (profileServer.isNotEmpty()) "; verified email enrollment and native email form" else "") +
                (if (chatMotion) "; stationary chat backdrop, both system back edges, cancellation, archive, reduced motion and settings edges" else "") +
                (if (storyClip.isNotEmpty()) "; story markup modes, on-canvas text and formatting, audience modes and exclusions, draft retention, trim and audio removal" else "") + "\n")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.message}\n")
            finish(0, result)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun checkStoryClip() {
        val ctx = targetContext
        val source = File.createTempFile("story-test-", ".mp4", ctx.cacheDir)
        val output = File.createTempFile("story-result-", ".mp4", ctx.cacheDir)
        try {
            source.writeBytes(android.util.Base64.decode(storyClip, android.util.Base64.DEFAULT))
            val ready = CountDownLatch(1)
            var success = false
            runOnMainSync {
                exportStoryClip(ctx, source, output, 400, 1_400, true) {
                    success = it
                    ready.countDown()
                }
            }
            check(ready.await(30, TimeUnit.SECONDS) && success) { "Story clip export failed" }
            check((Videos.info(output.path)?.durationMs ?: 0L) in 850L..1_150L) { "Story clip duration did not match the trim" }
            val media = android.media.MediaExtractor()
            try {
                media.setDataSource(output.path)
                check((0 until media.trackCount).none {
                    media.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }) { "Muted story still contains audio" }
            } finally {
                media.release()
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun checkStoryCanvas() {
        val source = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(70, 70, 70))
        val stage = StoryStage(targetContext)
        stage.style = JSONObject("""{"brightness":20,"color":16777215,"lines":[[[500,340],[500,420]]]}""")
        stage.photo(source)
        val painted = render(stage, 180, 320)
        try {
            check(Color.red(painted.getPixel(90, 80)) in 100..110) { "Story photo tone did not change" }
            check(Color.red(painted.getPixel(90, 120)) > 230) { "Story drawing is missing from the selected position" }
        } finally {
            painted.recycle()
            source.recycle()
        }
    }

    private fun checkStoryTransforms() {
        val sticker = JSONObject().put("emoji", "🔥").put("x", 250).put("y", 250)
            .put("scale", 1000).put("angle", 0)
        val stage = StoryStage(targetContext).apply {
            edit = true
            style = JSONObject().put("color", 0xffffff).put("text", "Тест")
                .put("text_x", 500).put("text_y", 500)
                .put("text_scale", 1000).put("text_angle", 0)
                .put("stickers", org.json.JSONArray().put(sticker))
        }
        render(stage, 360, 640).recycle()
        fun drag(x1: Float, y1: Float, x2: Float, y2: Float) {
            val start = SystemClock.uptimeMillis()
            for ((index, part) in listOf(
                Triple(MotionEvent.ACTION_DOWN, x1, y1),
                Triple(MotionEvent.ACTION_MOVE, x2, y2),
                Triple(MotionEvent.ACTION_UP, x2, y2)
            ).withIndex()) {
                val event = MotionEvent.obtain(start, start + index * 40L,
                    part.first, part.second, part.third, 0)
                try { check(stage.dispatchTouchEvent(event)) { "Story touch was not handled: $index" } }
                finally { event.recycle() }
            }
        }
        stage.selectSticker(0)
        drag(90f, 160f, 125f, 180f)
        check(sticker.optInt("x") > 250 && sticker.optInt("y") > 250) {
            "Sticker did not move"
        }
        // Hit the actual painted handle: its padding is expressed in density-independent pixels.
        val cx = sticker.optInt("x") * 360f / 1000f
        val cy = sticker.optInt("y") * 640f / 1000f
        val handle = 360f * 0.12f * 0.75f + targetContext.dpf(9)
        drag(cx + handle, cy + handle, cx + handle * 1.15f, cy + handle * 1.9f)
        check(sticker.optInt("scale", 1000) > 1300) { "Sticker did not resize: $sticker" }
        check(kotlin.math.abs(sticker.optInt("angle")) > 10) { "Sticker did not rotate: $sticker" }
        stage.selectText()
        drag(180f, 320f, 160f, 350f)
        check(stage.style.optInt("text_x") < 500 && stage.style.optInt("text_y") > 500) {
            "Text did not move"
        }
        render(stage, 360, 640).recycle()
        // The rendered text block is 84% of the 360px frame, with a right/lower handle.
        val tx = stage.style.optInt("text_x") * 360f / 1000f
        val ty = stage.style.optInt("text_y") * 640f / 1000f
        drag(tx + 167f, ty + 45f, tx + 167f, ty + 110f)
        check(stage.style.optInt("text_scale", 1000) > 1000) { "Text did not resize: ${stage.style}" }
        check(kotlin.math.abs(stage.style.optInt("text_angle")) > 5) { "Text did not rotate: ${stage.style}" }
    }

    @androidx.media3.common.util.UnstableApi
    private fun checkStoryEditor() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("test_server", "http://127.0.0.1:1")
        }) as MainActivity
        waitForIdleSync()
        val source = File.createTempFile("story-editor-", ".mp4", activity.cacheDir)
        source.writeBytes(android.util.Base64.decode(storyClip, android.util.Base64.DEFAULT))
        val duration = checkNotNull(Videos.info(source.path)).durationMs.toInt()
        val reduced = Theme.forceReduced
        lateinit var editor: StoryEditor
        fun View.descendants(): Sequence<View> = sequence {
            yield(this@descendants)
            if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
        }
        fun shown() = editor.view.descendants().filter { it.isShown }
        fun click(text: String) {
            val control = checkNotNull(shown().firstOrNull {
                it.isClickable && (it.contentDescription == text || (it as? android.widget.TextView)?.text?.toString() == text)
            }) { "Missing story control: $text" }
            check(control.performClick())
        }
        try {
            runOnMainSync {
                Theme.forceReduced = true
                activity.showMain()
                editor = StoryEditor(activity, StoryInput(source, "test.mp4", "video/mp4", null, duration))
                activity.openPage(editor)
            }
            waitForIdleSync()
            runOnMainSync {
                val stage = shown().filterIsInstance<StoryStage>().single()
                val strip = shown().filterIsInstance<StoryTimeline>().single()
                check(stage.height > editor.view.height / 2) { "Tools occupy more space than the media" }
                check(shown().none { it is EditText }) { "Text input takes up space in the main editor" }
                fun drag(from: Float, to: Float) {
                    val start = SystemClock.uptimeMillis()
                    for ((index, step) in listOf(MotionEvent.ACTION_DOWN to from, MotionEvent.ACTION_MOVE to to,
                        MotionEvent.ACTION_UP to to).withIndex()) {
                        val ev = MotionEvent.obtain(start, start + index * 30, step.first, step.second, strip.height / 2f, 0)
                        try { check(strip.dispatchTouchEvent(ev)) } finally { ev.recycle() }
                    }
                }
                drag(activity.dpf(12), strip.width * 0.2f)
                drag(strip.width - activity.dpf(12), strip.width * 0.78f)
                check(strip.first > 0 && strip.last < duration && strip.last - strip.first >= 500) {
                    "Both trim handles must change the selected interval"
                }
                val selection = strip.first to strip.last
                click("Добавить подпись")
                shown().filterIsInstance<EditText>().single().setText("Вечерний кадр")
                click("Готово")
                click("Рисовать")
                check(stage.drawing)
                check(shown().any { it.contentDescription == "Цвет кисти" })
                click("Маркер")
                check(stage.brushKind == 2)
                click("Стикеры")
                check(!stage.drawing)
                click("🔥")
                check(stage.style.getJSONArray("stickers").length() == 1)
                click("Рисунок")
                check(stage.drawing)
                click("Отмена")
                check(stage.style.optJSONArray("stickers") == null)
                click("Текст на кадре")
                val field = shown().filterIsInstance<EditText>().single()
                check(stage.descendants().any { it === field }) { "Story text must be edited on the media" }
                field.setText("Черновик надписи")
                check(stage.style.str("text") == "Черновик надписи")
                click("Выравнивание текста: По центру")
                val leftAlign = activity.overlay.descendants().filterIsInstance<android.widget.TextView>()
                    .firstOrNull { it.text.toString() == "По левому краю" }
                checkNotNull(leftAlign) { "Alignment choices were not displayed" }.performClick()
                check(stage.style.optInt("text_align") == 1)
                click("Без фона")
                check(stage.style.optInt("text_background") == 1)
                check(editor.onBack())
                check(stage.style.str("text").isEmpty())
                check(!stage.style.has("text_align"))
                click("Далее")
                check(shown().none { it is StoryStage })
                check(shown().none { it is EditText }) { "Recipient picker opened before choosing an audience" }
                val modes = shown().filterIsInstance<android.widget.RadioButton>().toList()
                check(modes.map { it.contentDescription } == StoryVisibility.entries.map { it.title })
                check(modes.single { it.isChecked }.contentDescription == "Все")
                click("Мои контакты")
                check(modes.single { it.isChecked }.contentDescription == "Мои контакты")
                check(shown().any { it.contentDescription == "Опубликовать" && !it.isEnabled })
                click("Назад")
                check(stage.isShown && strip.isShown)
                check(strip.first to strip.last == selection)
                check(shown().any { (it as? android.widget.TextView)?.text == "Вечерний кадр" })
            }
            val contacts = setOf("alice", "bob")
            val exclusions = setOf("bob")
            check(StoryVisibility.ALL.allows("carol", contacts, exclusions))
            check(!StoryVisibility.ALL.allows("bob", contacts, exclusions))
            check(StoryVisibility.CONTACTS.allows("alice", contacts, exclusions))
            check(!StoryVisibility.CONTACTS.allows("carol", contacts, exclusions))
            check(!StoryVisibility.CONTACTS.allows("bob", contacts, exclusions))
            for (mode in listOf(StoryVisibility.FRIENDS, StoryVisibility.SELECTED)) {
                check(mode.allows("bob", contacts, exclusions))
                check(!mode.allows("alice", contacts, exclusions))
            }
        } finally {
            runOnMainSync {
                activity.closePage()
                Theme.forceReduced = reduced
                activity.finish()
            }
            source.delete()
        }
    }

    private fun checkUpdateProvider() {
        val ctx = targetContext
        val slot = File(ctx.filesDir, "core/accounts/${java.util.UUID.randomUUID()}")
        val apk = File(slot, "updates/test.apk")
        val internalFile = File(slot, "private.db")
        try {
            check(apk.parentFile!!.mkdirs())
            apk.writeText("installer bytes")
            internalFile.writeText("private")
            val uri = app.neochat.FilesProvider.uriFor(ctx, apk)
            check(ctx.contentResolver.getType(uri) == "application/vnd.android.package-archive")
            check(ctx.contentResolver.openInputStream(uri)?.use { it.reader().readText() } == "installer bytes")
            check(runCatching { app.neochat.FilesProvider.uriFor(ctx, internalFile) }.isFailure)
        } finally {
            apk.delete()
            apk.parentFile?.delete()
            internalFile.delete()
            slot.delete()
        }
    }

    private fun chatNavigationMotion() {
        check(android.os.Build.VERSION.SDK_INT >= 34) { "Chat motion integration requires Android 14+" }
        uiAutomation.grantRuntimePermission("app.neochat", "android.permission.POST_NOTIFICATIONS")
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("test_server", "http://127.0.0.1:1")
        }) as MainActivity
        waitForIdleSync()
        SystemClock.sleep(300)
        val oldReduced = Theme.forceReduced
        val oldMode = Theme.mode
        val oldAppearance = Theme.appearance
        val cls = MainActivity::class.java
        fun field(name: String) = cls.getDeclaredField(name).apply { isAccessible = true }
        val show = cls.getDeclaredMethod("show", Screen::class.java, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        lateinit var content: FrameLayout
        lateinit var chat: ChatScreen
        lateinit var list: Screen
        lateinit var qrPanel: View
        fun topColors(vararg xs: Int): IntArray {
            val frameReady = CountDownLatch(1)
            runOnMainSync {
                content.viewTreeObserver.registerFrameCommitCallback(frameReady::countDown)
                content.invalidate()
            }
            check(frameReady.await(3, TimeUnit.SECONDS)) { "Navigation frame did not render" }
            SystemClock.sleep(50)
            val screenshot = checkNotNull(uiAutomation.takeScreenshot())
            val pixels = if (screenshot.config == Bitmap.Config.HARDWARE) {
                screenshot.copy(Bitmap.Config.ARGB_8888, false)
            } else screenshot
            return try {
                IntArray(xs.size) { pixels.getPixel(xs[it], activity.dp(4)) }
            } finally {
                if (pixels !== screenshot) pixels.recycle()
                screenshot.recycle()
            }
        }
        fun colorDifference(left: Int, right: Int): Int =
            maxOf(abs(Color.red(left) - Color.red(right)),
                abs(Color.green(left) - Color.green(right)), abs(Color.blue(left) - Color.blue(right)))
        fun open(fromArchive: Boolean = false) {
            var animation: android.animation.ValueAnimator? = null
            var seam = 0
            val sampleX = content.width * 3 / 4
            val homeColor = if (fromArchive || Theme.reducedMotion) null else topColors(sampleX).single()
            runOnMainSync {
                if (fromArchive) activity.openArchive()
                list = field(if (fromArchive) "archive" else "home").get(activity) as Screen
                chat = ChatScreen(activity, "motion-fixture", JSONObject()
                    .put("title", "Motion test").put("kind", "group").put("in_archive", fromArchive))
                field("chat").set(activity, chat)
                show.invoke(activity, chat, activity.dpf(22), true)
                animation = field("slideAnim").get(activity) as? android.animation.ValueAnimator
                if (Theme.reducedMotion) check(animation == null) else {
                    val motion = checkNotNull(animation)
                    motion.pause()
                    motion.currentPlayTime = 80
                    check(chat.view.alpha == 1f && chat.view.translationX > 0f && chat.view.translationX < content.width)
                    check(kotlin.math.abs(list.view.translationX + content.width - chat.view.translationX) < 1f)
                    check(chat.pageBackground.visibility == View.INVISIBLE && list.pageBackground?.visibility == View.INVISIBLE)
                    check(content.childCount == if (fromArchive) 3 else 2) { "Chat entry added temporary layers" }
                    seam = chat.view.translationX.toInt()
                }
            }
            if (seam > 0) {
                val (left, right, midpointColor) = topColors(seam - 2, seam + 2, sampleX)
                val delta = colorDifference(left, right)
                check(delta <= 4) { "Visible edge in the chat entry backdrop: channel difference $delta" }
                if (homeColor != null) {
                    val tintDifference = colorDifference(homeColor, midpointColor)
                    check(tintDifference <= 4) { "Top tint moved during chat entry: channel difference $tintDifference" }
                }
            }
            runOnMainSync {
                animation?.end()
                check(chat.view.alpha == 1f && list.view.visibility == View.INVISIBLE)
                check(chat.view.translationX == 0f && list.view.translationX == 0f)
                check(chat.pageBackground.visibility == View.VISIBLE && list.pageBackground?.visibility == View.VISIBLE)
            }
            waitForIdleSync()
            if (homeColor != null) {
                check(colorDifference(homeColor, topColors(sampleX).single()) <= 4) {
                    "Top tint changed between the list and the open chat"
                }
            }
        }
        fun backSwipe(direction: Int, cancel: Boolean) {
            val start = SystemClock.uptimeMillis()
            val bounds = activity.window.decorView
            val x = if (direction > 0) 2f else bounds.width - 2f
            val y = bounds.height * 0.48f
            fun send(action: Int, offset: Float) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + direction * offset, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {
                    check(uiAutomation.injectInputEvent(event, false)) { "System rejected motion event $action" }
                } finally {
                    event.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN, 0f)
            val moveCount = if (cancel) 7 else 12
            for (i in 1..moveCount) {
                SystemClock.sleep(18)
                send(MotionEvent.ACTION_MOVE, activity.dpf(12 * i))
            }
            SystemClock.sleep(100)
            var followedGesture = false
            runOnMainSync {
                val v = chat.view
                if (Theme.reducedMotion) {
                    followedGesture = v.translationX == 0f && v.translationY == 0f &&
                        v.scaleX == 1f && v.scaleY == 1f && !v.clipToOutline
                } else {
                    val sideGap = v.width * (1f - v.scaleX) / 2f
                    val verticalGap = v.height * (1f - v.scaleY) / 2f
                    val sideways = v.translationX * direction
                    followedGesture = sideways > 0f && sideways < sideGap &&
                        v.translationY > 0f && v.translationY < verticalGap &&
                        v.scaleX == v.scaleY && v.rotation == 0f && v.alpha == 1f &&
                        v.clipToOutline && list.view.visibility == View.VISIBLE
                }
            }
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, activity.dpf(12 * moveCount))
            check(followedGesture) { "Chat motion did not match the system gesture from direction $direction" }
            SystemClock.sleep(360)
            waitForIdleSync()
            runOnMainSync {
                check(field("current").get(activity) === if (cancel) chat else list) { "Incorrect back destination" }
                check(chat.view.translationX == 0f && chat.view.translationY == 0f && chat.view.alpha == 1f)
                check(chat.view.scaleX == 1f && chat.view.scaleY == 1f && !chat.view.clipToOutline)
                check(list.view.visibility == if (cancel) View.INVISIBLE else View.VISIBLE)
                check(if (cancel) chat.view.parent === content else chat.view.parent == null)
            }
        }
        try {
            runOnMainSync {
                Theme.forceReduced = true
                Theme.setMode(activity, Theme.Mode.DARK)
                Theme.setAppearance(activity, "ocean")
                activity.showMain()
                Theme.forceReduced = false
                content = field("content").get(activity) as FrameLayout
            }
            open()
            backSwipe(direction = 1, cancel = true)
            backSwipe(direction = 1, cancel = false)
            open(fromArchive = true)
            backSwipe(direction = -1, cancel = false)
            runOnMainSync { activity.closeArchive() }
            runOnMainSync { Theme.forceReduced = true }
            open()
            backSwipe(direction = -1, cancel = false)
            open()
            runOnMainSync {
                activity.closeChat()
                check(field("current").get(activity) === list && chat.view.parent == null)
                val profile = HomeScreen::class.java.getDeclaredField("settingsPage")
                    .apply { isAccessible = true }.get(list)
                PersonProfilePage::class.java.getDeclaredMethod("openSettings")
                    .apply { isAccessible = true }.invoke(profile)
                val settings = field("current").get(activity) as Screen
                check(!(settings.view.background as GlassDrawable).rim) { "The settings window draws a rim" }
                val customization = CustomizationPage(activity)
                activity.openPage(customization)
                check(!(customization.view.background as GlassDrawable).rim) { "The customization window draws a rim" }
                Qr.showMine(activity)
                qrPanel = activity.overlay.getChildAt(activity.overlay.childCount - 1)
            }
            waitForIdleSync()
            runOnMainSync {
                val buttons = listOf("Оформить", "Поделиться", "Сканировать").map { title ->
                    val found = arrayListOf<View>()
                    qrPanel.findViewsWithText(found, title, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    found.filterIsInstance<PillButton>().single()
                }
                val row = buttons.first().parent as View
                row.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(264), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                check(buttons.all {
                    it.width == buttons.first().width && it.height == activity.dp(48) &&
                        it.label.textSize == buttons.first().label.textSize
                }) { "QR actions have different dimensions or text sizes" }
                for (button in buttons) {
                    check(button.label.lineCount == 1 && button.label.layout.getEllipsisCount(0) == 0) {
                        "QR action did not fit on one line: ${button.label.text}"
                    }
                }
                activity.onBackPressed()
                QrStyles.show(activity, demoRows(), "fixture", "Тест", "fixture", "", QrStyle()) {}
                qrPanel = activity.overlay.getChildAt(activity.overlay.childCount - 1)
                val tiles = QrPattern.entries.map { pattern ->
                    val found = arrayListOf<View>()
                    qrPanel.findViewsWithText(found, pattern.title, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    found.filterIsInstance<android.widget.LinearLayout>().single()
                }
                val patterns = tiles.first().parent as View
                patterns.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(264), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                patterns.layout(0, 0, patterns.measuredWidth, patterns.measuredHeight)
                for (tile in tiles) {
                    val caption = tile.getChildAt(1) as android.widget.TextView
                    val text = caption.layout
                    check(text.lineCount == 1 && text.getEllipsisCount(0) == 0) { "QR pattern caption did not fit: ${caption.text}" }
                    check(abs(text.getLineLeft(0) + text.getLineRight(0) - caption.width) <= 2f) {
                        "QR pattern caption is off-center: ${caption.text}"
                    }
                }
                activity.onBackPressed()
            }
        } finally {
            runOnMainSync {
                Theme.forceReduced = true
                Theme.setMode(activity, oldMode)
                Theme.setAppearance(activity, oldAppearance)
                Theme.forceReduced = oldReduced
                activity.finish()
            }
        }
    }

    /**
     * Draws every pattern, ink and the photo hole of a styled code into `qr/` of the app's external
     * files, and checks what the picture is made of: modules are laid down on white paper, the
     * patterns differ from one another, and the square left for the photo really holds nothing.
     */
    private fun qrArtChecks() {
        val ctx = targetContext
        val out = File(ctx.getExternalFilesDir(null), "qr").apply { mkdirs() }
        val oldReduced = Theme.forceReduced
        Theme.forceReduced = true
        try {
            val rows = demoRows(25)
            fun card(style: QrStyle = QrStyle(), code: List<String> = rows): QrCard {
                val c = QrCard(ctx)
                c.show(code, "andrey", "Андрей", "u1", "", style)
                return c
            }
            val cover = HashMap<String, Double>()
            for (p in QrPattern.values()) {
                val c = card(QrStyle(pattern = p))
                save(render(c, 340, 340), File(out, "pattern-${p.id}.png"))
                cover[p.id] = ink(render(c.qr, 320, 320))
            }
            check(cover.values.all { it > 0.05 }) { "A pattern drew almost nothing: $cover" }
            check(cover.values.toSet().size == cover.size) { "Patterns drew the same picture: $cover" }
            for (i in QrInk.ALL) {
                save(render(card(QrStyle(ink = i)), 340, 340), File(out, "ink-${i.id}.png"))
            }
            // A code is read from white paper, so no ink may be pale — in any palette.
            for (p in listOf(Palette.DARK, Palette.LIGHT)) {
                val cs = QrInk.THEME.colors(p)
                check(cs.all { lightness(it) < 0.6 }) { "The theme ink is too pale to scan in ${if (p.dark) "dark" else "light"}: ${cs.map { Integer.toHexString(it) }}" }
            }
            val plain = ink(render(card().qr, 320, 320))
            val withPhoto = card(QrStyle(avatar = true))
            save(render(withPhoto, 340, 340), File(out, "photo.png"))
            val hole = ink(render(withPhoto.qr, 320, 320))
            check(hole < plain - 0.01) { "The photo left no room in the code: $plain then $hole" }
            val style = QrStyle(QrPattern.SQUARES, QrInk.custom(0xFFFFDBF7.toInt(), Color.WHITE), true, listOf("✨", "🌙"))
            val restored = QrStyle.fromJson(style.toJson())
            check(restored.emoji == style.emoji && restored.ink.from == style.ink.from && restored.avatar)
            val link = "neochat://u/qr_fixture?key=abcdefghijklmnopqrstuvwxyz123456"
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to "Q"))
            val moduleRows = List(matrix.height) { y -> (0 until matrix.width).joinToString("") { x -> if (matrix[x, y]) "1" else "0" } }
            val image = render(card(restored, moduleRows), ctx.dp(320), ctx.dp(368))
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val source = com.google.zxing.RGBLuminanceSource(image.width, image.height, pixels)
            val decoded = com.google.zxing.qrcode.QRCodeReader().decode(
                com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
            check(decoded.text == link) { "The styled QR card could not be decoded" }
            image.recycle()
            save(render(BottomNav(ctx, listOf("Все", "Личные", "Группы").map { 0 to it }, textOnly = true), ctx.dp(344), ctx.dp(64)), File(out, "filters.png"))
        } finally {
            Theme.forceReduced = oldReduced
        }
    }

    /** Measures, lays out and draws a view that is not on the screen. */
    private fun render(v: View, w: Int, h: Int): Bitmap {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun save(b: Bitmap, f: File) = f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** Share of the paper that carries a module. */
    private fun ink(b: Bitmap): Double {
        var dark = 0
        var seen = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            seen++
            if (lightness(b.getPixel(x, y)) < 0.72) dark++
        }
        return dark.toDouble() / seen
    }

    private fun lightness(color: Int) = (0.2126 * (color shr 16 and 0xFF) + 0.7152 * (color shr 8 and 0xFF) + 0.0722 * (color and 0xFF)) / 255.0

    private fun pushInteraction() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (profileServer.isNotEmpty()) putExtra("test_server",profileServer)
        }) as MainActivity
        waitForIdleSync()
        try {
            runOnMainSync {
                check(com.google.firebase.FirebaseApp.getApps(activity).isNotEmpty()) { "Local Firebase configuration was not loaded" }
                val prefs = Prefs.of(activity)
                val oldRealtime = prefs.all["realtime"] as? Boolean
                val oldPush = prefs.all["push_registered"] as? Boolean
                try {
                    prefs.edit().remove("realtime").remove("push_registered").commit()
                    check(app.neochat.RealtimeService.enabled(activity)) { "Socket fallback was disabled before push registration" }
                    app.neochat.RealtimeService.pushRegistered(activity)
                    check(!app.neochat.RealtimeService.enabled(activity)) { "Confirmed push kept the default foreground service" }
                    prefs.edit().putBoolean("realtime", true).commit()
                    app.neochat.RealtimeService.pushRegistered(activity)
                    check(app.neochat.RealtimeService.enabled(activity)) { "Push registration overwrote the explicit socket setting" }
                } finally {
                    prefs.edit().apply {
                        if (oldRealtime == null) remove("realtime") else putBoolean("realtime", oldRealtime)
                        if (oldPush == null) remove("push_registered") else putBoolean("push_registered", oldPush)
                    }.commit()
                }
                val service = app.neochat.PushService()
                android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
                    .apply { isAccessible = true }.invoke(service, activity)
                val scheduler = activity.getSystemService(android.app.job.JobScheduler::class.java)
                scheduler.cancel(app.neochat.SyncJob.PUSH_ID)
                service.onMessageReceived(com.google.firebase.messaging.RemoteMessage.Builder("fictional-sender").addData("type", "unrelated").build())
                check(scheduler.getPendingJob(app.neochat.SyncJob.PUSH_ID) == null) { "Unknown push started a sync" }
                val message = com.google.firebase.messaging.RemoteMessage(Bundle().apply {
                    putString("type", "sync")
                    putString("google.delivered_priority", "high")
                })
                check(message.priority == com.google.firebase.messaging.RemoteMessage.PRIORITY_HIGH)
                service.onMessageReceived(message)
                val job = scheduler.getPendingJob(app.neochat.SyncJob.PUSH_ID)
                check(job != null && job.service.className == app.neochat.SyncJob::class.java.name) { "FCM did not schedule the encrypted sync" }
                if (android.os.Build.VERSION.SDK_INT >= 31) check(job.isExpedited) { "High-priority FCM did not get an expedited job" }
                repeat(10) { service.onMessageReceived(message) }
                check(scheduler.allPendingJobs.count { it.id == app.neochat.SyncJob.PUSH_ID } == 1) { "Push burst created duplicate jobs" }
                scheduler.cancel(app.neochat.SyncJob.PUSH_ID)
            }
            if (profileServer.isNotEmpty()) {
                lateinit var form: EmailVerification
                fun field(host:Any,name:String):Any = host.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(host)!!
                runOnMainSync {
                    val onboarding=field(activity,"current") as OnboardingScreen
                    (field(onboarding,"switchMode") as View).performClick()
                    form=field(onboarding,"email") as EmailVerification
                    (field(form,"address") as FloatingField).setText("android_email_${System.currentTimeMillis()}@example.test")
                    (field(form,"send") as View).performClick()
                }
                val deadline=SystemClock.uptimeMillis()+10_000
                var completed=false
                while (!completed && SystemClock.uptimeMillis()<deadline) {
                    SystemClock.sleep(100); waitForIdleSync()
                    runOnMainSync {
                        val check=field(form,"check") as View
                        if (check.visibility==View.VISIBLE && check.isEnabled) check.performClick()
                        completed=form.verifiedTicket.isNotEmpty()
                    }
                }
                check(completed) { "Native email form did not confirm its private registration ticket" }
            }
        } finally { runOnMainSync { activity.finish() } }
    }

    private fun registrationParams(p:JSONObject):JSONObject {
        val request=JSONObject().put("server",profileServer).put("email",p.getString("username")+"@example.test")
        val (value,enrollError)=app.neochat.core.CoreClient.callBlocking("registration_email_start",request)
        check(enrollError==null) { "Test mailbox enrollment failed" }
        val ticket=(value as JSONObject).getString("ticket")
        val deadline=SystemClock.uptimeMillis()+5000
        while (SystemClock.uptimeMillis()<deadline) {
            val (state,failure)=app.neochat.core.CoreClient.callBlocking("registration_email_status",JSONObject().put("server",profileServer).put("ticket",ticket))
            check(failure==null) { "Test mailbox status failed" }
            if ((state as JSONObject).getBoolean("verified")) return p.put("email_ticket",ticket)
            SystemClock.sleep(30)
        }
        error("Test mailbox confirmation timed out")
    }

    private fun screenAndFileRegressions() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("test_server", "http://127.0.0.1:1")
        }) as MainActivity
        waitForIdleSync()
        SystemClock.sleep(300)
        fun field(host: Any, name: String): Any = host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host)!!
        try {
            runOnMainSync {
                val content = field(activity, "content") as FrameLayout
                val show = MainActivity::class.java.getDeclaredMethod("show", Screen::class.java, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                val source = object : Screen { override val view = View(activity).apply { setBackgroundColor(android.graphics.Color.RED) } }
                val target = object : Screen { override val view = FrameLayout(activity) }
                val reduced = Theme.forceReduced
                Theme.forceReduced = false
                try {
                    fun measure() {
                        content.measure(View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(content.height, View.MeasureSpec.EXACTLY))
                        content.layout(content.left, content.top, content.right, content.bottom)
                    }
                    fun pixel(): Int {
                        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                        content.draw(Canvas(bitmap))
                        val color = bitmap.getPixel(content.width - 2, content.height / 2)
                        bitmap.recycle()
                        return color
                    }
                    for (forward in listOf(true, false)) {
                        show.invoke(activity, if (forward) source else target, 0f, false)
                        show.invoke(activity, if (forward) target else source, if (forward) 22f else -22f, true)
                        measure()
                        val anim = field(activity, "slideAnim") as android.animation.ValueAnimator
                        anim.currentPlayTime = 160
                        source.view.setBackgroundColor(android.graphics.Color.RED)
                        val red = pixel()
                        source.view.setBackgroundColor(android.graphics.Color.GREEN)
                        check(red == pixel()) { "Previous screen bleeds through during navigation (forward=$forward)" }
                        anim.end()
                        check(content.childCount == 1) { "Transition left extra layers" }
                    }
                } finally { Theme.forceReduced = reduced }

                val chat = ChatScreen(activity, "file-fixture")
                val adapter = field(chat, "adapter") as RecyclerView.Adapter<RecyclerView.ViewHolder>
                val itemClass = ChatScreen::class.java.declaredClasses.single { it.simpleName == "Item" }
                val ctor = itemClass.declaredConstructors.single().apply { isAccessible = true }
                val names = listOf("name.txt", "Очень длинное имя документа для проверки сохранения расширения.txt")
                for (kind in listOf("direct", "group")) for (out in listOf(false, true)) for (name in names) {
                    (field(chat, "conv") as JSONObject).put("kind", kind)
                    val message = JSONObject().put("id", "file-fixture").put("kind", "file").put("outgoing", out)
                        .put("file", JSONObject().put("name", name).put("size", 1536).put("mime", "text/plain"))
                    adapter.javaClass.getDeclaredMethod("setItems", List::class.java).apply { isAccessible = true }
                        .invoke(adapter, listOf(ctor.newInstance(message, true, true)))
                    val holder = adapter.onCreateViewHolder(FrameLayout(activity), 1)
                    adapter.onBindViewHolder(holder, 0)
                    val row = field(holder, "row")
                    val file = field(row, "file") as View
                    val label = field(row, "fileName") as android.widget.TextView
                    val meta = field(row, "fileMeta") as android.widget.TextView
                    for (width in listOf(320, 360, 412)) {
                        val view = holder.itemView
                        view.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                        check(label.width > 0 && meta.width > 0 && meta.height > 0) { "File labels collapsed: $kind, outgoing=$out, width=$width" }
                        check(label.text.toString() == name && meta.text.startsWith(sizeText(1536)))
                        check(label.layout.getLineEnd(0) == name.length || label.lineCount > 1) { "Filename extension lost" }
                        val rect = android.graphics.Rect(0, 0, file.width, file.height)
                        (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(file, rect)
                        check(rect.left >= 0 && rect.right <= view.width) { "File card clipped at width=$width" }
                    }
                }
            }
        } finally { runOnMainSync { activity.finish() } }
    }

    private fun archiveInteraction() {
        uiAutomation.grantRuntimePermission("app.neochat", "android.permission.POST_NOTIFICATIONS")
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("test_server", "http://127.0.0.1:1")
        }) as MainActivity
        waitForIdleSync()
        SystemClock.sleep(300)
        fun field(host: Any, name: String): Any = host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host)!!
        fun pause() { SystemClock.sleep(360); waitForIdleSync() }
        try {
            lateinit var home: HomeScreen
            lateinit var list: RecyclerView
            lateinit var wrap: View
            runOnMainSync {
                activity.readinessPrompted = true
                activity.showMain()
                activity.readinessPrompted = true
            }
            pause()
            runOnMainSync {
                home = field(activity, "home") as HomeScreen
                home.javaClass.getDeclaredField("allChats").apply { isAccessible = true }.set(home, (0..29).map {
                    JSONObject().put("id", "archive-fixture-$it").put("kind", "direct").put("title", "Тестовый чат")
                })
                home.javaClass.getDeclaredField("archiveRow").apply { isAccessible = true }.set(home, JSONObject().put("count", 3))
                home.javaClass.getDeclaredMethod("applyChats", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(home, false)
                list = field(home, "chatList") as RecyclerView
                wrap = field(home, "archiveWrap") as View
            }
            pause()
            fun capture(name: String) = runOnMainSync {
                val root = activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val dir = File(activity.filesDir, "archive-shots").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val start = SystemClock.uptimeMillis()
            fun touch(action: Int, dy: Int) {
                runOnMainSync {
                    val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, activity.dpf(180), activity.dpf(90 + dy), 0)
                    list.dispatchTouchEvent(e)
                    e.recycle()
                }
                SystemClock.sleep(25)
                waitForIdleSync()
            }
            touch(MotionEvent.ACTION_DOWN, 0); touch(MotionEvent.ACTION_MOVE, 64)
            runOnMainSync { check(wrap.height in 1 until activity.dp(72)) { "Archive did not follow the finger" } }
            capture("partial-pull")
            touch(MotionEvent.ACTION_UP, 64); pause()
            runOnMainSync { check(wrap.layoutParams.height == 0 && wrap.visibility == View.GONE) { "Short pull exposed the archive" } }
            touch(MotionEvent.ACTION_DOWN, 0); touch(MotionEvent.ACTION_MOVE, 150); touch(MotionEvent.ACTION_CANCEL, 150); pause()
            runOnMainSync { check(wrap.layoutParams.height == 0 && wrap.visibility == View.GONE) { "Cancelled pull exposed the archive" } }
            touch(MotionEvent.ACTION_DOWN, 0); touch(MotionEvent.ACTION_MOVE, 150); touch(MotionEvent.ACTION_UP, 150)
            SystemClock.sleep(500); waitForIdleSync()
            runOnMainSync {
                check(wrap.height == activity.dp(72)) { "Full pull did not settle" }
                val row = field(home, "archiveEntry") as ListRow
                check(row.contentDescription == "Архив" && (row.subtitle.parent as View).visibility == View.GONE) { "Archive still exposes counters" }
            }
            capture("revealed")
            runOnMainSync { list.scrollBy(0, activity.dp(100)) }; pause()
            runOnMainSync { check(wrap.layoutParams.height == 0 && wrap.visibility == View.GONE) { "Scrolling did not hide the archive" }; activity.openArchive() }
            SystemClock.sleep(85); capture("opening")
            pause()
            runOnMainSync { activity.closeArchive() }
            SystemClock.sleep(85); capture("closing")
            pause()
            runOnMainSync {
                check((field(activity, "content") as FrameLayout).childCount == 1) { "Navigation left transition layers behind" }
                var undone = 0
                activity.toast("Чат в архиве", "Отмена") { undone++ }
                val bubble = activity.overlay.findViewWithTag<View>("toast")
                val buttons = arrayListOf<View>()
                bubble.findViewsWithText(buttons, "Отмена", View.FIND_VIEWS_WITH_TEXT)
                check(buttons.size == 1) { "Undo is not inside the archive bubble" }
                buttons.single().performClick(); buttons.single().performClick()
                check(undone == 1) { "Undo did not run exactly once" }
                activity.toast("Чат в архиве", "Отмена") {}
            }
            pause(); capture("undo")
            // The tab bar lens: a touch lifts it, a drag carries it, letting go picks the item under the finger.
            lateinit var nav: BottomNav
            runOnMainSync { activity.overlay.findViewWithTag<View>("toast")?.let { activity.overlay.removeView(it) }; nav = field(home, "nav") as BottomNav }
            val navStart = SystemClock.uptimeMillis()
            fun navTouch(action: Int, item: Float) = runOnMainSync {
                val x = nav.paddingLeft + (nav.width - nav.paddingLeft - nav.paddingRight) * item / 3f
                val e = MotionEvent.obtain(navStart, SystemClock.uptimeMillis(), action, x, nav.height / 2f, 0)
                nav.dispatchTouchEvent(e)
                e.recycle()
            }
            navTouch(MotionEvent.ACTION_DOWN, 0.5f); SystemClock.sleep(220)
            for (k in 1..6) { navTouch(MotionEvent.ACTION_MOVE, 0.5f + k / 6f); SystemClock.sleep(16) }
            SystemClock.sleep(120)
            runOnMainSync { check((field(nav, "lift") as Float) > 0.5f && field(home, "tab") == 0) { "Lens did not lift, or chose before release" } }
            capture("lens-drag")
            for (k in 1..6) { navTouch(MotionEvent.ACTION_MOVE, 1.5f + k / 6f); SystemClock.sleep(16) }
            navTouch(MotionEvent.ACTION_UP, 2.5f); pause(); pause()
            runOnMainSync { check(field(home, "tab") == 2 && (field(nav, "lift") as Float) < 0.05f) { "Lens did not land on the profile" } }
            capture("lens-landed")
            // Text filters use the navigation lens too, and recycled rows retain selection and kind.
            runOnMainSync {
                val filters = field(home, "filterTabs") as BottomNav
                home.javaClass.getDeclaredField("allChats").apply { isAccessible = true }.set(home, listOf(
                    JSONObject().put("id", "direct-fixture").put("kind", "direct").put("title", "Личный"),
                    JSONObject().put("id", "group-fixture").put("kind", "group").put("title", "Группа")))
                filters.getChildAt(2).performClick()
                check(field(home, "filter") == 2 && filters.getChildAt(2).isSelected) { "Group filter and lens disagree" }
                check((list.adapter as RowAdapter).rows.single().str("kind") == "group")
                filters.getChildAt(1).performClick(); filters.getChildAt(0).performClick()
                check(field(home, "filter") == 0 && (list.adapter as RowAdapter).rows.size == 2) { "Fast filters left stale rows" }

                val row = ListRow(activity)
                bindChatRow(activity, row, JSONObject().put("id", "group").put("kind", "group").put("title", "Группа"))
                check(row.groupIcon.visibility == View.VISIBLE) { "Group icon missing" }
                bindChatRow(activity, row, JSONObject().put("id", "direct").put("kind", "direct").put("title", "Контакт"))
                check(row.groupIcon.visibility == View.GONE) { "Recycled contact kept the group icon" }
                row.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(activity.dp(72), View.MeasureSpec.EXACTLY))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                fun tint(): Int {
                    val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
                    row.draw(Canvas(bitmap))
                    val color = bitmap.getPixel(activity.dp(6), row.height / 2)
                    bitmap.recycle()
                    return color
                }
                row.setChecked(false)
                val plain = tint()
                row.setChecked(true)
                val selectedTint = tint()
                check(row.isSelected && selectedTint != plain && tint() == selectedTint) { "Selected contact lost its blue fill after release" }
                row.setChecked(false)
                check(!row.isSelected && tint() == plain) { "Deselected contact kept its blue fill" }
            }
            pause(); pause()
            runOnMainSync {
                val filters = field(home, "filterTabs") as BottomNav
                check((field(filters, "lift") as Float) < 0.05f) { "Text lens stayed lifted" }
                val reduced = Theme.forceReduced
                Theme.forceReduced = true
                try {
                    filters.getChildAt(2).performClick()
                    check(!(field(filters, "slide") as Spring).running && !(field(filters, "sizing") as Spring).running) { "Reduced motion animated text filters" }
                    filters.getChildAt(0).performClick()
                } finally { Theme.forceReduced = reduced }
            }
            lateinit var profileTabs: BottomNav
            lateinit var profilePage: Any
            runOnMainSync {
                profilePage = field(home, "settingsPage")
                profileTabs = field(profilePage, "tabs") as BottomNav
            }
            val textStart = SystemClock.uptimeMillis()
            fun textTouch(action: Int, index: Int) = runOnMainSync {
                val item = profileTabs.getChildAt(index)
                val e = MotionEvent.obtain(textStart, SystemClock.uptimeMillis(), action,
                    item.left + item.width / 2f, profileTabs.height / 2f, 0)
                profileTabs.dispatchTouchEvent(e)
                e.recycle()
            }
            textTouch(MotionEvent.ACTION_DOWN, 0)
            textTouch(MotionEvent.ACTION_MOVE, 1)
            SystemClock.sleep(220)
            runOnMainSync {
                check((field(profileTabs, "lift") as Float) > 0.5f && field(profilePage, "category") == "stories") { "Text lens chose before release" }
            }
            capture("text-lens-drag")
            textTouch(MotionEvent.ACTION_CANCEL, 1); pause(); pause()
            runOnMainSync { check(profileTabs.getChildAt(0).isSelected && field(profilePage, "category") == "stories") { "Cancelled text drag changed the tab" } }
            textTouch(MotionEvent.ACTION_DOWN, 1); textTouch(MotionEvent.ACTION_UP, 1); pause(); pause()
            runOnMainSync { check(profileTabs.getChildAt(1).isSelected && field(profilePage, "category") == "media") { "Text lens did not select files" } }
            capture("text-lens-files")
            // The lens dragged against the edge of the long profile tabs carries the strip along and lands where the finger lets go.
            val tabScroll = profileTabs.parent as android.widget.HorizontalScrollView
            val scrollStart = SystemClock.uptimeMillis()
            fun scrollTouch(action: Int, fraction: Float) = runOnMainSync {
                val e = MotionEvent.obtain(scrollStart, SystemClock.uptimeMillis(), action, tabScroll.width * fraction, tabScroll.height / 2f, 0)
                tabScroll.dispatchTouchEvent(e)
                e.recycle()
            }
            scrollTouch(MotionEvent.ACTION_DOWN, 0.5f)
            for (step in 1..6) { scrollTouch(MotionEvent.ACTION_MOVE, 0.5f + step * 0.08f); SystemClock.sleep(25) }
            SystemClock.sleep(600)
            runOnMainSync {
                check(tabScroll.scrollX > 0 && field(profilePage, "category") == "media") { "Lens held at the edge did not scroll the strip, or chose before release" }
            }
            scrollTouch(MotionEvent.ACTION_UP, 0.98f); pause(); pause()
            runOnMainSync {
                check(field(profilePage, "category") == "privacy" && profileTabs.getChildAt(5).isSelected) { "Last profile tab is inaccessible" }
            }
            pause(); pause(); capture("text-lens-privacy")
            if (profileServer.isNotEmpty()) {
                runOnMainSync { activity.overlay.findViewWithTag<View>("toast")?.let {activity.overlay.removeView(it)} }
                check(profileServer == "http://127.0.0.1:8097")
                fun core(method:String,p:JSONObject=JSONObject()):Any? {
                    val (value,error)=app.neochat.core.CoreClient.callBlocking(method,if(method=="register") registrationParams(p) else p)
                    check(error==null) { "$method failed: ${error?.code}" };return value
                }
                val suffix=java.util.UUID.randomUUID().toString().take(8)
                core("register",JSONObject().put("server",profileServer).put("username","preview_$suffix").put("display_name","Андрей").put("password","local preview password").put("platform","android").put("device_name","Preview emulator"))
                val peerDir=File(targetContext.cacheDir,"profile-peer-$suffix")
                val peer=app.neochat.core.NativeCore.open(JSONObject().put("data_dir",peerDir.absolutePath).put("db_key","b".repeat(64)).put("foreground",false).toString()) {}
                try {
                    fun remote(method:String,p:JSONObject):Any? {
                        val result=JSONObject(app.neochat.core.NativeCore.call(peer,method,(if(method=="register") registrationParams(p) else p).toString()))
                        check(!result.has("error")) { "$method failed" };return result.opt("ok")
                    }
                    remote("register",JSONObject().put("server",profileServer).put("username","friend_$suffix").put("display_name","Дмитрий").put("password","local preview password").put("platform","ios").put("device_name","Preview peer"))
                    remote("set_profile",JSONObject().put("bio","На связи в NeoChat"))
                    val contact=core("resolve_contact",JSONObject().put("username","@friend_$suffix")) as JSONObject
                    val uid=contact.str("user_id")
                    core("open_direct",JSONObject().put("user_id",uid))
                    core("set_profile_note",JSONObject().put("user_id",uid).put("note","Встретиться в выходные"))
                    runOnMainSync {activity.showMain();activity.readinessPrompted=true;activity.openPage(PersonProfilePage(activity,uid))}
                    pause();capture("profile-067")
                    runOnMainSync {
                        val found=arrayListOf<View>();activity.window.decorView.findViewsWithText(found,"QR-код профиля",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        check(found.isNotEmpty()) { "Profile QR button missing" };found.first().performClick()
                    }
                    pause();capture("profile-067-qr")
                    runOnMainSync {activity.onBackPressed()};pause()
                    runOnMainSync {
                        val found=arrayListOf<View>();activity.window.decorView.findViewsWithText(found,"Конфиденциальность",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        check(found.isNotEmpty()) { "Profile tabs missing" }
                        found.first().performClick()
                    }
                    pause();capture("profile-067-privacy")
                    runOnMainSync {activity.closePage()};pause()
                    runOnMainSync {
                        val current=field(activity,"home") as HomeScreen
                        current.javaClass.getDeclaredMethod("selectTab",Int::class.javaPrimitiveType).apply {isAccessible=true}.invoke(current,2)
                        (field(current,"nav") as BottomNav).select(2)
                    }
                    pause();capture("profile-067-own")
                } finally {app.neochat.core.NativeCore.close(peer);core("logout")}
            }
        } finally { runOnMainSync { activity.finish() } }
    }
}
