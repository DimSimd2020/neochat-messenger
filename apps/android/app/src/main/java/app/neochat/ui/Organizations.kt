package app.neochat.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.widget.ImageView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun showOrganizations(act: MainActivity, profileChanged: () -> Unit) {
    CoreClient.call("organizations", JSONObject()) { value, error ->
        if (error != null) return@call act.toast(error.message)
        val sheet = Sheet(act, "Организации")
        val organizations = (value as? JSONArray)?.objects().orEmpty()
        if (organizations.isEmpty()) sheet.add(act.label("У вас пока нет организаций и приглашений", 15f, 400))
        for (org in organizations) {
            sheet.organizationButton("${org.str("name")} · ${organizationRole(org.str("role"))}") {
                sheet.dismiss()
                showOrganization(act, org, profileChanged)
            }
        }
        sheet.show()
    }
}

private fun showOrganization(act: MainActivity, org: JSONObject, profileChanged: () -> Unit) {
    val id = org.str("id")
    val role = org.str("role")
    val sheet = Sheet(act, org.str("name"))
    val refresh = { sheet.dismiss(); profileChanged(); showOrganizations(act, profileChanged) }
    if (role == "invited") {
        sheet.organizationButton("Принять приглашение") {
            updateOrganization(act, id, "accept", done = refresh)
        }
        sheet.organizationButton("Отклонить") {
            updateOrganization(act, id, "decline", done = refresh)
        }
        sheet.show()
        return
    }
    for ((kind, label) in listOf("channel" to "Канал", "group" to "Группа")) {
        val chatId = org.str("${kind}_id")
        if (chatId.isNotEmpty()) sheet.organizationButton(label) {
            CoreClient.call("get_conversation", JSONObject().put("conversation_id", chatId)) { value, error ->
                val chat = value as? JSONObject
                if (error != null || chat == null || chat.optBoolean("left")) {
                    act.toast("Доступ к чату выдаёт его администрация")
                } else {
                    sheet.dismiss()
                    act.openChat(chatId)
                }
            }
        }
        if (role == "owner" && chatId.isEmpty()) {
            val createLabel = if (kind == "channel") "Создать канал организации" else "Создать группу организации"
            sheet.organizationButton(createLabel) {
                val params = JSONObject().put("organization_id", id).put("kind", kind).put("title", "${org.str("name")} · $label")
                CoreClient.call("create_group", params) { _, error ->
                    if (error != null) act.toast(error.message) else refresh()
                }
            }
        }
        if (role == "owner") sheet.organizationButton(if (chatId.isEmpty()) "Привязать: $label" else "Заменить: $label") {
            CoreClient.call("organization_chats", JSONObject().put("kind", kind)) { value, error ->
                if (error != null) return@call act.toast(error.message)
                val picker = Sheet(act, label)
                val chats = (value as? JSONArray)?.objects().orEmpty()
                if (chats.isEmpty()) picker.add(act.label("Сначала создайте чат этого типа в списке чатов", 15f, 400))
                for (chat in chats) picker.organizationButton(chat.str("title")) {
                    updateOrganization(act, id, "link_$kind", JSONObject().put("conversation_id", chat.str("id"))) {
                        picker.dismiss()
                        refresh()
                    }
                }
                picker.show()
            }
        }
    }
    sheet.organizationButton("Участники") {
        CoreClient.call("organization_members", JSONObject().put("organization_id", id)) { value, error ->
            if (error != null) return@call act.toast(error.message)
            val members = Sheet(act, "Участники")
            for (person in (value as? JSONArray)?.objects().orEmpty()) {
                val memberRole = person.str("role")
                members.add(act.label("", 15f, 400).apply {
                    organizationName("@${person.str("username")}", person.str("user_id"), " · ${organizationRole(memberRole)}")
                })
                if (role == "owner" && memberRole in listOf("admin", "member")) {
                    val next = if (memberRole == "admin") "member" else "admin"
                    members.organizationButton(if (next == "admin") "Назначить администратором" else "Снять права администратора") {
                        updateOrganization(act, id, "role", JSONObject().put("user_id", person.str("user_id")).put("role", next)) {
                            members.dismiss()
                            OrganizationBadges.refresh()
                            refresh()
                        }
                    }
                }
            }
            members.show()
        }
    }
    if (role == "owner" || role == "admin") {
        val username = FloatingField(act, "@Имя пользователя")
        sheet.add(username)
        sheet.organizationButton("Пригласить") {
            updateOrganization(act, id, "invite", JSONObject().put("username", username.text)) {
                username.setText("")
                act.toast("Приглашение отправлено")
            }
        }
    }
    if (role == "owner") {
        val icon = ImageView(act).apply {
            contentDescription = "Значок организации"
            setImageResource(R.drawable.ic_people)
        }
        sheet.body.addView(icon, lp(act.dp(40), act.dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        org.str("icon_id").takeIf { it.isNotEmpty() }?.let { iconId ->
            OrganizationBadges.loadIcon(iconId) { bitmap -> if (bitmap != null) icon.setImageBitmap(bitmap) }
        }
        sheet.organizationButton("Выбрать изображение значка") {
            pickOrganizationIcon(act, id) {
                OrganizationBadges.refresh()
                refresh()
            }
        }
    }
    sheet.show()
}

private fun pickOrganizationIcon(act: MainActivity, id: String, done: () -> Unit) {
    val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
    act.startForResult(intent) { code, data ->
        val uri = data?.data ?: return@startForResult
        if (code != Activity.RESULT_OK) return@startForResult
        Thread {
            var file: File? = null
            val prepared = runCatching {
                val decoded = checkNotNull(decodePhotoUri(act, uri, 96))
                val ratio = 96f / maxOf(decoded.width, decoded.height)
                val left = (96 - decoded.width * ratio) / 2f
                val top = (96 - decoded.height * ratio) / 2f
                val icon = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                Canvas(icon).drawBitmap(decoded, null, RectF(left, top, 96 - left, 96 - top), Paint(Paint.FILTER_BITMAP_FLAG))
                File.createTempFile("organization-icon-", ".png", act.cacheDir).also { output ->
                    file = output
                    output.outputStream().use { check(icon.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            }.getOrNull()
            if (prepared == null) {
                file?.delete()
                act.runOnUiThread { act.toast("Не удалось прочитать изображение") }
            } else {
                val (_, error) = try {
                    CoreClient.callBlocking("set_organization_icon", JSONObject().put("organization_id", id).put("path", prepared.absolutePath))
                } finally {
                    prepared.delete()
                }
                act.runOnUiThread { if (error != null) act.toast(error.message) else done() }
            }
        }.start()
    }
}

private fun updateOrganization(
    act: MainActivity,
    id: String,
    action: String,
    payload: JSONObject = JSONObject(),
    done: () -> Unit,
) {
    CoreClient.call("organization_update", payload.put("organization_id", id).put("action", action)) { _, error ->
        if (error != null) act.toast(error.message) else done()
    }
}

private fun Sheet.organizationButton(title: String, action: () -> Unit) {
    add(PillButton(ctx, title, primary = false).apply { setOnClickListener { action() } })
}

private fun organizationRole(role: String) = when (role) {
    "owner" -> "Владелец"
    "admin" -> "Администратор"
    "invited" -> "Приглашение"
    else -> "Участник"
}
