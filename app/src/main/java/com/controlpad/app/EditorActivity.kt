package com.controlpad.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 빈 곳을 드래그해 버튼을 그리고, 옮기고, 크기를 바꾸고, 키를 지정하는 화면. */
class EditorActivity : BaseActivity() {
    private lateinit var pad: PadLayout
    private lateinit var view: EditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = LayoutStore.load(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        bar.addView(TextView(this).apply {
            text = pad.name; textSize = 16f; setTextColor(Color.WHITE); setSingleLine()
            minWidth = dp(120)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        view = EditorView(this, pad)
        bar.addView(CheckBox(this).apply {
            text = "격자 맞춤"; isChecked = true
            setOnCheckedChangeListener { _, on -> view.snap = on; view.invalidate() }
        })
        bar.addView(CheckBox(this).apply {
            text = "슬라이드 입력"; isChecked = pad.slide
            setOnCheckedChangeListener { _, on -> pad.slide = on }
        })
        fun tool(label: String, action: () -> Unit) =
            bar.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        tool("＋노브") {
            // 화면에서 정사각형이 되도록 가로세로 비율을 맞춰 가운데에 놓는다.
            val h = 0.36f
            val w = (h * view.height / view.width.coerceAtLeast(1)).coerceIn(0.08f, 0.5f)
            val n = pad.buttons.count { it.isKnob }
            val k = PadButton(
                0.5f - w / 2, 0.5f - h / 2, w, h, type = PadButton.TYPE_KNOB,
                knobOut = if (n % 2 == 0) PadButton.OUT_MOUSE_X else PadButton.OUT_MOUSE_Y,
                color = PadButton.PALETTE[if (n % 2 == 0) 0 else 3],
            )
            pad.buttons += k; view.selected = k; view.invalidate()
            editButton(k, isNew = true)
        }
        tool("＋트랙패드") {
            val t = PadButton(0.3f, 0.25f, 0.4f, 0.5f, type = PadButton.TYPE_TRACKPAD, color = PadButton.PALETTE[7])
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        tool("＋마우스") {
            val h = 0.6f
            val w = (h * 0.62f * view.height / view.width.coerceAtLeast(1)).coerceIn(0.1f, 0.5f)
            val t = PadButton(0.5f - w / 2, 0.2f, w, h, type = PadButton.TYPE_MOUSE, color = PadButton.PALETTE[7])
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        tool("＋재시작") {
            AlertDialog.Builder(this).setTitle("퀵 리스타트 버튼")
                .setItems(Restart.names.toTypedArray()) { _, which ->
                    val w = 0.14f
                    val r = Restart.button(which, 0.5f - w / 2, 0.04f, w, 0.2f)
                    pad.buttons += r; view.selected = r; view.invalidate()
                }.show()
        }
        tool("속성") { view.selected?.let { editButton(it) } ?: hint() }
        tool("복제") {
            view.selected?.let {
                val c = it.copy().apply { x = min(x + 0.03f, 1f - w); y = min(y + 0.03f, 1f - h) }
                pad.buttons += c; view.selected = c; view.invalidate()
            } ?: hint()
        }
        tool("삭제") { view.selected?.let { pad.buttons -= it; view.selected = null; view.invalidate() } ?: hint() }
        tool("실행") {
            LayoutStore.save(this, pad)
            startActivity(Intent(this, PlayActivity::class.java).putExtra("id", pad.id))
        }
        tool("완료") { finish() }

        root.addView(android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(bar)
        })
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        view.onCreated = { editButton(it, isNew = true) }
        view.onEdit = { editButton(it) }
    }

    override fun onPause() {
        super.onPause()
        if (::pad.isInitialized) LayoutStore.save(this, pad)
    }

    private fun hint() = android.widget.Toast.makeText(this, "먼저 버튼을 눌러 선택하세요", android.widget.Toast.LENGTH_SHORT).show()

    private fun editKnob(b: PadButton, isNew: Boolean) {
        val left = b.keys.toMutableList()
        val right = b.keys2.toMutableList()
        var color = b.color
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }

        box.addView(TextView(this).apply { text = "노브를 돌리면 보낼 입력" })
        val out = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("마우스 X축 (아날로그)", "마우스 Y축 (아날로그)", "키보드 (돌리는 동안 키 입력)"),
            )
            setSelection(b.knobOut)
        }
        box.addView(out)

        val sensBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sensText = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        val sens = android.widget.SeekBar(this).apply { max = 19; progress = (b.sens.toInt() - 1).coerceIn(0, 19) }
        fun showSens() { sensText.text = "감도: ${sens.progress + 1}" }
        showSens()
        sens.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) = showSens()
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        sensBox.addView(sensText); sensBox.addView(sens)
        box.addView(sensBox)

