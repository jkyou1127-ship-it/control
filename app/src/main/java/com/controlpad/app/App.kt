package com.controlpad.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Link.init(this)
    }
}

/** 화면이 앞으로 올 때마다 PC 연결을 다시 확인하는 공통 액티비티. */
open class BaseActivity : Activity() {
    private var shownTheme = -1

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // 테마는 화면을 만들기 전에 정해야 글자·대화상자 색까지 함께 바뀐다.
        val t = Themes.current
        shownTheme = Themes.revision
        setTheme(if (t.light) R.style.AppThemeLight else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(t.bg))
        window.statusBarColor = t.bg
        window.navigationBarColor = t.bg
    }

    override fun onStart() {
        super.onStart()
        if (shownTheme != Themes.revision) { recreate(); return }   // 설정에서 테마를 바꾸고 돌아온 경우
        Link.ensure()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** 편집 화면과 연주 화면이 같은 모양으로 버튼을 그리도록 공유한다. */
class PadPainter(ctx: Context, private val theme: PadTheme = Themes.current) {
    private val density = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val keyNames = Link.showKeyNames

    /** angle 은 노브가 지금까지 돌아간 각도(표시용). */
    private val clip = android.graphics.Path()

    /** parts 는 눌린 부분의 비트(1 shl MousePart.x). */
    private fun drawMouse(c: Canvas, b: PadButton, r: RectF, selected: Boolean, parts: Int) {
        val rad = minOf(r.width(), r.height()) * 0.3f
        val top = r.top + r.height() * MousePart.TOP
        val x1 = r.left + r.width() * MousePart.WHEEL_FROM
        val x2 = r.left + r.width() * MousePart.WHEEL_TO
        clip.reset(); clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
        c.save(); c.clipPath(clip)
        fill.color = shade(b.color, 0.22f); c.drawRect(r, fill)
        fill.color = b.color
        if (parts and (1 shl MousePart.LEFT) != 0) c.drawRect(r.left, r.top, x1, top, fill)
        if (parts and (1 shl MousePart.RIGHT) != 0) c.drawRect(x2, r.top, r.right, top, fill)
        fill.color = if (parts and (1 shl MousePart.WHEEL) != 0) b.color else shade(b.color, 0.6f)
        val wr = (x2 - x1) * 0.28f
        c.drawRoundRect(x1 + (x2 - x1) * 0.22f, r.top + (top - r.top) * 0.18f, x2 - (x2 - x1) * 0.22f, top - (top - r.top) * 0.18f, wr, wr, fill)
        c.restore()
        stroke.color = if (selected) theme.text else b.color
        stroke.strokeWidth = density * if (selected) 3f else theme.stroke
        c.drawRoundRect(r, rad, rad, stroke)
        stroke.strokeWidth = density
        c.drawLine(r.left, top, r.right, top, stroke)
        c.drawLine(x1, r.top, x1, top, stroke)
        c.drawLine(x2, r.top, x2, top, stroke)
        sub.color = theme.text and 0x00FFFFFF or 0x88000000.toInt()
        sub.textSize = 13f * density
        c.drawText(b.label.ifEmpty { "마우스" }, r.centerX(), (top + r.bottom) / 2 + sub.textSize * 0.4f, sub)
    }

    fun draw(c: Canvas, b: PadButton, r: RectF, pressed: Boolean, selected: Boolean, angle: Float = 0f, parts: Int = 0) {
        if (b.isMouse) { drawMouse(c, b, r, selected, parts); return }
        fill.color = if (pressed && !b.isPad) b.color else shade(b.color, if (b.isPad) 0.18f else 0.38f)
        stroke.color = if (selected) theme.text else b.color
        stroke.strokeWidth = density * if (selected) 3f else theme.stroke
        val rad = minOf(r.width(), r.height()) * 0.14f
        if (b.shape == PadButton.SHAPE_OVAL || b.isKnob) {
            c.drawOval(r, fill); c.drawOval(r, stroke)
        } else {
            c.drawRoundRect(r, rad, rad, fill); c.drawRoundRect(r, rad, rad, stroke)
        }

        if (b.isKnob) {
            // 돌아간 정도를 보여 주는 표시점
            val rad2 = Math.toRadians(angle.toDouble() - 90)
            fill.color = if (pressed) Color.BLACK else if (theme.colorText) theme.text else b.color
            c.drawCircle(
                r.centerX() + (Math.cos(rad2) * r.width() * 0.36).toFloat(),
                r.centerY() + (Math.sin(rad2) * r.height() * 0.36).toFloat(),
                minOf(r.width(), r.height()) * 0.07f, fill,
            )
        }

        if (b.isPad) {
            sub.color = theme.text and 0x00FFFFFF or 0x88000000.toInt()
            sub.textSize = 13f * density
            c.drawText(b.label.ifEmpty { "트랙패드" }, r.centerX(), r.top + sub.textSize * 1.8f, sub)
            return
        }

        val combo = if (b.isKnob) Knob.describe(b) else Keys.combo(b.keys)
        val label = b.label.ifEmpty { if (b.isKnob) "노브" else combo.ifEmpty { "키 없음" } }
        text.color = if (pressed) Color.BLACK else if (theme.colorText) b.color else theme.text
        text.textSize = (r.height() * 0.3f)
            .coerceAtMost(r.width() * (if (b.isKnob) 0.9f else 1.5f) / label.length.coerceAtLeast(2))
            .coerceAtMost(if (b.isKnob) r.height() * 0.16f else Float.MAX_VALUE)
            .coerceIn(9f * density, 56f * density)
        val cy = r.centerY() - (text.descent() + text.ascent()) / 2
        c.drawText(label, r.centerX(), cy, text)

        if (keyNames && combo.isNotEmpty() && combo != label && r.height() > 56 * density) {
            sub.color = if (pressed) 0xAA000000.toInt() else theme.text and 0x00FFFFFF or 0xAA000000.toInt()
            sub.textSize = (text.textSize * 0.45f).coerceIn(9f * density, 16f * density)
            val sy = if (b.isKnob) cy + sub.textSize * 1.5f else r.bottom - sub.textSize * 0.7f
            c.drawText(combo, r.centerX(), sy, sub)
        }
    }

    /** 버튼 색을 테마의 바탕색 쪽으로 섞는다. f 가 클수록 원래 색에 가깝다. */
    private fun shade(color: Int, f: Float): Int {
        val k = (f * theme.strength).coerceIn(0f, 1f)
        fun mix(c: Int, base: Int) = (base + (c - base) * k).toInt()
        return Color.rgb(
            mix(Color.red(color), Color.red(theme.base)),
            mix(Color.green(color), Color.green(theme.base)),
            mix(Color.blue(color), Color.blue(theme.base)),
        )
    }
}

/** 컨트롤러 화면(편집·실행)의 색 구성. */
class PadTheme(
    val name: String,
    val bg: Int,          // 화면 배경
    val base: Int,        // 눌리지 않은 버튼이 섞여 들어가는 바탕색
    val strength: Float,  // 눌리지 않은 버튼의 색 진하기 배율
    val text: Int,        // 글자, 선택 테두리, 격자
    val stroke: Float,    // 테두리 굵기(dp)
    val colorText: Boolean = false,  // 글자를 버튼 색으로 쓸지
    val light: Boolean = false,      // 밝은 테마(목록·설정·대화상자도 밝게)
    val card: Int = 0xFF1E2128.toInt(),  // 목록 항목 배경
)

object Themes {
    private fun c(v: Long) = v.toInt()

    val all = listOf(
        PadTheme("다크 (기본)", c(0xFF14161A), Color.BLACK, 1f, Color.WHITE, 1.5f),
        PadTheme("블랙", Color.BLACK, Color.BLACK, 0.6f, Color.WHITE, 1.5f, card = c(0xFF101010)),
        PadTheme("라이트", c(0xFFECEFF1), Color.WHITE, 1.2f, c(0xFF202124), 1.5f, light = true, card = Color.WHITE),
        PadTheme("네온", c(0xFF05060A), Color.BLACK, 0.25f, Color.WHITE, 3f, colorText = true, card = c(0xFF10131C)),
        PadTheme("미드나잇", c(0xFF0B1026), c(0xFF050814), 1f, c(0xFFE3E8FF), 1.5f, card = c(0xFF141B3A)),
        PadTheme("포레스트", c(0xFF0E1A12), c(0xFF07100A), 1f, c(0xFFE4F2E7), 1.5f, card = c(0xFF16281C)),
        PadTheme("와인", c(0xFF1F0D14), c(0xFF12060B), 1f, c(0xFFFBE4EC), 1.5f, card = c(0xFF2E141E)),
        PadTheme("선셋", c(0xFF24140C), c(0xFF140A05), 1.1f, c(0xFFFFE9D6), 1.5f, card = c(0xFF36200F)),
        PadTheme("오션", c(0xFF06202A), c(0xFF031218), 1f, c(0xFFDDF6FF), 1.5f, card = c(0xFF0C303D)),
        PadTheme("그레이", c(0xFF2B2D31), c(0xFF1A1B1E), 1.1f, c(0xFFF2F3F5), 1.5f, card = c(0xFF383A40)),
        PadTheme("사이버펑크", c(0xFF0D0221), c(0xFF0D0221), 0.3f, c(0xFFFDF500), 3f, colorText = true, card = c(0xFF1A0B3B)),
        PadTheme("라벤더", c(0xFFEDE7F6), Color.WHITE, 1.2f, c(0xFF311B92), 1.5f, light = true, card = Color.WHITE),
        PadTheme("민트", c(0xFFE0F2F1), Color.WHITE, 1.2f, c(0xFF004D40), 1.5f, light = true, card = Color.WHITE),
        PadTheme("벚꽃", c(0xFFFCE4EC), Color.WHITE, 1.2f, c(0xFF880E4F), 1.5f, light = true, card = Color.WHITE),
    )

    /** 고를 수 있는 이름들. 마지막은 직접 만드는 커스텀 테마. */
    val names: List<String> get() = all.map { it.name } + "커스텀"

    /** 테마가 바뀔 때마다 올라간다. 떠 있는 화면이 다시 그려야 하는지 판단하는 데 쓴다. */
    var revision = 0
        private set

    private val prefs get() = Link.prefs
    private fun put(edit: (android.content.SharedPreferences.Editor) -> Unit) {
        prefs.edit().also(edit).apply()
        revision++
    }

    var index: Int
        get() = prefs.getInt("theme", 0).coerceIn(0, all.size)
        set(i) = put { it.putInt("theme", i) }

    val isCustom: Boolean get() = index == all.size
    val hasCustom: Boolean get() = prefs.contains("cBg")

    var customBg: Int
        get() = prefs.getInt("cBg", all[0].bg)
        set(v) = put { it.putInt("cBg", v or 0xFF000000.toInt()) }
    var customText: Int
        get() = prefs.getInt("cText", Color.WHITE)
        set(v) = put { it.putInt("cText", v or 0xFF000000.toInt()) }
    var customStrength: Float
        get() = prefs.getFloat("cStrength", 1f)
        set(v) = put { it.putFloat("cStrength", v) }
    var customStroke: Float
        get() = prefs.getFloat("cStroke", 1.5f)
        set(v) = put { it.putFloat("cStroke", v) }
    var customColorText: Boolean
        get() = prefs.getBoolean("cColorText", false)
        set(v) = put { it.putBoolean("cColorText", v) }

    /** 커스텀을 처음 고를 때, 쓰고 있던 테마에서 시작하도록 값을 복사한다. */
    fun copyToCustom(t: PadTheme) = put {
        it.putInt("cBg", t.bg).putInt("cText", t.text).putFloat("cStrength", t.strength)
            .putFloat("cStroke", t.stroke).putBoolean("cColorText", t.colorText)
    }

    fun custom(bg: Int, text: Int, strength: Float, stroke: Float, colorText: Boolean): PadTheme {
        val light = luminance(bg) > 0.5f
        return PadTheme(
            "커스텀", bg, if (light) Color.WHITE else Color.BLACK, strength, text, stroke, colorText,
            light, mix(bg, text, 0.09f),
        )
    }

    val current: PadTheme
        get() = if (isCustom) custom(customBg, customText, customStrength, customStroke, customColorText) else all[index]

    // android.graphics.Color 의 함수는 단위 테스트에서 쓸 수 없어 비트 연산으로 직접 계산한다.
    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF

    fun luminance(color: Int): Float = (0.299f * r(color) + 0.587f * g(color) + 0.114f * b(color)) / 255f

    /** x 에 y 를 f 만큼 섞는다. */
    fun mix(x: Int, y: Int, f: Float): Int {
        fun ch(p: Int, q: Int) = (p + (q - p) * f).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (ch(r(x), r(y)) shl 16) or (ch(g(x), g(y)) shl 8) or ch(b(x), b(y))
    }
}

/** 빨강·초록·파랑 슬라이더로 색을 고르는 창. */
fun pickColor(a: Activity, initial: Int, done: (Int) -> Unit) {
    val m = a.dp(20)
    val box = android.widget.LinearLayout(a).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setPadding(m, a.dp(12), m, 0)
    }
    val preview = android.view.View(a)
    val hex = android.widget.TextView(a).apply { gravity = android.view.Gravity.CENTER; setPadding(0, a.dp(6), 0, a.dp(6)) }
    box.addView(preview, android.widget.LinearLayout.LayoutParams(-1, a.dp(56)))
    box.addView(hex)
    val bars = listOf("빨강" to Color.red(initial), "초록" to Color.green(initial), "파랑" to Color.blue(initial)).map { (name, v) ->
        box.addView(android.widget.TextView(a).apply { text = name })
        android.widget.SeekBar(a).apply { max = 255; progress = v }.also { box.addView(it) }
    }
    fun color() = Color.rgb(bars[0].progress, bars[1].progress, bars[2].progress)
    fun show() {
        preview.setBackgroundColor(color())
        hex.text = "#%06X".format(color() and 0xFFFFFF)
    }
    show()
    for (bar in bars) bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) = show()
        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
    })
    android.app.AlertDialog.Builder(a).setTitle("색 고르기").setView(box)
        .setPositiveButton("확인") { _, _ -> done(color()) }
        .setNegativeButton("취소", null).show()
}

