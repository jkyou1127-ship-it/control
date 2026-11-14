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
class PadPainter(ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** angle 은 노브가 지금까지 돌아간 각도(표시용). */
    fun draw(c: Canvas, b: PadButton, r: RectF, pressed: Boolean, selected: Boolean, angle: Float = 0f) {
        fill.color = if (pressed && !b.isPad) b.color else shade(b.color, if (b.isPad) 0.18f else 0.38f)
        stroke.color = if (selected) Color.WHITE else b.color
        stroke.strokeWidth = density * if (selected) 3f else 1.5f
        val rad = minOf(r.width(), r.height()) * 0.14f
        if (b.shape == PadButton.SHAPE_OVAL || b.isKnob) {
            c.drawOval(r, fill); c.drawOval(r, stroke)
        } else {
            c.drawRoundRect(r, rad, rad, fill); c.drawRoundRect(r, rad, rad, stroke)
        }

        if (b.isKnob) {
            // 돌아간 정도를 보여 주는 표시점
            val rad2 = Math.toRadians(angle.toDouble() - 90)
            fill.color = if (pressed) Color.BLACK else b.color
            c.drawCircle(
                r.centerX() + (Math.cos(rad2) * r.width() * 0.36).toFloat(),
                r.centerY() + (Math.sin(rad2) * r.height() * 0.36).toFloat(),
                minOf(r.width(), r.height()) * 0.07f, fill,
            )
        }

        if (b.isPad) {
            sub.color = 0x88FFFFFF.toInt()
            sub.textSize = 13f * density
            c.drawText(b.label.ifEmpty { "트랙패드" }, r.centerX(), r.top + sub.textSize * 1.8f, sub)
            return
        }

        val combo = if (b.isKnob) Knob.describe(b) else Keys.combo(b.keys)
        val label = b.label.ifEmpty { if (b.isKnob) "노브" else combo.ifEmpty { "키 없음" } }
        text.color = if (pressed) Color.BLACK else Color.WHITE
        text.textSize = (r.height() * 0.3f)
            .coerceAtMost(r.width() * (if (b.isKnob) 0.9f else 1.5f) / label.length.coerceAtLeast(2))
            .coerceAtMost(if (b.isKnob) r.height() * 0.16f else Float.MAX_VALUE)
            .coerceIn(9f * density, 56f * density)
        val cy = r.centerY() - (text.descent() + text.ascent()) / 2
        c.drawText(label, r.centerX(), cy, text)

        if (combo.isNotEmpty() && combo != label && r.height() > 56 * density) {
            sub.color = if (pressed) 0xAA000000.toInt() else 0xAAFFFFFF.toInt()
            sub.textSize = (text.textSize * 0.45f).coerceIn(9f * density, 16f * density)
            val sy = if (b.isKnob) cy + sub.textSize * 1.5f else r.bottom - sub.textSize * 0.7f
            c.drawText(combo, r.centerX(), sy, sub)
        }
    }

    private fun shade(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt(),
    )
}
