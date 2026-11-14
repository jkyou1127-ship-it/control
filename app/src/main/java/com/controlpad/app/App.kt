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
    override fun onStart() {
        super.onStart()
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

        if (combo.isNotEmpty() && combo != label && r.height() > 56 * density) {
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
)

object Themes {
    val all = listOf(
        PadTheme("다크 (기본)", 0xFF14161A.toInt(), Color.BLACK, 1f, Color.WHITE, 1.5f),
        PadTheme("블랙", Color.BLACK, Color.BLACK, 0.6f, Color.WHITE, 1.5f),
        PadTheme("라이트", 0xFFECEFF1.toInt(), Color.WHITE, 1.2f, 0xFF202124.toInt(), 1.5f),
        PadTheme("네온", 0xFF05060A.toInt(), Color.BLACK, 0.25f, Color.WHITE, 3f, colorText = true),
    )

    var index: Int
        get() = Link.prefs.getInt("theme", 0).coerceIn(0, all.size - 1)
        set(i) = Link.prefs.edit().putInt("theme", i).apply()

    val current: PadTheme get() = all[index]
}
