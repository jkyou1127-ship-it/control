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

    /** 글자를 입력하는 화면은 키보드가 올라온 만큼도 비운다. 편집·실행 화면은 크기가 흔들리지 않게 끈다. */
    protected open val avoidKeyboard = true

    /**
     * Android 15 이상을 대상으로 빌드하면 화면이 상태 표시줄·내비게이션 바 밑까지 그려진다.
     * 내용이 가려지지 않게 그만큼 안쪽 여백을 준다(그렇지 않은 빌드에서는 여백이 0 으로 들어온다).
     */
    override fun setContentView(view: android.view.View) {
        super.setContentView(view)
        val l = view.paddingLeft; val t = view.paddingTop; val r = view.paddingRight; val b = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                var types = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                if (avoidKeyboard) types = types or android.view.WindowInsets.Type.ime()
                val i = insets.getInsets(types)
                v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    l + insets.systemWindowInsetLeft, t + insets.systemWindowInsetTop,
                    r + insets.systemWindowInsetRight, b + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        Link.onVisible(true)
        if (shownTheme != Themes.revision) { recreate(); return }   // 설정에서 테마를 바꾸고 돌아온 경우
        Link.ensure()
    }

    override fun onStop() {
        super.onStop()
        Link.onVisible(false)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** 편집 화면과 연주 화면이 같은 모양으로 버튼을 그리도록 공유한다. */
class PadPainter(private val ctx: Context, private val theme: PadTheme = Themes.current) {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = android.graphics.Rect()
    private val density = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val keyNames = Link.showKeyNames
    private val opacityOn = Labs.isOn("opacity")
    private val textScaleOn = Labs.isOn("text-scale")

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

    /** 편집 화면에서 그룹에 속한 버튼의 왼쪽 위에 그룹 번호를 붙인다. */
    fun badge(c: Canvas, r: RectF, text: String) {
        sub.textSize = 11f * density
        val w = sub.measureText(text) + 8 * density
        fill.color = theme.text
        c.drawRoundRect(r.left, r.top, r.left + w, r.top + 16 * density, 4 * density, 4 * density, fill)
        sub.color = theme.bg
        c.drawText(text, r.left + w / 2, r.top + 12 * density, sub)
    }

    /** 사진의 가운데를 영역 비율에 맞게 잘라 낼 범위를 src 에 넣는다. */
    private fun crop(bmp: android.graphics.Bitmap, r: RectF) {
        val want = r.width() / r.height()
        if (bmp.width.toFloat() / bmp.height > want) {
            val w = (bmp.height * want).toInt().coerceAtLeast(1)
            src.set((bmp.width - w) / 2, 0, (bmp.width + w) / 2, bmp.height)
        } else {
            val h = (bmp.width / want).toInt().coerceAtLeast(1)
            src.set(0, (bmp.height - h) / 2, bmp.width, (bmp.height + h) / 2)
        }
    }

    /** 꾸미기용 사진. 영역에 꽉 차게 늘려 그린다. editor 면 사진이 없을 때 자리 표시를 그린다. */
    fun drawImage(c: Canvas, b: PadButton, r: RectF, selected: Boolean, editor: Boolean) {
        val bmp = ImageStore.get(ctx, b.image)
        if (bmp != null) c.drawBitmap(bmp, null, r, bitmapPaint)
        if (selected || (bmp == null && editor)) {
            stroke.color = theme.text
            stroke.strokeWidth = density * if (selected) 3f else 1f
            c.drawRect(r, stroke)
        }
        if (bmp == null && editor) {
            sub.color = theme.text and 0x00FFFFFF or 0x88000000.toInt()
            sub.textSize = 13f * density
            c.drawText("사진 없음", r.centerX(), r.centerY(), sub)
        }
    }

    /** 키 입력 표시 패널. shown 은 보여 줄 글자, dim 이면 흐리게(방금 뗀 키, 편집 화면의 안내 글자). */
    /**
     * 그림 에디터로 그린 모양이면 그 그림을 면으로 그리고 참을 돌려준다(눌리면 그린 부분만 눌림 색으로).
     * 그린 모양이 아니면 아무것도 하지 않고 거짓.
     */
    private fun drawFreeFace(c: Canvas, b: PadButton, r: RectF, pressed: Boolean, selected: Boolean): Boolean {
        if (b.shape != PadButton.SHAPE_FREE) return false
        val lit = if (b.pressColor != 0) b.pressColor else b.color
        val drawn = ImageStore.get(ctx, b.image)
        if (drawn != null) {
            bitmapPaint.colorFilter = if (pressed) android.graphics.PorterDuffColorFilter(lit, android.graphics.PorterDuff.Mode.SRC_ATOP) else null
            c.drawBitmap(drawn, null, r, bitmapPaint)
            bitmapPaint.colorFilter = null
        }
        if (selected || drawn == null) {
            stroke.color = if (selected) theme.text else b.color
            stroke.strokeWidth = density * if (selected) 3f else 1f
            c.drawRect(r, stroke)
        }
        return true
    }

    fun drawDisplay(c: Canvas, b: PadButton, r: RectF, selected: Boolean, shown: String, dim: Boolean) {
        if (!drawFreeFace(c, b, r, false, selected)) {
            val rad = minOf(r.width(), r.height()) * 0.2f
            fill.color = shade(b.color, 0.18f)
            c.drawRoundRect(r, rad, rad, fill)
            stroke.color = if (selected) theme.text else b.color
            stroke.strokeWidth = density * if (selected) 3f else theme.stroke
            c.drawRoundRect(r, rad, rad, stroke)
        }
        if (shown.isEmpty()) return
        val color = if (b.textColor != 0) b.textColor else theme.text
        text.color = if (dim) color and 0x00FFFFFF or 0x77000000 else color
        text.textSize = (r.height() * 0.5f).coerceAtMost(r.width() * 1.7f / shown.length.coerceAtLeast(3))
            .coerceAtLeast(9f * density)
        c.drawText(shown, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    /** sx, sy 는 손잡이가 기울어진 정도(-1~1), mask 는 지금 눌린 방향(Stick.UP 등). */
    fun drawStick(c: Canvas, b: PadButton, r: RectF, selected: Boolean, sx: Float, sy: Float, mask: Int) {
        // 그린 조이스틱은 그림이 바탕이 되고, 그 위에 손잡이만 움직인다.
        val drawnFace = drawFreeFace(c, b, r, false, selected)
        if (!drawnFace) {
            fill.color = shade(b.color, 0.22f)
            c.drawOval(r, fill)
            stroke.color = if (selected) theme.text else b.color
            stroke.strokeWidth = density * if (selected) 3f else theme.stroke
            c.drawOval(r, stroke)
        }
        // 가장자리에 방향별 키 이름. 눌린 방향은 밝게.
        if (!drawnFace) drawStickLabels(c, b, r, mask)
        // 손잡이
        fill.color = if (b.pressColor != 0 && mask != 0) b.pressColor else b.color
        c.drawCircle(
            r.centerX() + sx * r.width() * 0.25f, r.centerY() + sy * r.height() * 0.25f,
            minOf(r.width(), r.height()) * 0.2f, fill,
        )
    }

    private fun drawStickLabels(c: Canvas, b: PadButton, r: RectF, mask: Int) {
        sub.textSize = (minOf(r.width(), r.height()) * 0.1f).coerceIn(9f * density, 18f * density)
        val ox = floatArrayOf(0f, 0f, -0.8f, 0.8f)
        val oy = floatArrayOf(-0.8f, 0.8f, 0f, 0f)
        for (i in 0..3) {
            val key = b.keys.getOrNull(i) ?: 0
            if (key <= 0) continue
            sub.color = if (mask and Stick.BITS[i] != 0) b.color else theme.text and 0x00FFFFFF or 0x88000000.toInt()
            c.drawText(Keys.name(key), r.centerX() + ox[i] * r.width() / 2, r.centerY() + oy[i] * r.height() / 2 + sub.textSize * 0.35f, sub)
        }
    }

    /** alt: 0 = 기본 이름, 1 = 한글 상태 이름, 2 = 둘 다(편집 화면). */
    fun draw(c: Canvas, b: PadButton, r: RectF, pressed: Boolean, selected: Boolean, angle: Float = 0f, parts: Int = 0, alt: Int = 0) {
        if (!opacityOn || b.opacity >= 100) { drawBody(c, b, r, pressed, selected, angle, parts, alt); return }
        // 비치는 버튼: 버튼 전체를 따로 그린 뒤 한꺼번에 흐리게 올린다(테두리와 글자가 따로 겹쳐 보이지 않게).
        val m = density * 4
        val saved = c.saveLayerAlpha(r.left - m, r.top - m, r.right + m, r.bottom + m, b.opacity.coerceIn(PadButton.MIN_OPACITY, 100) * 255 / 100)
        drawBody(c, b, r, pressed, selected, angle, parts, alt)
        c.restoreToCount(saved)
    }

    private fun drawBody(c: Canvas, b: PadButton, r: RectF, pressed: Boolean, selected: Boolean, angle: Float, parts: Int, alt: Int) {
        if (b.isMouse) {
            // 그린 마우스: 그림을 그대로 쓰고, 어느 부분이든 눌리면 눌림 색으로.
            if (!drawFreeFace(c, b, r, parts != 0, selected)) drawMouse(c, b, r, selected, parts)
            return
        }
        if (b.isStick) { drawStick(c, b, r, selected, 0f, 0f, 0); return }
        if (b.isDisplay) { drawDisplay(c, b, r, selected, if (alt == 2) DisplayKind.editorLabel(b) else "", alt == 2); return }
        if (b.isImage) { drawImage(c, b, r, selected, alt == 2); return }
        val lit = if (b.pressColor != 0) b.pressColor else b.color      // 눌렸을 때 채우는 색
        // 눌렸을 때 글자는 채운 색의 밝기에 맞춰 검정/흰색 중 잘 보이는 쪽
        val onLit = if (b.textColor != 0) b.textColor else if (Themes.luminance(lit) > 0.5f) Color.BLACK else Color.WHITE
        fill.color = if (pressed && !b.isPad) lit else shade(b.color, if (b.isPad) 0.18f else 0.38f)
        stroke.color = if (selected) theme.text else b.color
        stroke.strokeWidth = density * if (selected) 3f else theme.stroke
        val rad = minOf(r.width(), r.height()) * 0.14f
        if (drawFreeFace(c, b, r, pressed && !b.isPad, selected)) {
            // 그림 에디터로 그린 버튼·노브·트랙패드 등: 그림이 면이 된다.
            if (b.isKnob) {
                // 노브는 돌아간 정도를 점으로 보여 준다.
                val a = Math.toRadians(angle.toDouble() - 90)
                fill.color = if (pressed) lit else b.color
                c.drawCircle(
                    r.centerX() + (Math.cos(a) * r.width() * 0.36).toFloat(),
                    r.centerY() + (Math.sin(a) * r.height() * 0.36).toFloat(),
                    minOf(r.width(), r.height()) * 0.07f, fill,
                )
            }
            if (b.label.isNotEmpty()) {
                text.color = if (b.textColor != 0) b.textColor else theme.text
                text.textSize = (r.height() * 0.28f).coerceAtMost(r.width() * 1.5f / b.label.length.coerceAtLeast(2)).coerceIn(9f * density, 56f * density)
                c.drawText(b.label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
            return
        }
        val oval = b.shape == PadButton.SHAPE_OVAL || b.isKnob
        val photo = if (b.image.isNotEmpty() && !b.isKnob && !b.isPad) ImageStore.get(ctx, b.image) else null
        if (photo != null) {
            // 사진을 버튼 모양대로 잘라 면에 채우고, 눌렸을 때는 눌림 색을 반투명하게 덮는다.
            clip.reset()
            if (oval) clip.addOval(r, android.graphics.Path.Direction.CW)
            else clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
            c.save(); c.clipPath(clip)
            crop(photo, r)
            c.drawBitmap(photo, src, r, bitmapPaint)
            if (pressed) { fill.color = lit and 0x00FFFFFF or 0x88000000.toInt(); c.drawRect(r, fill) }
            c.restore()
            if (oval) c.drawOval(r, stroke) else c.drawRoundRect(r, rad, rad, stroke)
        } else if (oval) {
            c.drawOval(r, fill); c.drawOval(r, stroke)
        } else {
            c.drawRoundRect(r, rad, rad, fill); c.drawRoundRect(r, rad, rad, stroke)
        }

        if (b.isKnob) {
            // 돌아간 정도를 보여 주는 표시점
            val rad2 = Math.toRadians(angle.toDouble() - 90)
            fill.color = if (pressed) onLit else if (theme.colorText) theme.text else b.color
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

        val plain = b.type == PadButton.TYPE_BUTTON
        val combo = when {
            b.isShow -> "그룹 ${b.target} 표시"
            b.isSwitch -> "레이아웃 전환"
            b.isKnob -> Knob.describe(b)
            plain && b.mode == PadButton.MODE_MACRO -> "매크로 ${b.macro.size}단계"
            plain && b.mode == PadButton.MODE_TURBO -> Keys.combo(b.keys) + " ×${b.rate}/초"
            else -> Keys.combo(b.keys)
        }
        val name = when {
            b.label2.isEmpty() || alt == 0 -> b.label
            alt == 1 -> b.label2
            else -> b.label + "/" + b.label2
        }
        if (photo != null && name.isEmpty()) return      // 사진이 있으면 이름을 적었을 때만 글자를 올린다
        val label = name.ifEmpty { if (b.isShow) "그룹 ${b.target}" else if (b.isKnob) "노브" else combo.ifEmpty { "키 없음" } }
        text.color = if (pressed) onLit else if (b.textColor != 0) b.textColor else if (theme.colorText) b.color else theme.text
        text.textSize = (r.height() * 0.3f)
            .coerceAtMost(r.width() * (if (b.isKnob) 0.9f else 1.5f) / label.length.coerceAtLeast(2))
            .coerceAtMost(if (b.isKnob) r.height() * 0.16f else Float.MAX_VALUE)
            .coerceIn(9f * density, 56f * density) * (if (textScaleOn) b.textScale / 100f else 1f)
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

    /** 고를 수 있는 이름들: 기본 테마 다음에 테마 에디터로 직접 만든 테마가 온다. */
    val names: List<String> get() = all.map { it.name } + user.map { it.name }

    /** 테마가 바뀔 때마다 올라간다. 떠 있는 화면이 다시 그려야 하는지 판단하는 데 쓴다. */
    var revision = 0
        private set

    private val prefs get() = Link.prefs

    /** 테마 에디터로 직접 만든 테마들. 처음 쓸 때 저장된 것을 읽는다. */
    val user: MutableList<PadTheme> by lazy { load() }

    private fun load(): MutableList<PadTheme> {
        val out = ArrayList<PadTheme>()
        val saved = prefs.getString("userThemes", null)
        if (saved != null) {
            runCatching {
                val a = org.json.JSONArray(saved)
                for (i in 0 until a.length()) out += fromJson(a.getJSONObject(i))
            }
        } else if (prefs.contains("cBg")) {
            // 예전 버전의 '커스텀' 테마 하나를 첫 번째 직접 만든 테마로 옮긴다.
            out += custom(
                prefs.getInt("cBg", all[0].bg), prefs.getInt("cText", Color.WHITE), prefs.getFloat("cStrength", 1f),
                prefs.getFloat("cStroke", 1.5f), prefs.getBoolean("cColorText", false),
            )
        }
        return out
    }

    /** 직접 만든 테마 목록을 고친 뒤에 부른다. */
    fun saveUser() {
        val a = org.json.JSONArray()
        for (t in user) a.put(toJson(t))
        prefs.edit().putString("userThemes", a.toString()).apply()
        revision++
    }

    var index: Int
        get() = prefs.getInt("theme", 0).coerceIn(0, all.size + user.size - 1)
        set(i) {
            prefs.edit().putInt("theme", i).apply()
            revision++
        }

    val current: PadTheme
        get() = index.let { if (it < all.size) all[it] else user[it - all.size] }

    /** 배경 밝기에 맞춰 밝은/어두운 테마를 정한다. base 와 card 를 주지 않으면 배경에서 알아서 고른다. */
    fun make(
        name: String, bg: Int, text: Int, strength: Float, stroke: Float, colorText: Boolean,
        base: Int? = null, card: Int? = null,
    ): PadTheme {
        val light = luminance(bg) > 0.5f
        return PadTheme(
            name, bg or 0xFF000000.toInt(), (base ?: if (light) Color.WHITE else Color.BLACK) or 0xFF000000.toInt(),
            strength, text or 0xFF000000.toInt(), stroke, colorText, light, (card ?: mix(bg, text, 0.09f)) or 0xFF000000.toInt(),
        )
    }

    fun custom(bg: Int, text: Int, strength: Float, stroke: Float, colorText: Boolean): PadTheme =
        make("커스텀", bg, text, strength, stroke, colorText)

    fun toJson(t: PadTheme): org.json.JSONObject = org.json.JSONObject()
        .put("name", t.name).put("bg", t.bg).put("base", t.base).put("strength", t.strength.toDouble())
        .put("text", t.text).put("stroke", t.stroke.toDouble()).put("colorText", t.colorText).put("card", t.card)

    fun fromJson(o: org.json.JSONObject): PadTheme = make(
        o.optString("name").ifEmpty { "내 테마" }, o.getInt("bg"), o.getInt("text"),
        o.optDouble("strength", 1.0).toFloat().coerceIn(0.1f, 1.6f), o.optDouble("stroke", 1.5).toFloat().coerceIn(0.5f, 5f),
        o.optBoolean("colorText"), if (o.has("base")) o.getInt("base") else null, if (o.has("card")) o.getInt("card") else null,
    )

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
    // 가로 화면은 세로가 짧으므로 미리보기와 색 코드를 한 줄에 놓고, 그래도 넘치면 스크롤되게 한다.
    val preview = android.view.View(a)
    val hex = android.widget.TextView(a).apply { textSize = 16f; setPadding(a.dp(12), 0, 0, 0) }
    val head = android.widget.LinearLayout(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
    head.addView(preview, android.widget.LinearLayout.LayoutParams(0, a.dp(40), 1f))
    head.addView(hex)
    box.addView(head)
    val bars = listOf("빨강" to Color.red(initial), "초록" to Color.green(initial), "파랑" to Color.blue(initial)).map { (name, v) ->
        val row = android.widget.LinearLayout(a).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, a.dp(8), 0, 0) }
        row.addView(android.widget.TextView(a).apply { text = name; minWidth = a.dp(44) })
        val bar = android.widget.SeekBar(a).apply { max = 255; progress = v }
        row.addView(bar, android.widget.LinearLayout.LayoutParams(0, a.dp(40), 1f))
        box.addView(row)
        bar
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
    android.app.AlertDialog.Builder(a).setTitle("색 고르기")
        .setView(android.widget.ScrollView(a).apply { addView(box) })
        .setPositiveButton("확인") { _, _ -> done(color()) }
        .setNegativeButton("취소", null).show().wide()
}

/** 설정 화면에서 지금 테마로 버튼이 어떻게 보이는지 보여 준다. */
class ThemePreview(ctx: Context, private var theme: PadTheme = Themes.current) : android.view.View(ctx) {
    private var painter = PadPainter(ctx, theme)

    /** 아직 저장하지 않은 테마도 미리 볼 수 있게 한다. */
    fun show(t: PadTheme) {
        theme = t
        painter = PadPainter(context, t)
        invalidate()
    }
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
        val right = width - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > paddingLeft && x + c.measuredWidth > right) { x = paddingLeft; y += rowHeight + gap; rowHeight = 0 }
            if (place) c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowHeight = maxOf(rowHeight, c.measuredHeight)
        }
        return y + rowHeight + paddingBottom
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val unbounded = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
        val width = if (unbounded) resources.displayMetrics.widthPixels else MeasureSpec.getSize(widthSpec)
        for (i in 0 until childCount) {
            measureChild(getChildAt(i), MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.AT_MOST), heightSpec)
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

/** 편집 화면들의 도구 버튼. 테마 색을 따르는 납작한 모양이고, on 이면 켜진 상태로 강조한다. */
fun toolChip(ctx: Context, label: String, action: () -> Unit): android.widget.TextView =
    android.widget.TextView(ctx).apply {
        text = label; textSize = 14f; gravity = android.view.Gravity.CENTER; setSingleLine()
        setPadding(ctx.dp(12), ctx.dp(9), ctx.dp(12), ctx.dp(9))
        isClickable = true
        setOnClickListener { action() }
        styleChip(this, false)
    }

fun styleChip(chip: android.widget.TextView, on: Boolean) {
    val t = Themes.current
    val ctx = chip.context
    chip.setTextColor(if (on) t.bg else t.text)
    chip.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = ctx.dp(8).toFloat()
        setColor(if (on) t.text else t.card)
        setStroke(ctx.dp(1), t.text and 0x00FFFFFF or 0x33000000)
    }
}
