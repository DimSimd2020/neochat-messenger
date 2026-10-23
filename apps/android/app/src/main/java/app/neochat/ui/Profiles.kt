package app.neochat.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A full native profile, including private notes and paged shared items. */
class PersonProfilePage(private val act: MainActivity, private val userId: String? = null) : Screen {
    private val root = FrameLayout(act)
    override val view: View get() = root
    private val scroll = ScrollView(act)
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val details = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val avatar = AvatarView(act, 112)
    // The cover reaches under the status bar; the avatar sits on its lower edge.
    private val cover = ProfileHeaderView(act)
    private val coverBox = FrameLayout(act)
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private var backButton: View? = null
    private var inset = 0
    private val name = act.label("", 27f, 700)
    private val sub = act.label("", 14f, 400, Theme.Role.MUTED)
    private val tabs = BottomNav(act, listOf("Медиа", "Файлы", "Ссылки", "Группы", "Конфиденциальность").map { 0 to it }, textOnly = true, equalWidth = false)
    private val tabKeys = listOf("media", "files", "links", "groups", "privacy")
    private var tabIndex = 0
    private var profile = JSONObject()
    private var category = "media"
    private var before: Long? = null
    private var generation = 0
    private val items = ArrayList<JSONObject>()
    private fun dp(n: Int) = act.dp(n)

