package app.neochat.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.neochat.R

/** Appearance and accessibility settings. */
class CustomizationPage(private val act: MainActivity) : Screen {
    private val ctx: Context = act
    private val prefs = Prefs.of(ctx)
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassDrawable(ctx, GlassDrawable.Kind.STRONG, 0f, rim = false)
    }
    override val view: View = root
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
    }
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(n: Int) = ctx.dp(n)

    init {
        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = IconButton(ctx, R.drawable.ic_back, "Назад")
        back.setOnClickListener { act.closePage() }
        bar.addView(back, lp(dp(56), dp(56)))
        bar.addView(ctx.label("Кастомизация", 20f, 700), lp(WRAP, WRAP))
        root.addView(bar, lp(MATCH, dp(56)))
        root.addView(scroll, lp(MATCH, 0, 1f))
        scroll.addView(col, lp(MATCH, WRAP))
        build()
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        root.setPadding(0, top, 0, maxOf(bottom, ime))
    }

    private fun section(heading: String): LinearLayout {
        col.addView(
            ctx.label(heading, 13.5f, 600, Theme.Role.MUTED).apply { heading() },
            lp(MATCH, WRAP).apply { setMargins(dp(30), dp(20), dp(20), dp(7)) },
        )
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(ctx, radius = ctx.dpf(24))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        col.addView(container, lp(MATCH, WRAP).apply { setMargins(dp(16), 0, dp(16), 0) })
        return container
    }

    private fun item(title: String, description: String, onClick: (View) -> Unit): Pair<View, TextView> {
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(67)
            setPadding(dp(14), dp(8), dp(12), dp(8))
            background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(16), rim = false)
            isClickable = true
            isFocusable = true
            contentDescription = title + ". " + description
            pressFeedback()
        }
        val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(ctx.label(title, 15.5f, 600), lp(MATCH, WRAP))
        labels.addView(ctx.label(description, 12.5f, 400, Theme.Role.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(3) })
        row.addView(labels, lp(0, WRAP, 1f))
        val trailing = ctx.label("", 14f, 700, Theme.Role.ACCENT)
        row.addView(trailing, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        row.setOnClickListener { onClick(row) }
        return row to trailing
    }

    private fun build() {
        val themes = section("ТЕМА И ОФОРМЛЕНИЕ")
        themes.addView(ctx.label("Режим отображения", 13f, 500, Theme.Role.MUTED).apply {
            setPadding(dp(14), dp(6), 0, 0)
        }, lp(MATCH, WRAP))
        val mode = ThemeSlider(ctx)
        mode.setValue(Theme.mode.ordinal, false)
        mode.onChange = { Theme.setMode(ctx, Theme.Mode.entries[it]) }
        themes.addView(mode, lp(MATCH, dp(82)).apply { setMargins(dp(6), 0, dp(6), 0) })
        val appearance = item("Оформление интерфейса", "Готовые палитры, собственные темы и цвета") {
            Appearance.show(act)
        }
        themes.addView(appearance.first, lp(MATCH, WRAP))
        val sizes = listOf("Мелкий", "Обычный", "Крупный", "Очень крупный")
        val textSize = item("Размер текста", "Размер сообщений в переписке") { anchor ->
            val menu = PopupMenu(act, anchor)
            sizes.forEachIndexed { i, name ->
                menu.add(name, checked = i == prefs.getInt("text_size", 1)) {
                    prefs.edit().putInt("text_size", i).apply()
                    textSizeValue.text = name
                }
            }
            menu.show()
        }
        // Retain the existing font-size preference while moving the theme settings.
        textSizeValue = textSize.second
        textSize.second.text = sizes[prefs.getInt("text_size", 1).coerceIn(0, 3)]
        themes.addView(textSize.first, lp(MATCH, WRAP))
        val bigEmoji = ToggleRow(ctx, "Крупные эмодзи без текста")
        bigEmoji.setChecked(prefs.getBoolean("big_emoji", true), false)
        bigEmoji.onToggle = { prefs.edit().putBoolean("big_emoji", it).apply() }
        themes.addView(bigEmoji, lp(MATCH, WRAP))

        val motion = section("ДВИЖЕНИЕ")
        val reduce = ToggleRow(ctx, "Уменьшить анимацию")
        reduce.setChecked(prefs.getBoolean("reduce_motion", false), false)
        reduce.onToggle = {
            prefs.edit().putBoolean("reduce_motion", it).apply()
            Theme.forceReduced = it
        }
        motion.addView(reduce, lp(MATCH, WRAP))
        col.setPadding(0, 0, 0, dp(24))
    }

    private lateinit var textSizeValue: TextView
}