        val keyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun keyRow(title: String, keys: MutableList<Int>) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val shown = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
            fun show() { shown.text = title + ": " + Keys.combo(keys).ifEmpty { "(없음)" } }
            show()
            row.addView(shown, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "키 선택"
                setOnClickListener { pickKeys(keys) { keys.clear(); keys += it; show() } }
            })
            keyBox.addView(row)
        }
        keyRow("왼쪽(↺)으로 돌릴 때", left)
        keyRow("오른쪽(↻)으로 돌릴 때", right)
        box.addView(keyBox)

        fun showOut() {
            val keysMode = out.selectedItemPosition == PadButton.OUT_KEYS
            keyBox.visibility = if (keysMode) View.VISIBLE else View.GONE
            sensBox.visibility = if (keysMode) View.GONE else View.VISIBLE
        }
        out.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = showOut()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        showOut()

        val invert = CheckBox(this).apply { text = "회전 방향 반대로"; isChecked = b.invert }
        box.addView(invert)

        box.addView(TextView(this).apply { text = "노브에 표시할 이름"; setPadding(0, dp(8), 0, 0) })
        val label = EditText(this).apply { setText(b.label); setSingleLine() }
        box.addView(label)

        val swatches = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        fun paint() {
            for (i in 0 until swatches.childCount) {
                val c = PadButton.PALETTE[i]
                swatches.getChildAt(i).background = GradientDrawable().apply {
                    setColor(c); cornerRadius = dp(6).toFloat()
                    if (c == color) setStroke(dp(3), Color.WHITE)
                }
            }
        }
        for (c in PadButton.PALETTE) {
            swatches.addView(View(this).apply { setOnClickListener { color = c; paint() } },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })
        }
        paint()
        box.addView(swatches)

        AlertDialog.Builder(this).setTitle(if (isNew) "새 노브" else "노브 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                b.knobOut = out.selectedItemPosition
                b.sens = (sens.progress + 1).toFloat()
                b.keys = left; b.keys2 = right
                b.invert = invert.isChecked
                b.label = label.text.toString().trim(); b.color = color
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun editPad(b: PadButton, isNew: Boolean) {
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply {
            text = if (b.isMouse) "위쪽 왼쪽·오른쪽은 좌클릭·우클릭(누르고 있는 동안 유지), 가운데는 휠입니다. " +
                "휠은 위아래로 밀면 스크롤, 톡 누르면 휠클릭입니다.\n아래쪽 몸통을 문지르면 마우스가 움직입니다. " +
                "좌클릭을 누른 채 다른 손가락으로 몸통을 움직이면 드래그가 됩니다."
            else "한 손가락으로 움직이면 마우스 이동, 두 손가락으로 움직이면 스크롤입니다.\n" +
                "드래그하려면 '좌클릭' 키를 넣은 버튼을 따로 그려서 누른 채로 움직이세요."
        })
        val sensText = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        val sens = android.widget.SeekBar(this).apply { max = 19; progress = (b.sens.toInt() - 1).coerceIn(0, 19) }
        fun showSens() { sensText.text = "포인터 속도: ${sens.progress + 1}" }
        showSens()
        sens.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) = showSens()
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        box.addView(sensText); box.addView(sens)
        val tap = CheckBox(this).apply {
            text = "탭하면 클릭 (한 손가락 = 좌클릭, 두 손가락 = 우클릭)"; isChecked = b.mode == PadButton.PAD_TAP
        }
        if (!b.isMouse) box.addView(tap)
        box.addView(TextView(this).apply { text = "표시할 이름"; setPadding(0, dp(8), 0, 0) })
        val label = EditText(this).apply { setText(b.label); setSingleLine() }
        box.addView(label)

        val what = if (b.isMouse) "마우스" else "트랙패드"
        AlertDialog.Builder(this).setTitle(if (isNew) "새 $what" else "$what 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                b.sens = (sens.progress + 1).toFloat()
                b.mode = if (tap.isChecked) PadButton.PAD_TAP else PadButton.PAD_NO_TAP
                b.label = label.text.toString().trim()
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun editButton(b: PadButton, isNew: Boolean = false) {
        if (b.isKnob) { editKnob(b, isNew); return }
        if (b.isPad || b.isMouse) { editPad(b, isNew); return }
        val keys = b.keys.toMutableList()
        var color = b.color
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }

        box.addView(TextView(this).apply { text = "입력할 키" })
        val keyRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val keyText = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        fun showKeys() { keyText.text = Keys.combo(keys).ifEmpty { "(없음)" } }
        showKeys()
        keyRow.addView(keyText, LinearLayout.LayoutParams(0, -2, 1f))
        keyRow.addView(Button(this).apply {
            text = "키 선택"
            setOnClickListener { pickKeys(keys) { keys.clear(); keys += it; showKeys() } }
        })
        box.addView(keyRow)

        box.addView(TextView(this).apply { text = "버튼에 표시할 이름 (비우면 키 이름)"; setPadding(0, dp(8), 0, 0) })
        val label = EditText(this).apply { setText(b.label); setSingleLine() }
        box.addView(label)

        box.addView(TextView(this).apply { text = "동작 방식"; setPadding(0, dp(8), 0, 0) })
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("누르고 있는 동안 입력 (게임용)", "누를 때 한 번 입력 (단축키용)", "누를 때마다 켜짐/꺼짐"),
            )
            setSelection(b.mode)
        }
        box.addView(mode)

        val oval = CheckBox(this).apply { text = "원형 버튼"; isChecked = b.shape == PadButton.SHAPE_OVAL }
        box.addView(oval)

        val swatches = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        fun paint() {
            for (i in 0 until swatches.childCount) {
                val c = PadButton.PALETTE[i]
                swatches.getChildAt(i).background = GradientDrawable().apply {
                    setColor(c); cornerRadius = dp(6).toFloat()
                    if (c == color) setStroke(dp(3), Color.WHITE)
                }
            }
        }
        for (c in PadButton.PALETTE) {
            swatches.addView(View(this).apply { setOnClickListener { color = c; paint() } },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })
        }
        paint()
        box.addView(swatches)

        AlertDialog.Builder(this).setTitle(if (isNew) "새 버튼" else "버튼 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                b.keys = keys; b.label = label.text.toString().trim(); b.mode = mode.selectedItemPosition
                b.shape = if (oval.isChecked) PadButton.SHAPE_OVAL else PadButton.SHAPE_RECT
                b.color = color
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 여러 키를 골라 조합(예: Ctrl + Shift + S)을 만든다. */
    private fun pickKeys(current: List<Int>, done: (List<Int>) -> Unit) {
        val chosen = LinkedHashSet(current)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val cols = max(4, (resources.displayMetrics.widthPixels * 0.8f / dp(76)).toInt())
        for ((title, defs) in Keys.groups) {
            box.addView(TextView(this).apply { text = title; setPadding(0, dp(10), 0, dp(4)) })
            val grid = GridLayout(this).apply { columnCount = cols }
            for (k in defs) {
                val cell = TextView(this).apply {
                    text = k.name; gravity = Gravity.CENTER; textSize = 15f
                    setTextColor(Color.WHITE)
                }
                fun paint() {
                    cell.background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(if (k.usage in chosen) 0xFF0288D1.toInt() else 0xFF30343C.toInt())
                    }
                }
                paint()
                cell.setOnClickListener {
                    if (!chosen.remove(k.usage)) chosen += k.usage
                    paint()
                }
                grid.addView(cell, GridLayout.LayoutParams().apply {
                    width = dp(68); height = dp(44); setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            box.addView(grid)
        }
        AlertDialog.Builder(this).setTitle("키 선택 (여러 개를 고르면 동시에 입력)")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ -> done(Keys.ordered(chosen)) }
            .setNeutralButton("모두 해제") { _, _ -> done(emptyList()) }
            .setNegativeButton("취소", null)
            .show()
    }
}

class EditorView(ctx: Context, private val pad: PadLayout) : View(ctx) {
    var selected: PadButton? = null
    var snap = true
    var onCreated: (PadButton) -> Unit = {}
    var onEdit: (PadButton) -> Unit = {}

    private val painter = PadPainter(ctx)
    private val rect = RectF()
    private val theme = Themes.current
    private val gridPaint = Paint().apply { color = theme.text and 0x00FFFFFF or 0x18000000; strokeWidth = 1f }
    private val drawPaint = Paint().apply { color = 0x664FC3F7; style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.text and 0x00FFFFFF or 0x99000000.toInt(); textAlign = Paint.Align.CENTER; textSize = ctx.dp(18).toFloat()
    }

    init {
        setBackgroundColor(theme.bg)
    }
    private val handle = ctx.dp(22).toFloat()
    private val minSize = ctx.dp(36).toFloat()
    private val slop = ctx.dp(8).toFloat()

    private enum class Drag { NONE, DRAW, MOVE, RESIZE }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var offX = 0f
    private var offY = 0f
    private var moved = false
    private var wasSelected = false

    private val cell: Float get() = width / 48f
    private fun snapPx(v: Float) = if (snap) (v / cell).roundToInt() * cell else v

    private fun rectOf(b: PadButton) = rect.apply { set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height) }

    override fun onDraw(c: Canvas) {
        if (snap) {
            var x = 0f
            while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += cell }
            var y = 0f
            while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += cell }
        }
        if (pad.buttons.isEmpty() && drag != Drag.DRAW) {
            c.drawText("빈 곳을 손가락으로 드래그해서 버튼을 그리세요", width / 2f, height / 2f, hintPaint)
        }
        for (b in pad.buttons) painter.draw(c, b, rectOf(b), false, b === selected)
        selected?.let {
            val r = rectOf(it)
            c.drawCircle(r.right, r.bottom, handle * 0.5f, handlePaint)
        }
        if (drag == Drag.DRAW) {
            c.drawRect(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY), drawPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        val x = e.x.coerceIn(0f, width.toFloat())
        val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; curX = x; curY = y; moved = false
                val sel = selected
                val sr = sel?.let { RectF(rectOf(it)) }
                if (sel != null && sr != null && abs(x - sr.right) < handle * 1.4f && abs(y - sr.bottom) < handle * 1.4f) {
                    drag = Drag.RESIZE
                } else {
                    val hit = pad.buttons.lastOrNull { it.contains(x / width, y / height) }
                    if (hit != null) {
                        wasSelected = hit === selected
                        selected = hit
                        offX = x - hit.x * width; offY = y - hit.y * height
                        drag = Drag.MOVE
                    } else {
                        selected = null
                        downX = snapPx(x); downY = snapPx(y)
                        drag = Drag.DRAW
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(x - downX) > slop || abs(y - downY) > slop) moved = true
                val b = selected
                when (drag) {
                    Drag.DRAW -> { curX = snapPx(x); curY = snapPx(y) }
                    Drag.MOVE -> if (b != null && moved) {
                        b.x = (snapPx(x - offX) / width).coerceIn(0f, 1f - b.w)
                        b.y = (snapPx(y - offY) / height).coerceIn(0f, 1f - b.h)
                    }
                    Drag.RESIZE -> if (b != null) {
                        b.w = (max(snapPx(x) - b.x * width, minSize) / width).coerceAtMost(1f - b.x)
                        b.h = (max(snapPx(y) - b.y * height, minSize) / height).coerceAtMost(1f - b.y)
                    }
                    Drag.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = drag
                drag = Drag.NONE
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (was == Drag.DRAW) {
                        val l = min(downX, curX); val t = min(downY, curY)
                        val w = abs(curX - downX); val h = abs(curY - downY)
                        if (w >= minSize && h >= minSize) {
                            val b = PadButton(l / width, t / height, w / width, h / height,
                                color = PadButton.PALETTE[pad.buttons.size % PadButton.PALETTE.size])
                            pad.buttons += b
                            selected = b
                            onCreated(b)
                        }
                    } else if (was == Drag.MOVE && !moved && wasSelected) {
                        selected?.let(onEdit)
                    }
                }
            }
        }
        invalidate()
        return true
    }
}