    init {
        root.addView(BackgroundView(act), flp(MATCH, MATCH))
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.setPadding(0,0,0,dp(28))
        coverBox.addView(cover,FrameLayout.LayoutParams(MATCH,dp(150)))
        coverBox.addView(avatar,FrameLayout.LayoutParams(dp(112),dp(112),Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(94) })
        if (userId != null) {
            val back = IconButton(act,R.drawable.ic_back,"Назад",glass = true).apply { setOnClickListener { act.closePage() } }
            coverBox.addView(back,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.START).apply { topMargin = dp(8); marginStart = dp(8) })
            backButton = back
        }
        col.addView(coverBox,lp(MATCH,dp(206)))
        body.setPadding(dp(16),dp(14),dp(16),0)
        col.addView(body,lp())
        avatar.contentDescription = "Открыть фото профиля"; avatar.isFocusable = true
        avatar.setOnClickListener { showProfilePhotos(act,profile.str("user_id"),profile.optBoolean("me")) { load() } }
        name.gravity = Gravity.CENTER; sub.gravity = Gravity.CENTER
        body.addView(name,lp().apply { topMargin = dp(4) }); body.addView(sub,lp().apply { topMargin = dp(6) })
        body.addView(details,lp().apply { topMargin = dp(22) })
        val tabScroll = HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            addView(tabs, android.widget.FrameLayout.LayoutParams(WRAP, dp(68)))
        }
        tabs.onSelect = { select(tabKeys[it]) }
        body.addView(tabScroll,lp().apply { topMargin = dp(22) }); body.addView(content,lp().apply { topMargin = dp(16) })
        scroll.addView(col); column.addView(scroll,lp(MATCH,0,1f)); root.addView(column,flp(MATCH,MATCH))
        load()
    }
    override fun applyInsets(top:Int,bottom:Int,ime:Int) {
        inset = top
        root.setPadding(0,0,0,maxOf(bottom,ime))
        (cover.layoutParams as FrameLayout.LayoutParams).height = dp(150) + top
        (avatar.layoutParams as FrameLayout.LayoutParams).topMargin = dp(94) + top
        backButton?.let { (it.layoutParams as FrameLayout.LayoutParams).topMargin = dp(8) + top }
        coverBox.layoutParams.height = dp(206) + top
        coverBox.requestLayout()
    }
    fun setBottomPadding(px:Int) = scroll.setPadding(0,0,0,px)
    override fun onShown() = load()
    override fun onEvent(ev:JSONObject) { if (ev.str("type") in listOf("account_changed","contacts_changed","identity_changed")) load() }

    private fun action(text:String,icon:Int,go:()->Unit) = PillButton(act,text,primary=false,icon=icon).apply { setOnClickListener { go() } }
    private fun info(title:String,value:String,go:(()->Unit)?=null):LinearLayout {
        val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)) }
        row.addView(act.label(value,16f,400)); row.addView(act.label(title,12f,400,Theme.Role.MUTED),lp().apply { topMargin = dp(5) })
        if (go!=null) { row.isFocusable = true; row.setOnClickListener { go() } }
        return row
    }
    fun load() {
        CoreClient.call("user_profile",JSONObject().apply { userId?.let { put("user_id",it) } }) { v,e ->
            if (e!=null) return@call
            profile = v as? JSONObject ?: return@call
            val own = profile.optBoolean("me"); val uid = profile.str("user_id")
            name.text = profile.str("display_name").ifEmpty { "@"+profile.str("username") }
            name.personColor(profile.str("name_color"),Theme.Role.TEXT)
            cover.colors = ProfileStyle.colors(profile.str("header"),uid)
            sub.text = if(own) "Ваш профиль" else "@"+profile.str("username")
            avatar.set(name.text.toString(),uid,profile.str("avatar_path"))
            details.removeAllViews()
            val actions = LinearLayout(act)
            if (own) {
                actions.addView(action("Изменить",R.drawable.ic_edit) { edit() },lp(0,WRAP,1f))
                actions.addView(action("Настройки",R.drawable.ic_settings) { openSettings() },lp(0,WRAP,1f).apply { marginStart=dp(8) })
            } else {
                actions.addView(action("Чат",R.drawable.ic_chat) { openChat() },lp(0,WRAP,1f))
                actions.addView(action("Звонок",R.drawable.ic_phone) { call(false) },lp(0,WRAP,1f).apply { marginStart=dp(8) })
                actions.addView(action("Видео",R.drawable.ic_video) { call(true) },lp(0,WRAP,1f).apply { marginStart=dp(8) })
            }
            details.addView(actions)
            val card = LinearLayout(act).apply { orientation=LinearLayout.VERTICAL; background=GlassDrawable(act,radius=act.dpf(24)) }
            val line = LinearLayout(act).apply { gravity=Gravity.CENTER_VERTICAL }
            line.addView(info("Имя пользователя","@"+profile.str("username")) {
                (act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Имя пользователя","@"+profile.str("username"))); act.toast("Имя пользователя скопировано")
            },lp(0,WRAP,1f))
            line.addView(IconButton(act,R.drawable.ic_qr,"QR-код профиля").apply { setOnClickListener { profileQr(act,uid) } })
            if (!own && !profile.optBoolean("is_contact")) line.addView(IconButton(act,R.drawable.ic_person_add,"Добавить в контакт").apply {
                setOnClickListener { CoreClient.call("resolve_contact",JSONObject().put("username",profile.str("username"))) { _,err -> if(err!=null) act.toast(err.message) else { act.toast("Контакт добавлен"); load() } } }
            })
            card.addView(line); card.addView(info("О себе",profile.str("bio").ifEmpty { "Не указано" }))
            if (own) card.addView(info("Оформление","Цвет имени и шапка профиля") { editProfileStyle(act,profile) { load() } })
            if (!own) card.addView(info("Заметки · видны только вам",profile.str("note").ifEmpty { "Добавить заметку" }) { editNote() })
            details.addView(card,lp().apply { topMargin=dp(14) }); select(category,false)
        }
    }
    private fun openChat() { CoreClient.call("open_direct",JSONObject().put("user_id",profile.str("user_id"))) { v,e -> if(e!=null) act.toast(e.message) else act.openChat((v as JSONObject).str("id")) } }
    private fun call(video:Boolean) { CoreClient.call("open_direct",JSONObject().put("user_id",profile.str("user_id"))) { v,e ->
        if(e!=null) return@call act.toast(e.message)
        act.withPermission(android.Manifest.permission.RECORD_AUDIO) { mic -> if(mic) {
            if(video) act.withPermission(android.Manifest.permission.CAMERA) { cam -> app.neochat.calls.CallManager.start(v as JSONObject,cam) }
            else app.neochat.calls.CallManager.start(v as JSONObject,false)
        } }
    } }
    private fun edit() {
        val sheet=Sheet(act,"Изменить профиль"); val name=FloatingField(act,"Имя"); val nick=FloatingField(act,"Имя пользователя"); val bio=FloatingField(act,"О себе, до 100 символов")
        name.setText(profile.str("display_name")); nick.setText("@"+profile.str("username")); bio.setText(profile.str("bio")); bio.edit.filters=arrayOf(android.text.InputFilter.LengthFilter(100))
        sheet.add(name);sheet.add(nick);sheet.add(bio)
        val save=PillButton(act,"Сохранить"); sheet.add(save)
        save.setOnClickListener { save.isEnabled=false; CoreClient.call("set_profile",JSONObject().put("display_name",name.text.trim()).put("username",nick.text.trim()).put("bio",bio.text.trim())) { _,e -> save.isEnabled=true; if(e!=null) act.toast(e.message) else { sheet.dismiss();load() } } };sheet.show()
    }
    private fun editNote() {
        val sheet=Sheet(act,"Личная заметка"); val field=FloatingField(act,"До 100 символов");field.setText(profile.str("note"));field.edit.filters=arrayOf(android.text.InputFilter.LengthFilter(100))
        sheet.add(field);sheet.add(act.label("Видна только вам и синхронизируется между вашими устройствами.",14f,400,Theme.Role.MUTED))
        val save=PillButton(act,"Сохранить");sheet.add(save);save.setOnClickListener { save.isEnabled=false;CoreClient.call("set_profile_note",JSONObject().put("user_id",profile.str("user_id")).put("note",field.text)) { _,e -> save.isEnabled=true;if(e!=null)act.toast(e.message) else {sheet.dismiss();load()} } };sheet.show()
    }
    private fun openSettings() {
        val settings=SettingsPage(act);val column=LinearLayout(act).apply { orientation=LinearLayout.VERTICAL; background=GlassDrawable(act,GlassDrawable.Kind.STRONG,0f) }
        val bar=LinearLayout(act).apply { gravity=Gravity.CENTER_VERTICAL };bar.addView(IconButton(act,R.drawable.ic_back,"Назад").apply {setOnClickListener {act.closePage()}});bar.addView(act.label("Настройки",20f,600));column.addView(bar,lp(MATCH,dp(56)));column.addView(settings.view,lp(MATCH,0,1f))
        act.openPage(object:Screen {override val view=column;override fun applyInsets(top:Int,bottom:Int,ime:Int) {column.setPadding(0,top,0,maxOf(bottom,ime))}})
    }
    private fun select(key:String,animated:Boolean=true) {
        val next = tabKeys.indexOf(key).coerceAtLeast(0)
        val dir = when { next > tabIndex -> 1; next < tabIndex -> -1; else -> 0 }
        tabIndex = next
        category=key;before=null;items.clear();generation++;content.animate().cancel();content.removeAllViews()
        tabs.select(next, animated && dir != 0)
        if(key=="groups") {
            val groups=profile.optJSONArray("groups")?.objects().orEmpty()
            if(groups.isEmpty()) content.addView(act.label("Общих групп пока нет",15f,400,Theme.Role.MUTED))
            groups.forEach { g -> content.addView(action(g.str("title"),R.drawable.ic_people) {act.openChat(g.str("id"))}) }
            reveal(dir, animated)
        } else if(key=="privacy") {
            content.addView(act.label("Сквозное шифрование",18f,600))
            content.addView(act.label(if(profile.optBoolean("me")) "Коды безопасности доступны в профилях ваших собеседников." else profile.str("safety_number"),17f,600,Theme.Role.ACCENT).apply {setTextIsSelectable(true)},lp().apply {topMargin=dp(14)})
            if(!profile.optBoolean("me")) {
                content.addView(act.label("Сравните код с собеседником по другому надёжному каналу.",14f,400,Theme.Role.MUTED),lp().apply {topMargin=dp(12)})
                val changed=profile.optBoolean("key_changed")
                if(changed || !profile.optBoolean("verified")) content.addView(action(if(changed) "Код сверен, принять новый ключ" else "Отметить как сверенный",R.drawable.ic_shield) {
                    CoreClient.call(if(changed) "accept_key_change" else "verify_contact",JSONObject().put("user_id",profile.str("user_id"))) { _,e -> if(e!=null) act.toast(e.message) else load() }
                })
            }
            reveal(dir, animated)
        } else loadItems(dir, animated)
    }
    private fun reveal(dir: Int, animated: Boolean) {
        content.animate().cancel()
        content.stopSprings(Prop.X)
        if (!animated || Theme.reducedMotion) {
            content.alpha = 1f
            content.translationX = 0f
            return
        }
        content.alpha = 0f
        content.translationX = dir * dp(24).toFloat()
        content.springTo(Prop.X, 0f, 0.42f, 0.86f)
        content.animate().alpha(1f).setDuration(Theme.ms(200)).setInterpolator(Theme.surfaceCurve).start()
    }
    private fun loadItems(dir: Int = 0, animated: Boolean = false) {
        val fresh = before == null
        val gen=generation;val p=JSONObject().put("user_id",profile.str("user_id")).put("kind",category).put("limit",48);before?.let {p.put("before",it)}
        CoreClient.call("profile_items",p) { v,e ->
            if(gen!=generation)return@call;if(e!=null)return@call act.toast(e.message)
            val page=(v as? JSONArray)?.objects().orEmpty();items.addAll(page);before=page.lastOrNull()?.optLong("local_id");content.removeAllViews()
            if(items.isEmpty()) content.addView(act.label("Общих ${if(category=="media") "фото" else if(category=="links") "ссылок" else "файлов"} пока нет",15f,400,Theme.Role.MUTED))
            if(category=="media") {
                items.chunked(3).forEach { photos -> val row=LinearLayout(act)
                    photos.forEach { m -> val image=ImageView(act).apply {scaleType=ImageView.ScaleType.CENTER_CROP;setImageBitmap(Photos.thumb(m));contentDescription="Открыть фото";setOnClickListener {showSharedPhoto(act,m)} }
                        val side=(act.resources.displayMetrics.widthPixels-dp(40))/3;row.addView(image,lp(0,side,1f).apply {marginEnd=dp(4)}) }
                    repeat(3-photos.size) {row.addView(View(act),lp(0,1,1f))};content.addView(row,lp().apply {bottomMargin=dp(4)})
                }
            } else items.forEach { m ->
                val url=m.optJSONArray("spans")?.objects()?.firstOrNull {it.has("link")}?.str("link")
                content.addView(action(if(category=="links") url?:m.str("text") else m.optJSONObject("file")?.str("name")?:"Файл",if(category=="links") R.drawable.ic_link else R.drawable.ic_file) {
                    if(category=="links" && url!=null) act.startForResult(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url))) {_,_->}
                    else CoreClient.call("download_file",JSONObject().put("message_id",m.str("id"))) { result,err ->
                        if(err!=null)act.toast(err.message) else {
                            val uri=app.neochat.FilesProvider.uriFor(act,File((result as JSONObject).str("path")))
                            act.startForResult(Intent(Intent.ACTION_VIEW).setDataAndType(uri,m.optJSONObject("file")?.str("mime")?:"application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) {_,_->}
                        }
                    }
                })
            }
            if(page.size==48) content.addView(action("Показать ещё",R.drawable.ic_down) {loadItems()})
            if (fresh) reveal(dir, animated)
        }
    }
}

