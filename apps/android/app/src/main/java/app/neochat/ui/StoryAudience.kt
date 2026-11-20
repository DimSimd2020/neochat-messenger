package app.neochat.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject

internal enum class StoryVisibility(val title: String, val action: String, val icon: Int) {
    ALL("Все", "Добавить исключения", R.drawable.ic_speaker),
    CONTACTS("Мои контакты", "Добавить исключения", R.drawable.ic_profile),
    FRIENDS("Близкие друзья", "Изменить список", R.drawable.ic_shield),
    SELECTED("Выбранные пользователи", "Выбрать пользователей", R.drawable.ic_people);

    fun allows(user: String, contacts: Set<String>, choices: Set<String>) = when (this) {
        ALL -> user !in choices
        CONTACTS -> user in contacts && user !in choices
        FRIENDS, SELECTED -> user in choices
    }
}

internal class StoryAudience(
    private val act: MainActivity,
    private val changeTitle: (String) -> Unit,
    private val publishStory: (List<String>) -> Unit,
) : LinearLayout(act) {
    private val audiences = LinearLayout(act).apply { orientation = VERTICAL }
    private val people = LinearLayout(act).apply { orientation = VERTICAL; visibility = GONE }
    private val accent = 0xff98b9ff.toInt()
    private val status = text("", 13f, 500, STORY_MUTED).apply {
        setPadding(act.dp(8), act.dp(10), act.dp(8), act.dp(10))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val screenshotOption = Switch(act).apply {
        text = "Разрешить снимки экрана"
        setTextColor(Color.WHITE)
        thumbTintList = ColorStateList.valueOf(accent)
        setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6))
        background = act.storyPanel()
        isChecked = true
        minHeight = act.dp(48)
    }
    private val profileOption = Switch(act).apply {
        text = "Опубликовать в моём профиле"
        setTextColor(Color.WHITE)
        thumbTintList = ColorStateList.valueOf(accent)
        setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6))
        background = act.storyPanel()
        isChecked = false
        minHeight = act.dp(48)
    }
    fun options(): JSONObject = JSONObject()
        .put("screenshots", screenshotOption.isChecked)
        .put("profile", profileOption.isChecked)
        .put("audience", mode.name.lowercase())

    private val author = text("", 15f, 700)
    private val avatar = AvatarView(act, 40)
    private val search = EditText(act).apply {
        hint = "Поиск людей"
        contentDescription = hint
        setSingleLine()
        setTextSize(16f)
        setTextColor(Color.WHITE)
        setHintTextColor(STORY_MUTED)
        setPadding(act.dp(16), 0, act.dp(16), 0)
        background = act.storyPanel()
    }
    private val list = RowAdapter(40, ::bindPerson)
    private val publish = PillButton(act, "Опубликовать").apply {
        background = act.storyPanel(0xff4169cf.toInt())
        isEnabled = false
    }
    private val friendKey = "story_friends_${CoreClient.accountId}"
    private val choices = StoryVisibility.entries.associateWith { mode ->
        if (mode == StoryVisibility.FRIENDS) Prefs.of(act).getStringSet(friendKey, emptySet()).orEmpty().toMutableSet()
        else linkedSetOf()
    }
    private val controls = mutableMapOf<StoryVisibility, Triple<View, RadioButton, TextView>>()
    private var contacts = emptySet<String>()
    private var chats: List<JSONObject>? = null
    private var errorMessage: String? = null
    private var mode = StoryVisibility.ALL
    private var editing: StoryVisibility? = null
    private var selection = mutableSetOf<String>()
    private var busy = false
    private var generation = 0

    init {
        orientation = VERTICAL
        visibility = GONE
        val content = LinearLayout(act).apply { orientation = VERTICAL }
        audiences.addView(ScrollView(act).apply {
            addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        }, lp(MATCH, 0, 1f))
        val identity = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            addView(avatar)
            addView(LinearLayout(act).apply {
                orientation = VERTICAL
                addView(author, lp())
                addView(text("Личный аккаунт", 13f, 500, STORY_MUTED), lp())
            }, lp(0, WRAP, 1f).apply { marginStart = act.dp(12) })
        }
        content.addView(identity, lp().apply { bottomMargin = act.dp(18) })
        content.addView(text("Аудитория истории", 14f, 700, accent).apply {
            heading()
            setPadding(act.dp(8), 0, 0, act.dp(8))
        }, lp())
        for (entry in StoryVisibility.entries) addAudience(content, entry)
        content.addView(text("НАСТРОЙКИ ПУБЛИКАЦИИ", 13f, 700, accent).apply {
            heading()
            setPadding(act.dp(8), act.dp(16), 0, act.dp(6))
        })
        content.addView(screenshotOption, lp().apply { bottomMargin = act.dp(6) })
        content.addView(profileOption, lp())
        content.addView(text("Если запретить снимки, NeoChat защитит экран истории системными средствами Android.",
            12f, 400, STORY_MUTED).apply { setPadding(act.dp(8), 0, act.dp(8), act.dp(6)) })
        content.addView(status, lp())
        audiences.addView(publish, lp().apply { topMargin = act.dp(8); bottomMargin = act.dp(8) })
        publish.setOnClickListener {
            val ids = recipients()
            if (!busy && ids.isNotEmpty()) publishStory(ids)
        }
        addView(audiences, lp(MATCH, MATCH))

        people.addView(search, lp(MATCH, act.dp(48)))
        people.addView(RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            adapter = list
            itemAnimator = null
        }, lp(MATCH, 0, 1f).apply { topMargin = act.dp(8) })
        people.addView(PillButton(act, "Готово").apply {
            setOnClickListener {
                val current = editing ?: return@setOnClickListener
                choices.getValue(current).apply { clear(); addAll(selection) }
                if (current == StoryVisibility.FRIENDS) {
                    Prefs.of(act).edit().putStringSet(friendKey, selection.toSet()).apply()
                }
                mode = current
                returnToAudiences()
            }
        }, lp().apply { topMargin = act.dp(8); bottomMargin = act.dp(8) })
        addView(people, lp(MATCH, MATCH))
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = filterPeople()
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        CoreClient.call("status") { value, _ ->
            val profile = value as? JSONObject ?: return@call
            val name = profile.str("display_name").ifEmpty { profile.str("username") }
            if (name.isEmpty()) return@call
            author.organizationName(name, profile.str("user_id"))
            avatar.set(name, profile.str("user_id"), profile.str("avatar_path"))
            identity.visibility = VISIBLE
        }
        refresh()
    }

    private fun text(label: String, size: Float, weight: Int, color: Int = Color.WHITE) =
        act.label(label, size, weight, Theme.Role.ON_ACCENT).apply {
            setTag(Theme.TAG_ROLE, null)
            setTextColor(color)
        }

    private fun addAudience(parent: LinearLayout, entry: StoryVisibility) {
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val radio = RadioButton(act).apply {
            minimumWidth = act.dp(48)
            gravity = Gravity.CENTER
            contentDescription = entry.title
            buttonTintList = ColorStateList.valueOf(accent)
        }
        val symbol = IconButton(act, entry.icon, "", sizeDp = 40, iconRole = Theme.Role.ON_ACCENT).apply {
            background = act.storyPanel(0xff334873.toInt())
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = text(entry.title, 16f, 700)
        val edit = text(entry.action, 13.5f, 600, accent).apply {
            minHeight = act.dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            pressFeedback()
            setOnClickListener { openPeople(entry) }
        }
        row.addView(radio, lp(act.dp(48), act.dp(52)))
        row.addView(symbol, lp(act.dp(40), act.dp(40)))
        row.addView(LinearLayout(act).apply {
            orientation = VERTICAL
            addView(label, lp())
            addView(edit, lp())
        }, lp(0, WRAP, 1f).apply { marginStart = act.dp(12); marginEnd = act.dp(8); topMargin = act.dp(10) })
        fun select() { mode = entry; refresh() }
        row.setOnClickListener { select() }
        radio.setOnClickListener { select() }
        parent.addView(row, lp().apply { bottomMargin = act.dp(2) })
        controls[entry] = Triple(row, radio, edit)
    }

    fun open() {
        visibility = VISIBLE
        changeTitle("Публикация истории")
        val request = ++generation
        chats = null
        errorMessage = null
        refresh()
        CoreClient.call("list_conversations") { result, error ->
            if (request != generation) return@call
            if (error != null) { errorMessage = error.message; refresh(); return@call }
            val available = (result as? JSONArray)?.objects().orEmpty().filter {
                it.str("kind") == "direct" && it.optBoolean("can_media") && it.str("peer_user_id").isNotEmpty()
            }.distinctBy { it.str("peer_user_id") }
            CoreClient.call("list_contacts") { users, fault ->
                if (request != generation) return@call
                if (fault != null) { errorMessage = fault.message; refresh(); return@call }
                contacts = (users as? JSONArray)?.objects().orEmpty().mapTo(hashSetOf()) { it.str("user_id") }
                chats = available
                refresh()
            }
        }
    }

    fun collapse() { ++generation; visibility = GONE }

    fun back(): Boolean {
        if (editing == null) return false
        returnToAudiences()
        return true
    }

    fun setBusy(value: Boolean) { busy = value; refresh() }

    private fun recipients() = chats.orEmpty().filter {
        mode.allows(it.str("peer_user_id"), contacts, choices.getValue(mode))
    }.map { it.str("id") }

    private fun refresh() {
        val available = chats
        for ((entry, control) in controls) {
            val (row, radio, edit) = control
            radio.isChecked = entry == mode
            row.background = if (entry == mode) act.storyPanel() else null
            row.isClickable = !busy
            radio.isEnabled = !busy
            edit.isEnabled = !busy && available != null
            val count = choices.getValue(entry).size
            edit.text = if (count == 0) "${entry.action} ›" else "${entry.action} ($count) ›"
        }
        publish.setText(if (busy) "Подготовка…" else "Опубликовать")
        val hasAudience = recipients().isNotEmpty()
        publish.isEnabled = !busy && hasAudience
        status.text = when {
            available == null -> errorMessage ?: "Загрузка…"
            available.isEmpty() -> "Для публикации нужен доступный личный чат."
            !hasAudience -> "В этой аудитории пока никого нет."
            mode == StoryVisibility.ALL -> "История появится у доступных собеседников NeoChat."
            else -> ""
        }
    }

    private fun openPeople(entry: StoryVisibility) {
        editing = entry
        selection = choices.getValue(entry).toMutableSet()
        changeTitle(if (entry == StoryVisibility.ALL || entry == StoryVisibility.CONTACTS) "Исключения" else entry.title)
        audiences.visibility = GONE
        people.visibility = VISIBLE
        search.setText("")
        filterPeople()
    }

    private fun returnToAudiences() {
        editing = null
        people.visibility = GONE
        audiences.visibility = VISIBLE
        changeTitle("Публикация истории")
        refresh()
    }

    private fun filterPeople() {
        val query = search.text.toString().trim()
        list.rows = chats.orEmpty().filter {
            (editing != StoryVisibility.CONTACTS || it.str("peer_user_id") in contacts) &&
                it.str("title").contains(query, ignoreCase = true)
        }
    }

    private fun bindPerson(row: ListRow, chat: JSONObject) {
        val id = chat.str("peer_user_id")
        val name = chat.str("title")
        row.title.organizationName(name, id)
        row.title.role(Theme.Role.ON_ACCENT)
        row.subtitle.visibility = GONE
        row.contentDescription = name
        row.avatar.set(name, id, chat.str("avatar_path"))
        fun mark() {
            row.setChecked(id in selection)
            row.check.imageTintList = ColorStateList.valueOf(accent)
            row.background = if (row.isSelected) act.storyPanel() else null
            row.describeState(if (id in selection) "Выбран" else "Не выбран")
        }
        mark()
        row.setOnClickListener { if (!selection.remove(id)) selection += id; mark() }
    }
}