/** 설정 화면에서 지금 테마로 버튼이 어떻게 보이는지 보여 준다. */
class ThemePreview(ctx: Context) : android.view.View(ctx) {
    private val theme = Themes.current
    private val painter = PadPainter(ctx, theme)
    private val rect = RectF()
    private val samples = listOf(
        PadButton(0f, 0f, 0f, 0f, "D", mutableListOf(0x07), color = PadButton.PALETTE[0]),
        PadButton(0f, 0f, 0f, 0f, "F", mutableListOf(0x09), color = PadButton.PALETTE[2]),
        PadButton(0f, 0f, 0f, 0f, "저장", mutableListOf(0xE0, 0x16), color = PadButton.PALETTE[1]),
        PadButton(0f, 0f, 0f, 0f, "VOL", color = PadButton.PALETTE[3], type = PadButton.TYPE_KNOB),
    )

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.bg)
        val gap = height * 0.1f
        val w = (width - gap * (samples.size + 1)) / samples.size
        samples.forEachIndexed { i, b ->
            val left = gap + i * (w + gap)
            if (b.isKnob) {
                val side = minOf(w, height - gap * 2)
                rect.set(left + (w - side) / 2, gap, left + (w - side) / 2 + side, gap + side)
            } else rect.set(left, gap, left + w, height - gap)
            painter.draw(c, b, rect, i == 1, false)   // 두 번째는 눌린 모습
        }
    }
}

/** 자식들을 왼쪽부터 놓다가 폭이 모자라면 다음 줄로 넘긴다. 대화상자 폭이 기기마다 달라도 잘리지 않는다. */
class FlowLayout(ctx: Context) : android.view.ViewGroup(ctx) {
    private val gap = ctx.dp(6)

    /** 주어진 폭 안에서 자식들의 위치를 정하고 전체 높이를 돌려준다. place 가 참이면 실제로 배치한다. */
    private fun arrange(width: Int, place: Boolean): Int {
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            if (place) c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowHeight = maxOf(rowHeight, c.measuredHeight)
        }
        return y + rowHeight
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val unbounded = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
        val width = if (unbounded) resources.displayMetrics.widthPixels else MeasureSpec.getSize(widthSpec)
        for (i in 0 until childCount) {
            measureChild(getChildAt(i), MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightSpec)
        }
        setMeasuredDimension(width, arrange(width, false))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        arrange(r - l, true)
    }
}

/** 대화상자를 화면 폭에 가깝게 넓힌다(기본 폭은 태블릿에서 좁아 내용이 잘린다). */
fun android.app.AlertDialog.wide(): android.app.AlertDialog {
    window?.setLayout((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    return this
}
