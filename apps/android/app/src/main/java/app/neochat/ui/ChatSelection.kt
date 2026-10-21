package app.neochat.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONObject

fun confirmChatRemoval(act: MainActivity, count: Int, hide: Boolean, run: () -> Unit) {
    val sheet = Sheet(act, if (hide) "Удалить чаты?" else "Очистить чат?")
    sheet.add(act.label("Выбрано: $count. История удалится только на этом устройстве. У собеседников сообщения останутся. Отправки из очереди продолжатся.", 15f))
    sheet.add(PillButton(act, if (hide) "Удалить" else "Очистить").apply {
        setOnClickListener { sheet.dismiss(); run() }
    })
    sheet.show()
}

fun confirmClearChat(act: MainActivity, id: String) = confirmChatRemoval(act, 1, false) {
    CoreClient.call("clear_conversation", JSONObject().put("conversation_id", id)) { _, e ->
        if (e != null) act.toast(e.message)
    }
}

/** Shared selection actions for the main list and archive. Long press starts selection. */
class ChatSelection(private val act: MainActivity, private val changed: () -> Unit) {
    val bar = LinearLayout(act)
    private val count = act.label("", 15f, 600)
    private val selected = linkedMapOf<String, JSONObject>()
    val active get() = selected.isNotEmpty()
    private var busy = false

    init {
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(act.dp(16), 0, act.dp(8), 0)
        bar.background = GlassDrawable(act, GlassDrawable.Kind.GLASS, act.dpf(18))
        bar.addView(count, lp(0, WRAP, 1f))
        val more = IconButton(act, R.drawable.ic_more, "Действия с выбранными чатами")
        more.setOnClickListener {
            if (busy) return@setOnClickListener
            val allPinned = selected.values.all { it.optBoolean("pinned") }
            val allArchived = selected.values.all { it.optBoolean("in_archive") }
            PopupMenu(act, more)
                .add(if (allArchived) "Вернуть из архива" else "В архив") { run("set_archived", "archived", !allArchived) }
                .add(if (allPinned) "Открепить" else "Закрепить") { run("set_pinned", "pinned", !allPinned) }
                .add("Очистить…", danger = true) { confirmChatRemoval(act, selected.size, false) { run("clear_conversation", "hide", false) } }
                .add("Удалить чаты…", danger = true) { confirmChatRemoval(act, selected.size, true) { run("clear_conversation", "hide", true) } }
                .show()
        }
        bar.addView(more)
        bar.addView(IconButton(act, R.drawable.ic_close, "Отменить выбор").apply { setOnClickListener { clear() } })
        bar.visibility = View.GONE
    }

    fun bind(row: ListRow, c: JSONObject) {
        val id = c.str("id")
        row.setChecked(if (active) selected.containsKey(id) else null)
        row.isSelected = selected.containsKey(id)
        row.setOnClickListener { if (active) toggle(c) else act.openChat(id) }
        row.setOnLongClickListener { toggle(c); true }
    }

    private fun toggle(c: JSONObject) {
        if (busy) return
        val id = c.str("id")
        if (selected.remove(id) == null) selected[id] = c
        update()
    }

    fun begin(c: JSONObject) { if (!active) toggle(c) }

    fun clear(): Boolean {
        if (!active || busy) return false
        selected.clear()
        update()
        return true
    }

    private fun update() {
        val entering = active && bar.visibility != View.VISIBLE
        bar.visibility = if (active) View.VISIBLE else View.GONE
        count.text = "Выбрано: ${selected.size}"
        if (entering && !Theme.reducedMotion) {
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(180).start()
        }
        changed()
    }

    private fun run(method: String, key: String, value: Boolean) {
        if (busy || !active) return
        val ids = selected.keys.toList()
        val toUndo = selected.values.filter { !it.optBoolean("in_archive") }.map { it.str("id") }
        val archived = arrayListOf<String>()
        busy = true
        fun next(i: Int) {
            if (i == ids.size) {
                busy = false
                update()
                if (method == "set_archived") {
                    act.overlay.replyHaptic()
                    if (value) archiveUndo(act, archived) { changed() }
                }
                return
            }
            CoreClient.call(method, JSONObject().put("conversation_id", ids[i]).put(key, value)) { _, e ->
                if (e != null) { busy = false; update(); act.toast(e.message); return@call }
                if (method == "set_archived" && value && ids[i] in toUndo) archived += ids[i]
                selected.remove(ids[i])
                next(i + 1)
            }
        }
        next(0)
    }
}
