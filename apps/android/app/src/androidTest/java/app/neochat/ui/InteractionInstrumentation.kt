package app.neochat.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File

/** Real Android touch dispatch, without a network account or additional test libraries. */
class InteractionInstrumentation : Instrumentation() {
    private var archive = false
    private var fixes = false
    private var push = false
    private var profileServer = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); archive = arguments?.getString("archive") == "true"; fixes = arguments?.getString("fixes") == "true"; push = arguments?.getString("push") == "true"; profileServer=arguments?.getString("profile_server") ?: ""; start() }

    override fun onStart() {
        val result = Bundle()
        try {
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
            if (archive) archiveInteraction()
            if (push) pushInteraction()
            if (fixes) screenAndFileRegressions()
            result.putString("stream", "OK: reply gestures; archive follows finger, partial/cancelled pull, scroll hiding, navigation cleanup, inline undo; tab bar lens lifts, follows and lands" +
                (if (push) "; FCM job and socket fallback" else "") + (if (profileServer.isNotEmpty()) "; verified email enrollment and native email form" else "") + "\n")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.message}\n")
            finish(0, result)
        }
    }

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
            touch(MotionEvent.ACTION_DOWN, 0); touch(MotionEvent.ACTION_MOVE, 150); touch(MotionEvent.ACTION_UP, 150); pause()
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
                check((field(profileTabs, "lift") as Float) > 0.5f && field(profilePage, "category") == "media") { "Text lens chose before release" }
            }
            capture("text-lens-drag")
            textTouch(MotionEvent.ACTION_CANCEL, 1); pause(); pause()
            runOnMainSync { check(profileTabs.getChildAt(0).isSelected && field(profilePage, "category") == "media") { "Cancelled text drag changed the tab" } }
            textTouch(MotionEvent.ACTION_DOWN, 1); textTouch(MotionEvent.ACTION_UP, 1); pause(); pause()
            runOnMainSync { check(profileTabs.getChildAt(1).isSelected && field(profilePage, "category") == "files") { "Text lens did not select files" } }
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
                check(tabScroll.scrollX > 0 && field(profilePage, "category") == "files") { "Lens held at the edge did not scroll the strip, or chose before release" }
            }
            scrollTouch(MotionEvent.ACTION_UP, 0.98f); pause(); pause()
            runOnMainSync {
                check(field(profilePage, "category") == "privacy" && profileTabs.getChildAt(4).isSelected) { "Last profile tab is inaccessible" }
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
