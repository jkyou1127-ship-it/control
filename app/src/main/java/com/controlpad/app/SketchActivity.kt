package com.controlpad.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 그림 에디터: 손가락으로 그린 그림이 그대로 버튼이 된다. 레이아웃 편집 화면과는 별개다.
 * 그림을 그리고 '버튼으로 만들기'를 누르면, 그린 부분을 감싸는 크기의 버튼이 레이아웃에 추가된다.
 */
class SketchActivity : BaseActivity() {
    override val avoidKeyboard = false
    private lateinit var pad: PadLayout
    private lateinit var view: SketchView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = LayoutStore.load(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        view = SketchView(this, pad)

        bar.addView(TextView(this).apply { text = "그림 에디터 · " + pad.name; textSize = 16f; setTextColor(Themes.current.text); setSingleLine() })
        // 펜 색
        val swatches = LinearLayout(this).apply { setPadding(dp(12), 0, dp(4), 0) }
        fun paintSwatches() {
            for (i in PadButton.PALETTE.indices) swatches.getChildAt(i).background = GradientDrawable().apply {
                setColor(PadButton.PALETTE[i]); cornerRadius = dp(5).toFloat()
                if (PadButton.PALETTE[i] == view.color) setStroke(dp(3), Themes.current.text)
            }
        }
        for (c in PadButton.PALETTE) swatches.addView(
            View(this).apply { setOnClickListener { view.color = c; paintSwatches(); view.invalidate() } },
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(4) },
        )
        swatches.addView(Button(this).apply {
            text = "＋색"
            setOnClickListener { pickColor(this@SketchActivity, view.color) { view.color = it; paintSwatches(); view.invalidate() } }
        })
        paintSwatches()
        bar.addView(swatches)

        fun tool(label: String, action: () -> Unit) = bar.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        tool("가늘게") { view.thickness = (view.thickness - dp(2)).coerceAtLeast(dp(2).toFloat()); view.invalidate() }
        tool("굵게") { view.thickness = (view.thickness + dp(2)).coerceAtMost(dp(40).toFloat()); view.invalidate() }
        bar.addView(CheckBox(this).apply {
            text = "안쪽 채우기"; isChecked = view.fill
            setOnCheckedChangeListener { _, on -> view.fill = on; view.invalidate() }
        })
        tool("↶ 한 획 취소") { view.undo() }
        tool("모두 지우기") { view.clear() }
        tool("버튼으로 만들기") { makeButton() }
        tool("완료") { finish() }

        root.addView(HorizontalScrollView(this).apply { isFillViewport = true; addView(bar) })
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun makeButton() {
        val made = view.render()
        if (made == null) {
            Toast.makeText(this, "먼저 화면에 버튼 모양을 그리세요", Toast.LENGTH_SHORT).show()
            return
        }
        val (bmp, box) = made
        val b = PadButton(
            box.left / view.width, box.top / view.height, box.width() / view.width, box.height() / view.height,
            shape = PadButton.SHAPE_FREE, image = ImageStore.save(this, bmp), color = view.color,
        )
        pad.buttons += b
        LayoutStore.save(this, pad)
        view.clear()
        // 바로 입력할 키를 고르게 한다. 이름·동작 방식 등은 일반 편집 화면의 속성에서 바꾼다.
        pickKeys(this, emptyList()) { keys ->
            b.keys = keys.toMutableList()
            LayoutStore.save(this, pad)
            view.invalidate()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::pad.isInitialized) LayoutStore.save(this, pad)
    }
}

class SketchView(ctx: Context, private val pad: PadLayout) : View(ctx) {
    var color = PadButton.PALETTE[0]
    var thickness = ctx.dp(8).toFloat()
    var fill = true

    private val theme = Themes.current
    private val painter = PadPainter(ctx)
    private val rect = RectF()
    private val strokes = ArrayList<FloatArray>()       // 다 그린 획들: x0, y0, x1, y1, …
    private val current = ArrayList<Float>()            // 그리고 있는 획
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.text and 0x00FFFFFF or 0x99000000.toInt(); textAlign = Paint.Align.CENTER; textSize = ctx.dp(18).toFloat()
    }
    private val path = Path()

    init {
        setBackgroundColor(theme.bg)
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
        invalidate()
    }

    fun clear() {
        strokes.clear(); current.clear()
        invalidate()
    }

    /** 획들을 (dx, dy) 만큼 옮겨 그린다. 채우기가 켜져 있으면 획마다 닫힌 안쪽을 옅게 칠한다. */
    private fun drawStrokes(c: Canvas, all: List<FloatArray>, dx: Float, dy: Float) {
        line.color = color; line.strokeWidth = thickness
        inside.color = color and 0x00FFFFFF or 0x66000000
        for (s in all) {
            if (s.size < 2) continue
            path.reset()
            path.moveTo(s[0] + dx, s[1] + dy)
            for (i in 1 until s.size / 2) path.lineTo(s[i * 2] + dx, s[i * 2 + 1] + dy)
            if (s.size == 2) path.lineTo(s[0] + dx + 0.1f, s[1] + dy)      // 점 하나도 보이게
            if (fill && s.size >= 6) c.drawPath(path, inside)
            c.drawPath(path, line)
        }
    }

    /**
     * 그린 것을 투명한 배경의 그림으로 만든다. 그림과, 화면에서 그 그림이 차지하는 영역을 돌려준다.
     * 아무것도 그리지 않았거나 너무 작으면 null.
     */
    fun render(): Pair<Bitmap, RectF>? {
        val box = Sketch.bounds(strokes, thickness / 2 + 2, width.toFloat(), height.toFloat()) ?: return null
        val w = (box[2] - box[0]).toInt()
        val h = (box[3] - box[1]).toInt()
        if (w < context.dp(24) || h < context.dp(24)) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawStrokes(Canvas(bmp), strokes, -box[0], -box[1])
        return bmp to RectF(box[0], box[1], box[0] + w, box[1] + h)
    }

    override fun onDraw(c: Canvas) {
        // 이미 있는 버튼들을 밑에 보여 줘서 자리를 맞출 수 있게 한다.
        for (b in pad.buttons) {
            rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
            painter.draw(c, b, rect, false, false, alt = 2)
        }
        if (strokes.isEmpty() && current.isEmpty()) {
            c.drawText("손가락으로 버튼 모양을 그린 뒤 '버튼으로 만들기'를 누르세요", width / 2f, height / 2f, hint)
        }
        drawStrokes(c, strokes, 0f, 0f)
        if (current.size >= 2) drawStrokes(c, listOf(current.toFloatArray()), 0f, 0f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(0f, width.toFloat())
        val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { current.clear(); current += x; current += y }
            MotionEvent.ACTION_MOVE -> {
                // 화면 갱신 사이에 들어온 점들도 빠뜨리지 않아야 선이 매끄럽다.
                for (i in 0 until e.historySize) {
                    current += e.getHistoricalX(i).coerceIn(0f, width.toFloat())
                    current += e.getHistoricalY(i).coerceIn(0f, height.toFloat())
                }
                current += x; current += y
            }
            MotionEvent.ACTION_UP -> {
                if (current.size >= 2) strokes += current.toFloatArray()
                current.clear()
            }
            MotionEvent.ACTION_CANCEL -> current.clear()
        }
        invalidate()
        return true
    }
}
