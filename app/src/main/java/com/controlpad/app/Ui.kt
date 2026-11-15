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
            is CheckBox, is RadioButton -> {
                view as android.widget.CompoundButton
                view.buttonTintList = ColorStateList.valueOf(accent())
                view.setTextColor(t.text)
                view.minHeight = ctx.dp(40)
            }
            is Button -> {
                val primary = view.tag == PRIMARY
                view.isAllCaps = false
                view.stateListAnimator = null
                view.background = ripple(ctx, rounded(ctx, if (primary) accent() else surface(), 12, if (primary) 0 else line()))
                view.setTextColor(if (primary) (if (Themes.luminance(accent()) > 0.5f) 0xFF0B1A24.toInt() else 0xFFFFFFFF.toInt()) else t.text)
                view.minHeight = ctx.dp(42); view.minimumHeight = ctx.dp(42)
                view.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
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

    /** 설정 화면들의 탭: 설정 · 기능 · 업데이트. 누르면 그 화면으로 바로 바꾼다(뒤로 가기는 목록으로). */
    val TABS = listOf("설정" to SettingsActivity::class.java, "기능 · 기타" to FeaturesActivity::class.java, "업데이트" to UpdateActivity::class.java)

    fun tabs(a: android.app.Activity, selected: Int): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = a.dp(4)
        setPadding(pad, pad, pad, pad)
        background = rounded(a, surface(), 14, line())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, a.dp(12)) }
        TABS.forEachIndexed { i, (label, cls) ->
            val on = i == selected
            addView(TextView(a).apply {
                text = label; textSize = 15f; gravity = android.view.Gravity.CENTER
                typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setTextColor(if (on) (if (Themes.luminance(accent()) > 0.5f) 0xFF0B1A24.toInt() else 0xFFFFFFFF.toInt()) else Themes.current.text)
                if (on) background = rounded(a, accent(), 10)
                setPadding(0, a.dp(10), 0, a.dp(10))
                isClickable = true
                if (!on) setOnClickListener {
                    a.startActivity(android.content.Intent(a, cls).addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    a.finish()
                    @Suppress("DEPRECATION") a.overridePendingTransition(0, 0)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** 묶음 제목. SectionedLayout 에 넣으면 새 카드가 열린다. */
    fun section(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title; textSize = 13f; tag = SECTION
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(accent())
        setPadding(0, 0, 0, ctx.dp(6))
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
