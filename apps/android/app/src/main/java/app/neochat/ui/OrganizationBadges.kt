package app.neochat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Base64
import android.util.LruCache
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

fun TextView.organizationName(name: String, userId: String, suffix: String = "") {
    OrganizationBadges.bind(this, name, userId, suffix)
}

internal object OrganizationBadges {
    private data class Badge(val name: String, val icon: String)
    private data class Cached(val badges: List<Badge>, val until: Long)
    private class Binding(val user: String, val name: String, val suffix: String, var rendered: CharSequence)
    private class IconSpan(drawable: android.graphics.drawable.Drawable, val organization: String) :
        ImageSpan(drawable, ALIGN_CENTER)

    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val people = LruCache<String, Cached>(512)
    private val icons = LruCache<String, Bitmap>(48)
    private val bindings = WeakHashMap<TextView, Binding>()
    private val actions = WeakHashMap<TextView, List<Int>>()
    private val queued = linkedSetOf<String>()
    private val loading = mutableSetOf<String>()
    private val loadingIcons = mutableSetOf<String>()
    private val failedIcons = LruCache<String, Long>(48)
    private val uuid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private var scheduled = false
    private var account = CoreClient.accountId

    fun bind(view: TextView, name: String, user: String, suffix: String) {
        if (account != CoreClient.accountId) {
            account = CoreClient.accountId
            queued.clear()
            loading.clear()
            people.evictAll()
        }
        clearActions(view)
        view.text = name + suffix
        if (!uuid.matches(user) || name.isEmpty()) {
            bindings.remove(view)
            view.setOnTouchListener(null)
            return
        }
        val binding = Binding(user, name, suffix, view.text)
        bindings[view] = binding
        render(view, binding, people.get(user)?.badges.orEmpty())
        var pressed: IconSpan? = null
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = hit(view, event)
                    pressed != null
                }
                MotionEvent.ACTION_UP -> {
                    val badge = pressed
                    pressed = null
                    if (badge != null && hit(view, event) === badge) show(view, badge.organization)
                    badge != null
                }
                MotionEvent.ACTION_CANCEL -> (pressed != null).also { pressed = null }
                else -> pressed != null
            }
        }
        if ((people.get(user)?.until ?: 0) <= SystemClock.elapsedRealtime() && user !in loading) enqueue(user)
    }

    fun refresh() {
        people.evictAll()
        bindings.values.map { it.user }.distinct().filterNot { it in loading }.forEach(::enqueue)
    }

    private fun enqueue(user: String) {
        queued.add(user)
        schedule()
    }

    private fun schedule() {
        if (scheduled || loading.isNotEmpty() || queued.isEmpty()) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            if (queued.isNotEmpty()) fetch()
        }, 40)
    }

    private fun fetch() {
        val users = queued.take(64)
        queued.removeAll(users.toSet())
        loading.addAll(users)
        CoreClient.call("organization_badges_batch", JSONObject().put("user_ids", JSONArray(users))) { value, error ->
            loading.removeAll(users.toSet())
            val results = value as? JSONObject
            val now = SystemClock.elapsedRealtime()
            for (user in users) {
                val previous = people.get(user)?.badges.orEmpty()
                val badges = if (error != null) previous else results?.optJSONArray(user)?.objects().orEmpty()
                    .map { Badge(it.str("name"), it.str("icon_id")) }
                people.put(user, Cached(badges, now + if (error == null) 120_000 else 15_000))
            }
            redraw { it.user in users }
            schedule()
        }
    }

    fun loadIcon(id: String, result: (Bitmap?) -> Unit) {
        CoreClient.call("organization_icon", JSONObject().put("icon_id", id)) { value, error ->
            val data = (value as? JSONObject)?.str("png").orEmpty()
            if (error != null || data.isEmpty()) {
                result(null)
                return@call
            }
            decoder.execute {
                val image = runCatching {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                handler.post { result(image) }
            }
        }
    }

    private fun requestIcon(id: String) {
        if ((failedIcons.get(id) ?: 0) > SystemClock.elapsedRealtime() || !loadingIcons.add(id)) return
        loadIcon(id) { icon ->
            loadingIcons.remove(id)
            if (icon == null) {
                failedIcons.put(id, SystemClock.elapsedRealtime() + 15_000)
                return@loadIcon
            }
            icons.put(id, icon)
            redraw { binding -> people.get(binding.user)?.badges?.any { it.icon == id } == true }
        }
    }

    private fun redraw(filter: (Binding) -> Boolean) {
        for ((view, binding) in bindings.toMap()) {
            if (view.text === binding.rendered && filter(binding)) {
                render(view, binding, people.get(binding.user)?.badges.orEmpty())
            }
        }
    }

    private fun render(view: TextView, binding: Binding, badges: List<Badge>) {
        clearActions(view)
        val text = SpannableStringBuilder(binding.name)
        val actionIds = mutableListOf<Int>()
        for (badge in badges) {
            val image = icons.get(badge.icon)
            if (image == null && badge.icon.isNotEmpty()) requestIcon(badge.icon)
            val drawable = if (image != null) BitmapDrawable(view.resources, image) else
                view.context.getDrawable(R.drawable.ic_people)?.mutate()?.apply { setTint(Theme.p.accent) } ?: continue
            val size = view.context.dp(22)
            drawable.setBounds(0, 0, size, size)
            text.append("\u00A0\uFFFC")
            text.setSpan(IconSpan(drawable, badge.name), text.length - 1, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            actionIds.add(ViewCompat.addAccessibilityAction(view, "Член организации ${badge.name}") { target, _ ->
                show(target, badge.name)
                true
            })
        }
        if (actionIds.isNotEmpty()) actions[view] = actionIds
        text.append(binding.suffix)
        view.text = text
        binding.rendered = view.text
    }

    private fun clearActions(view: TextView) {
        actions.remove(view)?.forEach { ViewCompat.removeAccessibilityAction(view, it) }
    }

    private fun hit(view: TextView, e: MotionEvent): IconSpan? {
        val text = view.text as? Spanned ?: return null
        val layout = view.layout ?: return null
        val x = e.x - view.totalPaddingLeft + view.scrollX
        val y = e.y - view.totalPaddingTop + view.scrollY
        if (y < 0 || y >= layout.height) return null
        val line = layout.getLineForVertical(y.toInt())
        return text.getSpans(layout.getLineStart(line), layout.getLineEnd(line), IconSpan::class.java).firstOrNull {
            val start = text.getSpanStart(it)
            val visibleEnd = layout.getLineStart(line) + layout.getEllipsisStart(line)
            if (layout.getEllipsisCount(line) != 0 && start >= visibleEnd) return@firstOrNull false
            val left = layout.getPrimaryHorizontal(start)
            val right = layout.getPrimaryHorizontal(text.getSpanEnd(it))
            x >= min(left, right) && x < max(left, right)
        }
    }

    private fun show(view: android.view.View, name: String) {
        val message = "Пользователь является членом организации «$name»"
        val activity = view.context as? MainActivity
        if (activity != null) activity.toast(message)
        else android.widget.Toast.makeText(view.context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
