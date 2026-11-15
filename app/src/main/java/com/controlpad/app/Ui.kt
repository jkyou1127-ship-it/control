package com.controlpad.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/**
 * 새 UI(Uwe) 화면 모양: 둥근 카드, 납작한 버튼, 테마 색을 따르는 강조색.
 * 화면을 만든 뒤 restyle 로 한꺼번에 입히므로, 각 화면은 평소처럼 기본 위젯으로 만들면 된다.
 */
object Ui {
    const val SECTION = "ui-section"       // 이 태그가 붙은 제목은 SectionedLayout 에서 새 카드를 연다
    const val PRIMARY = "ui-primary"       // 이 태그가 붙은 버튼은 강조색으로 채운다

    fun accent(t: PadTheme = Themes.current): Int = if (t.light) 0xFF0277BD.toInt() else 0xFF4FC3F7.toInt()

    /** 배경 위에 살짝 떠 보이는 면(카드·버튼 바탕). */
    fun surface(t: PadTheme = Themes.current): Int = t.card

    fun line(t: PadTheme = Themes.current): Int = t.text and 0x00FFFFFF or 0x26000000

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = ctx.dp(radiusDp).toFloat()
            if (stroke != 0) setStroke(ctx.dp(1), stroke)
        }

    private fun ripple(ctx: Context, base: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(accent() and 0x00FFFFFF or 0x44000000), base, null)

    /** 화면(또는 대화상자) 안의 위젯을 새 UI 모양으로 바꾼다. */
    fun restyle(view: View?) {
        if (view == null) return
        val ctx = view.context
        val t = Themes.current
        when (view) {
            is android.widget.Switch -> {
                // 켜짐은 강조색, 꺼짐은 회색(버튼 모양을 입히지 않는다)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                view.thumbTintList = ColorStateList(states, intArrayOf(accent(), 0xFFBDBDBD.toInt()))
                view.trackTintList = ColorStateList(states, intArrayOf(accent() and 0x00FFFFFF or 0x88000000.toInt(), line()))
            }
            is CheckBox, is RadioButton -> {
                view as android.widget.CompoundButton
                view.buttonTintList = ColorStateList.valueOf(accent())
                view.setTextColor(t.text)
                view.minHeight = ctx.dp(40)
                view.minWidth = 0; view.minimumWidth = 0
            }
            is Button -> {
                val primary = view.tag == PRIMARY
                view.isAllCaps = false
                view.stateListAnimator = null
                view.background = ripple(ctx, rounded(ctx, if (primary) accent() else surface(), 12, if (primary) 0 else line()))
                view.setTextColor(if (primary) (if (Themes.luminance(accent()) > 0.5f) 0xFF0B1A24.toInt() else 0xFFFFFFFF.toInt()) else t.text)
                view.minHeight = ctx.dp(42); view.minimumHeight = ctx.dp(42)
                // 기본 버튼의 최소 폭(64dp 남짓)을 없애고 옆 여백을 줄여, 좁은 줄에서 버튼이 밀려나 잘리지 않게 한다.
                view.minWidth = 0; view.minimumWidth = 0
                view.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3)); view.layoutParams = it }
            }
            is EditText -> {
                view.background = rounded(ctx, surface(), 12, line())
                view.setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                view.setTextColor(t.text)
                view.setHintTextColor(t.text and 0x00FFFFFF or 0x77000000)
            }
            is Spinner -> view.background = rounded(ctx, surface(), 12, line())
            is SeekBar -> {
                view.progressTintList = ColorStateList.valueOf(accent())
                view.thumbTintList = ColorStateList.valueOf(accent())
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) restyle(view.getChildAt(i))
    }

    /** 화면 맨 위 제목 줄(큰 제목과 작은 설명). */
    fun header(ctx: Context, title: String, subtitle: String? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ctx.dp(10))
            addView(TextView(ctx).apply {
                text = title; textSize = 26f; setTextColor(Themes.current.text)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            if (subtitle != null) addView(TextView(ctx).apply {
                text = subtitle; textSize = 13f; setTextColor(Themes.current.text and 0x00FFFFFF or 0x99000000.toInt())
            })
        }

    /** 설정 화면들의 탭: 설정 · 기능 · 실험실(알파만) · 업데이트. 누르면 그 화면으로 바로 바꾼다(뒤로 가기는 목록으로). */
    fun tabList(): List<Pair<String, Class<*>>> = buildList {
        add("설정" to SettingsActivity::class.java)
        add("기능" to FeaturesActivity::class.java)
        if (Updater.alpha) add("실험실" to LabsActivity::class.java)
        add("업데이트" to UpdateActivity::class.java)
    }

    fun tabs(a: android.app.Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = a.dp(4)
        setPadding(pad, pad, pad, pad)
        background = rounded(a, surface(), 14, line())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, a.dp(12)) }
        for ((label, cls) in tabList()) {
            val on = cls == a.javaClass
            addView(TextView(a).apply {
                text = label; textSize = 15f; gravity = android.view.Gravity.CENTER
                typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setTextColor(if (on) onAccent() else Themes.current.text)
                if (on) background = rounded(a, accent(), 10)
                setPadding(0, a.dp(10), 0, a.dp(10))
                isClickable = true
                if (!on) setOnClickListener {
                    if (cls == LabsActivity::class.java) Labs.refresh()   // 실험실은 열기 전에 최신 상태를 받아 둔다
                    a.startActivity(android.content.Intent(a, cls).addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    a.finish()
                    @Suppress("DEPRECATION") a.overridePendingTransition(0, 0)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** 강조색 위의 글자 색. */
    fun onAccent(): Int = if (Themes.luminance(accent()) > 0.5f) 0xFF0B1A24.toInt() else 0xFFFFFFFF.toInt()

    /** 흐린 글자 색(설명용). */
    fun muted(): Int = Themes.current.text and 0x00FFFFFF or 0x99000000.toInt()

    /**
     * 카드 격자: 레이아웃 목록처럼 화면 너비에 따라 한 줄에 카드를 여러 개 놓는다.
     * 마지막 줄이 덜 차면 빈 칸으로 채워 카드 너비를 맞춘다.
     */
    fun grid(ctx: Context, cards: List<View>, minWidthDp: Int = 260): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val widthDp = ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density
        val columns = ((widthDp - 32) / minWidthDp).toInt().coerceIn(1, 4)
        var line: LinearLayout? = null
        cards.forEachIndexed { i, card ->
            if (i % columns == 0) {
                line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })
            }
            line!!.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i % columns != 0) leftMargin = ctx.dp(12) })
        }
        val rest = (columns - cards.size % columns) % columns
        repeat(rest) { line?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ctx.dp(12) }) }
    }

    /**
     * 기능 카드: 왼쪽 위 아이콘 칸, 오른쪽 위 스위치(또는 상태 표시), 아래에 제목과 설명.
     * on 이 null 이면 스위치 없이 badge 만 보인다. 카드를 누르면 onTap.
     */
    fun featureCard(
        ctx: Context, icon: String, title: String, note: String, on: Boolean?, badge: String? = null,
        enabled: Boolean = true, onTap: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val lit = on == true
        background = rounded(ctx, surface(), 18, if (lit) accent() and 0x00FFFFFF or 0x99000000.toInt() else line())
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        alpha = if (enabled) 1f else 0.55f
        val top = LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(TextView(ctx).apply {
            text = icon; textSize = 20f; gravity = android.view.Gravity.CENTER
            background = rounded(ctx, if (lit) accent() and 0x00FFFFFF or 0x40000000 else Themes.current.bg, 12)
        }, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(42)))
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (badge != null) top.addView(TextView(ctx).apply {
            text = badge; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(accent())
            setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3))
            background = rounded(ctx, 0, 10, accent())
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ctx.dp(6) })
        val sw = if (on != null) android.widget.Switch(ctx).apply { isChecked = on; isClickable = false; isFocusable = false } else null
        if (sw != null) top.addView(sw)
        addView(top)
        addView(TextView(ctx).apply {
            text = title; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
            setPadding(0, ctx.dp(12), 0, 0)
        })
        if (note.isNotEmpty()) addView(TextView(ctx).apply {
            text = note; textSize = 12.5f; setTextColor(muted()); setLineSpacing(0f, 1.15f); setPadding(0, ctx.dp(4), 0, 0)
        })
        if (onTap != null && enabled) {
            isClickable = true
            foreground = RippleDrawable(ColorStateList.valueOf(accent() and 0x00FFFFFF or 0x33000000), null, rounded(ctx, -1, 18))
            setOnClickListener { onTap() }
        }
    }

    /** 묶음 제목. SectionedLayout 에 넣으면 새 카드가 열린다. */
    fun section(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title; textSize = 13f; tag = SECTION
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(accent())
        setPadding(0, 0, 0, ctx.dp(6))
        // NES3: 제목 왼쪽에 강조색 막대
        val bar = rounded(ctx, accent(), 2).apply { setSize(ctx.dp(4), ctx.dp(14)) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null)
        compoundDrawablePadding = ctx.dp(8)
    }
}

/**
 * Ui.SECTION 태그가 붙은 제목을 넣으면 둥근 카드를 하나 열고, 다음 제목이 올 때까지 넣는 것을 그 카드에 담는다.
 * 설정처럼 길게 이어지는 화면을 묶음별 카드로 보여 줄 때 쓴다.
 */
class SectionedLayout(ctx: Context) : LinearLayout(ctx) {
    private var card: LinearLayout? = null

    init { orientation = VERTICAL }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (child.tag == Ui.SECTION) {
            val c = LinearLayout(context).apply {
                orientation = VERTICAL
                background = Ui.rounded(context, Ui.surface(), 20, Ui.line())
                setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(12))
            }
            super.addView(c, -1, LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
            c.addView(child)
            card = c
            return
        }
        val target = card
        if (target != null) target.addView(child, params) else super.addView(child, index, params)
    }
}