fun profileQr(act:MainActivity,uid:String) {
    CoreClient.call("profile_qr",JSONObject().put("user_id",uid)) { v,e ->
        if(e!=null)return@call act.toast(e.message)
        val p=v as JSONObject;val sheet=Sheet(act,"QR-код профиля");val card=QrCard(sheet.ctx)
        card.qr.set(p.optJSONArray("qr_rows")?.let {a->(0 until a.length()).map {a.optString(it)}}.orEmpty())
        card.handle.text="@"+p.str("username");sheet.add(card)
        sheet.add(PillButton(sheet.ctx,"Скопировать ссылку",primary=false).apply {setOnClickListener {val clipboard=act.getSystemService(android.content.ClipboardManager::class.java);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NeoChat",p.str("link")));act.toast("Ссылка скопирована")}})
        sheet.show()
    }
}

private fun showSharedPhoto(act:MainActivity,m:JSONObject) {
    CoreClient.call("download_file",JSONObject().put("message_id",m.str("id"))) { v,e -> if(e!=null)act.toast(e.message) else showProfileImages(act,listOf(JSONObject().put("id",m.str("id")).put("path",(v as JSONObject).str("path"))),"",false) {} }
}

fun showProfilePhotos(act:MainActivity,uid:String,own:Boolean,done:()->Unit) {
    CoreClient.call("profile_photos",JSONObject().put("user_id",uid)) { v,e ->
        if(e!=null)return@call act.toast(e.message)
        val photos=(v as? JSONArray)?.objects().orEmpty()
        if(photos.isEmpty()) {if(own)pickFullProfilePhotos(act,done);return@call}
        showProfileImages(act,photos,uid,own,done)
    }
}

private fun showProfileImages(act:MainActivity,photos:List<JSONObject>,uid:String,own:Boolean,done:()->Unit) {
    val root=FrameLayout(act).apply {setBackgroundColor(0xFA040914.toInt());isClickable=true}
    val pager=RecyclerView(act);val layout=LinearLayoutManager(act,LinearLayoutManager.HORIZONTAL,false);pager.layoutManager=layout;PagerSnapHelper().attachToRecyclerView(pager)
    class Page(val image:ZoomImageView):RecyclerView.ViewHolder(image) {var id=""}
    pager.adapter=object:RecyclerView.Adapter<Page>() {
        override fun getItemCount()=photos.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Page(ZoomImageView(act).apply {scaleType=ImageView.ScaleType.FIT_CENTER;layoutParams=RecyclerView.LayoutParams(MATCH,MATCH)})
        override fun onBindViewHolder(holder:Page,position:Int) {
            val photo=photos[position];holder.id=photo.str("id");holder.image.reset()
            fun decode(path:String) {Thread {val bitmap=Photos.decode(path,2732);act.runOnUiThread {if(holder.id==photo.str("id"))holder.image.setImageBitmap(bitmap)}}.start()}
            if(uid.isEmpty())decode(photo.str("path"))
            else CoreClient.call("profile_photo",JSONObject().put("user_id",uid).put("photo_id",photo.str("id"))) { v,e ->if(e!=null)act.toast(e.message) else decode((v as JSONObject).str("path"))}
        }
    }
    root.addView(pager,flp(MATCH,MATCH))
    val counter=act.label("1 из ${photos.size}",14f,600).apply {setTextColor(android.graphics.Color.WHITE);gravity=Gravity.CENTER;setPadding(act.dp(12),act.dp(8),act.dp(12),act.dp(8));background=android.graphics.drawable.GradientDrawable().apply {setColor(0x66000000);cornerRadius=act.dpf(18)}}
    if(photos.size>1) root.addView(counter,flp(WRAP,WRAP,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {topMargin=act.topInset+act.dp(12)})
    pager.addOnScrollListener(object:RecyclerView.OnScrollListener() {override fun onScrolled(rv:RecyclerView,dx:Int,dy:Int) {val pos=layout.findFirstCompletelyVisibleItemPosition();if(pos>=0)counter.text="${pos+1} из ${photos.size}"}})
    var closed=false
    val back:()->Boolean={if(!closed){closed=true;(root.parent as?ViewGroup)?.removeView(root)};true}
    val close=IconButton(act,R.drawable.ic_back,"Назад",glass=true);close.setOnClickListener {act.popBack(back);back()};root.addView(close,flp(act.dp(48),act.dp(48),Gravity.TOP or Gravity.START).apply {topMargin=act.topInset+act.dp(8);marginStart=act.dp(12)})
    if(own) {
        val edit=IconButton(act,R.drawable.ic_edit,"Изменить фото профиля",glass=true)
        edit.setOnClickListener {val index=layout.findFirstVisibleItemPosition().coerceAtLeast(0);PopupMenu(act,edit).add("Добавить фото") {act.popBack(back);back();pickFullProfilePhotos(act,done)}.add("Удалить это фото",danger=true) {CoreClient.call("delete_profile_photo",JSONObject().put("photo_id",photos[index].str("id"))) { _,e ->if(e!=null)act.toast(e.message) else {act.popBack(back);back();done()}}}.show()}
        root.addView(edit,flp(act.dp(48),act.dp(48),Gravity.TOP or Gravity.END).apply {topMargin=act.topInset+act.dp(8);marginEnd=act.dp(12)})
    }
    act.overlay.addView(root,flp(MATCH,MATCH));act.pushBack(back);root.alpha=0f;root.animate().alpha(1f).setDuration(Theme.ms(180)).start()
}

private fun pickFullProfilePhotos(act:MainActivity,done:()->Unit) {
    val pick=Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
    act.startForResult(pick) { code,data ->
        if(code!=Activity.RESULT_OK)return@startForResult
        val uris=if(data?.clipData!=null)(0 until data.clipData!!.itemCount).map {data.clipData!!.getItemAt(it).uri}.take(10) else listOfNotNull(data?.data)
        fun upload(index:Int) {
            if(index>=uris.size){done();return}
            Thread {
                val input=File.createTempFile("profile-source-",".img",act.cacheDir);val output=File.createTempFile("profile-",".jpg",act.cacheDir)
                val ok=runCatching {act.contentResolver.openInputStream(uris[index])!!.use {r->input.outputStream().use {w->val b=ByteArray(65536);var n:Int;var total=0;while(r.read(b).also {n=it}>=0){total+=n;require(total<=25*1024*1024);w.write(b,0,n)}}};val image=Photos.decode(input.absolutePath,4096)?:error("image");output.outputStream().use {require(image.compress(Bitmap.CompressFormat.JPEG,90,it))};require(output.length()<=8*1024*1024)}.isSuccess
                input.delete();act.runOnUiThread {if(!ok){output.delete();act.toast("Не удалось подготовить фото до 8 МБ")}else CoreClient.call("set_profile_photo",JSONObject().put("path",output.absolutePath)) { _,e->output.delete();if(e!=null)act.toast(e.message) else upload(index+1)}}
            }.start()
        };upload(0)
    }
}
