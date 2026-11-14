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
    override val avoidKeyboard = false
    private lateinit var pad: PadLayout
    private lateinit var view: EditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = LayoutStore.load(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        bar.addView(TextView(this).apply {
            text = pad.name; textSize = 16f; setTextColor(Themes.current.text); setSingleLine()
            minWidth = dp(120)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        view = EditorView(this, pad)
        bar.addView(CheckBox(this).apply {
            text = "격자 맞춤"; isChecked = true
            setOnCheckedChangeListener { _, on -> view.snap = on; view.invalidate() }
        })
        val multi = CheckBox(this).apply {
            text = "다중 선택"
            setOnCheckedChangeListener { _, on -> view.multi = on; view.invalidate() }
        }
        bar.addView(multi)
        bar.addView(CheckBox(this).apply {
            text = "슬라이드 입력"; isChecked = pad.slide
            setOnCheckedChangeListener { _, on -> pad.slide = on }
        })
        fun tool(label: String, action: () -> Unit) =
            bar.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        // 추가할 수 있는 것들은 한 메뉴에 모은다(도구 줄이 길어져 화면 밖으로 밀려나지 않게).
        val adds = LinkedHashMap<String, () -> Unit>()
        fun add(label: String, action: () -> Unit) { adds[label] = action }
        tool("＋추가") {
            val names = adds.keys.toTypedArray()
            AlertDialog.Builder(this).setTitle("추가")
                .setItems(names) { _, which -> checkpoint(); adds[names[which]]?.invoke() }
                .show()
        }
        add("노브 (사운드 볼텍스 · KSM)") {
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
        add("트랙패드") {
            val t = PadButton(0.3f, 0.25f, 0.4f, 0.5f, type = PadButton.TYPE_TRACKPAD, color = PadButton.PALETTE[7])
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        add("가상 마우스") {
            val h = 0.6f
            val w = (h * 0.62f * view.height / view.width.coerceAtLeast(1)).coerceIn(0.1f, 0.5f)
            val t = PadButton(0.5f - w / 2, 0.2f, w, h, type = PadButton.TYPE_MOUSE, color = PadButton.PALETTE[7])
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        add("퀵 리스타트 버튼") {
            AlertDialog.Builder(this).setTitle("퀵 리스타트 버튼")
                .setItems(Restart.names.toTypedArray()) { _, which ->
                    val w = 0.14f
                    val r = Restart.button(which, 0.5f - w / 2, 0.04f, w, 0.2f)
                    pad.buttons += r; view.selected = r; view.invalidate()
                }.show()
        }
        add("숫자 키패드") {
            AlertDialog.Builder(this).setTitle("숫자 키패드 추가")
                .setItems(Keypad.names.toTypedArray()) { _, which ->
                    // 화면에서 칸이 대략 정사각형이 되도록 폭을 정해 가운데에 놓는다.
                    val h = 0.9f
                    val w = (h * 0.8f * view.height / view.width.coerceAtLeast(1)).coerceIn(0.2f, 0.9f)
                    val keys = Keypad.create(which == 0, 0.5f - w / 2, 0.05f, w, h)
                    pad.buttons += keys
                    // 한 덩어리로 옮길 수 있게 전부 선택해 둔다.
                    multi.isChecked = true
                    view.selection.clear(); view.selection += keys
                    view.invalidate()
                }.show()
        }
        for ((i, name) in Keyboard.names.withIndex()) add(name) {
            val keys = Keyboard.create(korean = i == 1)
            pad.buttons += keys
            // 화면을 가득 채우므로, 줄이거나 옮기기 쉽게 전부 선택해 둔다.
            multi.isChecked = true
            view.selection.clear(); view.selection += keys
            view.invalidate()
        }
        add("표시 버튼 (누르면 그룹이 나타남)") {
            // 아직 표시 버튼이 없는 가장 작은 그룹 번호를 기본으로 한다.
            val used = pad.buttons.filter { it.isShow }.map { it.target }
            val g = (1..PadButton.GROUPS).firstOrNull { it !in used } ?: 1
            val t = PadButton(
                0.43f, 0.4f, 0.14f, 0.2f, type = PadButton.TYPE_SHOW, mode = PadButton.MODE_TOGGLE,
                target = g, color = PadButton.PALETTE[5],
            )
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        tool("↶ 취소") { step(undo, redo, "되돌릴 것이 없습니다") }
        tool("↷ 다시") { step(redo, undo, "다시 실행할 것이 없습니다") }
        tool("숨김") {
            // 겹쳐 있는 버튼 밑의 것을 고치기 쉽게, 편집 화면에서만 잠깐 안 보이게 한다. 레이아웃 자체는 바뀌지 않는다.
            val toast = { t: String -> android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show() }
            when {
                view.selection.isNotEmpty() -> {
                    val n = view.selection.size
                    view.hidden += view.selection
                    view.selection.clear()
                    toast("${n}개를 숨겼습니다. 아무것도 선택하지 않고 '숨김'을 누르면 다시 나타납니다")
                }
                view.hidden.isNotEmpty() -> { view.hidden.clear(); toast("숨긴 버튼을 모두 표시했습니다") }
                else -> toast("숨길 버튼을 먼저 선택하세요")
            }
            view.invalidate()
        }
        tool("속성") {
            val sel = view.selection.toList()
            when {
                sel.isEmpty() -> hint()
                sel.size == 1 -> editButton(sel[0])
                else -> editMany(sel)
            }
        }
        tool("복제") {
            val sel = view.selection.toList()
            if (sel.isEmpty()) hint()
            else {
                checkpoint()
                val copies = sel.map { it.copy().apply { x = min(x + 0.03f, 1f - w); y = min(y + 0.03f, 1f - h) } }
                pad.buttons += copies
                view.selection.clear(); view.selection += copies
                view.invalidate()
            }
        }
        tool("삭제") {
            if (view.selection.isEmpty()) hint()
            else { checkpoint(); pad.buttons.removeAll(view.selection); view.selection.clear(); view.invalidate() }
        }
        tool("실행") {
            LayoutStore.save(this, pad)
            startActivity(Intent(this, PlayActivity::class.java).putExtra("id", pad.id))
        }
        tool("저장") {
            LayoutStore.save(this, pad)
            android.widget.Toast.makeText(this, "저장했습니다", android.widget.Toast.LENGTH_SHORT).show()
        }
        tool("완료") { finish() }

        root.addView(android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(bar)
        })
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        view.onBeforeChange = { checkpoint() }
        view.onCreated = { editButton(it, isNew = true) }
        view.onEdit = { editButton(it) }
    }

    override fun onPause() {
        super.onPause()
        if (::pad.isInitialized) LayoutStore.save(this, pad)
    }

    // 실행취소: 바꾸기 직전의 레이아웃을 통째로 저장해 둔다.
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    /** 레이아웃을 바꾸기 직전에 부른다. */
    private fun checkpoint() {
        val now = pad.toJson().toString()
        if (undo.lastOrNull() == now) return      // 아무것도 바뀌지 않은 채 또 부른 경우
        undo.addLast(now)
        if (undo.size > History.LIMIT) undo.removeFirst()
        redo.clear()
    }

    private fun restore(json: String) {
        val saved = PadLayout.fromJson(org.json.JSONObject(json))
        pad.buttons.clear(); pad.buttons += saved.buttons
        view.selection.clear(); view.hidden.clear()
        view.invalidate()
    }

    /** from 에서 하나 꺼내 그 상태로 돌아가고, 지금 상태는 to 에 넣는다. */
    private fun step(from: ArrayDeque<String>, to: ArrayDeque<String>, empty: String) {
        val now = pad.toJson().toString()
        // 바뀐 것이 없는 기록은 건너뛴다.
        while (from.isNotEmpty() && from.last() == now) from.removeLast()
        if (from.isEmpty()) { android.widget.Toast.makeText(this, empty, android.widget.Toast.LENGTH_SHORT).show(); return }
        to.addLast(now)
        restore(from.removeLast())
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
            val shown = TextView(this).apply { textSize = 16f; setTextColor(Themes.current.text) }
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

        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(colorRow(color) { color = it }, LinearLayout.LayoutParams(-1, -2))
        var textColor = b.textColor
        var pressColor = b.pressColor
        box.addView(optionalColor("글자색", "테마 기본", textColor) { textColor = it })
        box.addView(optionalColor("눌렸을 때 색", "버튼 색과 같게", pressColor) { pressColor = it })

        AlertDialog.Builder(this).setTitle(if (isNew) "새 노브" else "노브 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.knobOut = out.selectedItemPosition
                b.sens = (sens.progress + 1).toFloat()
                b.keys = left; b.keys2 = right
                b.invert = invert.isChecked
                b.label = label.text.toString().trim(); b.color = color; b.textColor = textColor; b.pressColor = pressColor
                b.group = group.selectedItemPosition
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
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
        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(TextView(this).apply { text = "표시할 이름"; setPadding(0, dp(8), 0, 0) })
        val label = EditText(this).apply { setText(b.label); setSingleLine() }
        box.addView(label)

        val what = if (b.isMouse) "마우스" else "트랙패드"
        AlertDialog.Builder(this).setTitle(if (isNew) "새 $what" else "$what 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.sens = (sens.progress + 1).toFloat()
                b.mode = if (tap.isChecked) PadButton.PAD_TAP else PadButton.PAD_NO_TAP
                b.label = label.text.toString().trim()
                b.group = group.selectedItemPosition
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun editButton(b: PadButton, isNew: Boolean = false) {
        if (b.isShow) { editShow(b, isNew); return }
        if (b.isKnob) { editKnob(b, isNew); return }
        if (b.isPad || b.isMouse) { editPad(b, isNew); return }
        val keys = b.keys.toMutableList()
        var color = b.color
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }

        box.addView(TextView(this).apply { text = "입력할 키" })
        val keyRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val keyText = TextView(this).apply { textSize = 18f; setTextColor(Themes.current.text) }
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
        box.addView(TextView(this).apply {
            text = "한글 상태일 때 표시할 이름 (실행 화면에서 한/영을 누르면 바뀜, 비우면 안 바뀜)"; setPadding(0, dp(8), 0, 0)
        })
        val label2 = EditText(this).apply { setText(b.label2); setSingleLine() }
        box.addView(label2)

        box.addView(TextView(this).apply { text = "동작 방식"; setPadding(0, dp(8), 0, 0) })
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "누르고 있는 동안 입력 (게임용)", "누를 때 한 번 입력 (단축키용)", "누를 때마다 켜짐/꺼짐",
                    "누르면 일정 시간 동안 입력",
                ),
            )
            setSelection(b.mode)
        }
        box.addView(mode)
        val holdRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        holdRow.addView(TextView(this).apply { text = "입력 유지 시간(초)  " })
        val hold = EditText(this).apply {
            setText((b.holdMs / 1000f).toString().removeSuffix(".0")); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            minWidth = dp(90)
        }
        holdRow.addView(hold)
        box.addView(holdRow)
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                holdRow.visibility = if (pos == PadButton.MODE_TIMED) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        holdRow.visibility = if (b.mode == PadButton.MODE_TIMED) View.VISIBLE else View.GONE

        val oval = CheckBox(this).apply { text = "원형 버튼"; isChecked = b.shape == PadButton.SHAPE_OVAL }
        box.addView(oval)

        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(colorRow(color) { color = it }, LinearLayout.LayoutParams(-1, -2))
        var textColor = b.textColor
        var pressColor = b.pressColor
        box.addView(optionalColor("글자색", "테마 기본", textColor) { textColor = it })
        box.addView(optionalColor("눌렸을 때 색", "버튼 색과 같게", pressColor) { pressColor = it })

        AlertDialog.Builder(this).setTitle(if (isNew) "새 버튼" else "버튼 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.keys = keys; b.label = label.text.toString().trim(); b.mode = mode.selectedItemPosition
                b.label2 = label2.text.toString().trim()
                b.holdMs = PadButton.parseHold(hold.text.toString())
                b.shape = if (oval.isChecked) PadButton.SHAPE_OVAL else PadButton.SHAPE_RECT
                b.color = color; b.textColor = textColor; b.pressColor = pressColor
                b.group = group.selectedItemPosition
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun groupSpinner(initial: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
            listOf("항상 표시") + (1..PadButton.GROUPS).map { "그룹 $it — 표시 버튼을 눌러야 나타남" },
        )
        setSelection(initial)
    }

    /** 누르면 지정한 그룹의 버튼들이 나타나는 버튼. */
    private fun editShow(b: PadButton, isNew: Boolean) {
        var color = b.color
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply {
            text = "이 버튼을 누르면 아래에서 고른 그룹의 버튼들이 나타납니다.\n" +
                "나타나게 할 버튼은 각 버튼의 속성 > 표시 조건에서 같은 그룹으로 지정하세요."
        })
        box.addView(TextView(this).apply { text = "나타나게 할 그룹"; setPadding(0, dp(8), 0, 0) })
        val target = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
                (1..PadButton.GROUPS).map { g -> "그룹 $g (버튼 ${pad.buttons.count { it.group == g }}개)" },
            )
            setSelection(b.target - 1)
        }
        box.addView(target)
        box.addView(TextView(this).apply { text = "동작 방식"; setPadding(0, dp(8), 0, 0) })
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("누를 때마다 나타남/사라짐", "누르고 있는 동안만 나타남"),
            )
            setSelection(if (b.mode == PadButton.MODE_HOLD) 1 else 0)
        }
        box.addView(mode)
        box.addView(TextView(this).apply { text = "버튼에 표시할 이름"; setPadding(0, dp(8), 0, 0) })
        val label = EditText(this).apply { setText(b.label); setSingleLine() }
        box.addView(label)
        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "이 버튼 자체의 표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(colorRow(color) { color = it }, LinearLayout.LayoutParams(-1, -2))
        var textColor = b.textColor
        var pressColor = b.pressColor
        box.addView(optionalColor("글자색", "테마 기본", textColor) { textColor = it })
        box.addView(optionalColor("눌렸을 때 색", "버튼 색과 같게", pressColor) { pressColor = it })

        AlertDialog.Builder(this).setTitle(if (isNew) "새 표시 버튼" else "표시 버튼 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.target = target.selectedItemPosition + 1
                b.mode = if (mode.selectedItemPosition == 1) PadButton.MODE_HOLD else PadButton.MODE_TOGGLE
                b.label = label.text.toString().trim(); b.color = color; b.textColor = textColor; b.pressColor = pressColor
                // 자기가 여는 그룹에 자기 자신이 들어가면 영영 누를 수 없으므로 막는다.
                b.group = group.selectedItemPosition.let { if (it == b.target) 0 else it }
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    /** 고르지 않으면 기본값(0)을 쓰는 색. */
    private fun optionalColor(title: String, defaultName: String, initial: Int, onPick: (Int) -> Unit): View {
        var color = initial
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        val text = TextView(this)
        val swatch = View(this)
        fun show() {
            text.text = title + ": " + if (color == 0) defaultName else "#%06X".format(color and 0xFFFFFF)
            swatch.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                if (color != 0) setColor(color)
                setStroke(dp(1), Themes.current.text)
            }
        }
        show()
        row.addView(swatch, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) })
        text.setSingleLine(); text.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            this.text = "고르기"
            setOnClickListener {
                pickColor(this@EditorActivity, if (color == 0) Color.WHITE else color) { color = it; onPick(it); show() }
            }
        })
        row.addView(Button(this).apply { this.text = "기본"; setOnClickListener { color = 0; onPick(0); show() } })
        return row
    }

    /** 여러 버튼을 한꺼번에 바꾼다. 건드리지 않은 항목은 그대로 둔다. */
    private fun editMany(sel: List<PadButton>) {
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply { text = "선택한 ${sel.size}개에 한꺼번에 적용합니다. 바꾸지 않은 항목은 그대로 둡니다." })

        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        val group = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("변경 안 함", "항상 표시") + (1..PadButton.GROUPS).map { "그룹 $it — 표시 버튼을 눌러야 나타남" },
            )
        }
        box.addView(group)

        var color: Int? = null
        var textColor: Int? = null
        var pressColor: Int? = null
        box.addView(TextView(this).apply { text = "버튼 색 (고르면 적용)"; setPadding(0, dp(8), 0, 0) })
        box.addView(colorRow(0) { color = it }, LinearLayout.LayoutParams(-1, -2))
        box.addView(optionalColor("글자색", "변경 안 함", 0) { textColor = it })
        box.addView(optionalColor("눌렸을 때 색", "변경 안 함", 0) { pressColor = it })
        val reset = CheckBox(this).apply { text = "글자색·눌렸을 때 색을 기본값으로 되돌리기" }
        box.addView(reset)

        AlertDialog.Builder(this).setTitle("${sel.size}개 한꺼번에 바꾸기")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                for (b in sel) {
                    val g = group.selectedItemPosition - 1
                    // 표시 버튼을 자기가 여는 그룹에 넣으면 누를 수 없게 되므로 건너뛴다.
                    if (g >= 0 && !(b.isShow && b.target == g)) b.group = g
                    color?.let { b.color = it }
                    if (reset.isChecked) { b.textColor = 0; b.pressColor = 0 }
                    textColor?.takeIf { it != 0 }?.let { b.textColor = it }
                    pressColor?.takeIf { it != 0 }?.let { b.pressColor = it }
                }
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    /** 기본 색 8개와, 원하는 색을 직접 고르는 ＋ 칸. */
    private fun colorRow(initial: Int, onPick: (Int) -> Unit): View {
        var color = initial
        val row = FlowLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        val plus = TextView(this).apply { text = "＋"; gravity = Gravity.CENTER; textSize = 18f }
        fun paint() {
            for (i in PadButton.PALETTE.indices) {
                val c = PadButton.PALETTE[i]
                row.getChildAt(i).background = GradientDrawable().apply {
                    setColor(c); cornerRadius = dp(6).toFloat()
                    if (c == color) setStroke(dp(3), Themes.current.text)
                }
            }
            val custom = color !in PadButton.PALETTE
            plus.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                if (custom) setColor(color)
                setStroke(dp(if (custom) 3 else 1), Themes.current.text)
            }
            plus.setTextColor(if (custom && Themes.luminance(color) > 0.5f) Color.BLACK else Themes.current.text)
        }
        for (c in PadButton.PALETTE) {
            row.addView(View(this).apply { setOnClickListener { color = c; onPick(c); paint() } },
                android.view.ViewGroup.LayoutParams(dp(40), dp(40)))
        }
        plus.setOnClickListener { pickColor(this, color) { color = it; onPick(it); paint() } }
        row.addView(plus, android.view.ViewGroup.LayoutParams(dp(40), dp(40)))
        paint()
        return row
    }

    /** 여러 키를 골라 조합(예: Ctrl + Shift + S)을 만든다. */
    private fun pickKeys(current: List<Int>, done: (List<Int>) -> Unit) {
        val chosen = LinkedHashSet(current)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        for ((title, defs) in Keys.groups) {
            box.addView(TextView(this).apply { text = title; setPadding(0, dp(10), 0, dp(4)) })
            val grid = FlowLayout(this)
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
                grid.addView(cell, android.view.ViewGroup.LayoutParams(dp(68), dp(44)))
            }
            box.addView(grid, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this).setTitle("키 선택 (여러 개를 고르면 동시에 입력)")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ -> done(Keys.ordered(chosen)) }
            .setNeutralButton("모두 해제") { _, _ -> done(emptyList()) }
            .setNegativeButton("취소", null)
            .show().wide()
    }
}

class EditorView(ctx: Context, private val pad: PadLayout) : View(ctx) {
    /** 선택된 버튼들. 다중 선택이 꺼져 있으면 많아야 하나다. */
    val selection = LinkedHashSet<PadButton>()
    var selected: PadButton?
        get() = selection.lastOrNull()
        set(v) { selection.clear(); if (v != null) selection += v }

    /** 편집 화면에서만 잠깐 숨긴 버튼들. 그리지도 않고 잡히지도 않지만 레이아웃에는 그대로 있다. */
    val hidden = HashSet<PadButton>()

    private fun hitAt(x: Float, y: Float) = pad.buttons.lastOrNull { it !in hidden && it.contains(x / width, y / height) }

    /** 켜면 버튼을 눌러 선택에 더하거나 빼고, 손가락으로 훑고 지나간 버튼들을 선택한다. */
    var multi = false
        set(on) { field = on; if (!on && selection.size > 1) selected = selection.last() }
    var snap = true
    var onCreated: (PadButton) -> Unit = {}
    var onEdit: (PadButton) -> Unit = {}

    /** 드래그로 버튼을 옮기거나 크기를 바꾸거나 새로 만들기 직전에 한 번 불린다(실행취소용). */
    var onBeforeChange: () -> Unit = {}
    private var changed = false

    private fun beforeChange() {
        if (!changed) { changed = true; onBeforeChange() }
    }

    private val painter = PadPainter(ctx)
    private val rect = RectF()
    private val theme = Themes.current
    private val gridPaint = Paint().apply { color = theme.text and 0x00FFFFFF or 0x18000000; strokeWidth = 1f }
    private val drawPaint = Paint().apply { color = 0x664FC3F7; style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text }
    private val outlinePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat() }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.text and 0x00FFFFFF or 0x99000000.toInt(); textAlign = Paint.Align.CENTER; textSize = ctx.dp(18).toFloat()
    }

    init {
        setBackgroundColor(theme.bg)
    }

    private val handle = ctx.dp(22).toFloat()
    private val minSize = ctx.dp(36).toFloat()
    private val slop = ctx.dp(8).toFloat()

    private enum class Drag { NONE, DRAW, SLIDE, MOVE, RESIZE }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var moved = false
    private var wasSelected = false
    private var hitButton: PadButton? = null
    private val starts = HashMap<PadButton, FloatArray>()   // 옮기거나 크기를 바꾸기 시작할 때의 x, y, w, h
    private val box = RectF()                               // 그때 선택 전체를 감싸는 영역(화면 대비 비율)

    /** 선택된 버튼 전체를 감싸는 영역(화면 대비 비율). */
    private fun selectionBox(out: RectF): RectF {
        out.set(
            selection.minOf { it.x }, selection.minOf { it.y },
            selection.maxOf { it.x + it.w }, selection.maxOf { it.y + it.h },
        )
        return out
    }

    private fun remember() {
        starts.clear()
        for (b in selection) starts[b] = floatArrayOf(b.x, b.y, b.w, b.h)
        selectionBox(box)
    }

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
        for (b in pad.buttons) {
            if (b in hidden) continue
            painter.draw(c, b, rectOf(b), false, b in selection, alt = 2)
            if (b.group > 0) painter.badge(c, rectOf(b), "그룹 ${b.group}")
        }
        if (hidden.isNotEmpty()) {
            c.drawText("숨긴 버튼 ${hidden.size}개 — '숨김'을 누르면 다시 표시", width / 2f, hintPaint.textSize * 1.4f, hintPaint)
        }
        if (selection.isNotEmpty()) {
            // 크기 조절 손잡이. 여러 개를 선택했으면 전체를 감싸는 영역의 모서리에 둔다.
            val r = selectionBox(rect)
            if (selection.size > 1) {
                outlinePaint.color = theme.text and 0x00FFFFFF or 0x66000000
                c.drawRect(r.left * width, r.top * height, r.right * width, r.bottom * height, outlinePaint)
            }
            c.drawCircle(r.right * width, r.bottom * height, handle * 0.5f, handlePaint)
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
                downX = x; downY = y; curX = x; curY = y; moved = false; changed = false
                val corner = if (selection.isEmpty()) null else selectionBox(rect)
                if (corner != null && abs(x - corner.right * width) < handle * 1.4f &&
                    abs(y - corner.bottom * height) < handle * 1.4f
                ) {
                    remember()
                    drag = Drag.RESIZE
                } else {
                    val hit = hitAt(x, y)
                    hitButton = hit
                    if (multi && (hit == null || hit !in selection)) {
                        // 다중 선택: 선택되지 않은 곳에서 시작하면 손가락이 지나가는 버튼을 차례로 선택한다.
                        wasSelected = false
                        if (hit != null) selection += hit
                        drag = Drag.SLIDE
                    } else if (hit != null) {
                        // 선택된 버튼을 잡으면 선택된 것 전부를 옮긴다.
                        wasSelected = hit in selection
                        if (!multi) selected = hit
                        remember()
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
                when (drag) {
                    Drag.DRAW -> { curX = snapPx(x); curY = snapPx(y) }
                    Drag.SLIDE -> hitAt(x, y)?.let { selection += it }
                    Drag.MOVE -> if (moved && starts.isNotEmpty()) {
                        beforeChange()
                        // 선택된 것들이 함께 움직이되, 어느 것도 화면 밖으로 나가지 않게 한다.
                        val dx = (snapPx(x - downX) / width).coerceIn(-box.left, 1f - box.right)
                        val dy = (snapPx(y - downY) / height).coerceIn(-box.top, 1f - box.bottom)
                        for ((b, s) in starts) { b.x = s[0] + dx; b.y = s[1] + dy }
                    }
                    Drag.RESIZE -> if (starts.isNotEmpty()) {
                        beforeChange()
                        // 선택 전체를 왼쪽 위 모서리를 기준으로 같은 비율로 늘리거나 줄인다.
                        val sx = Resize.scale(box.left, box.width(), snapPx(x) / width, starts.values.minOf { it[2] }, minSize / width)
                        val sy = Resize.scale(box.top, box.height(), snapPx(y) / height, starts.values.minOf { it[3] }, minSize / height)
                        for ((b, s) in starts) {
                            b.x = box.left + (s[0] - box.left) * sx; b.w = s[2] * sx
                            b.y = box.top + (s[1] - box.top) * sy; b.h = s[3] * sy
                        }
                    }
                    Drag.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = drag
                drag = Drag.NONE
                if (e.actionMasked == MotionEvent.ACTION_UP) when (was) {
                    Drag.DRAW -> {
                        val l = min(downX, curX); val t = min(downY, curY)
                        val w = abs(curX - downX); val h = abs(curY - downY)
                        if (w >= minSize && h >= minSize) {
                            val b = PadButton(l / width, t / height, w / width, h / height,
                                color = PadButton.PALETTE[pad.buttons.size % PadButton.PALETTE.size])
                            beforeChange()
                            pad.buttons += b
                            selected = b
                            onCreated(b)
                        }
                    }
                    // 빈 곳을 움직이지 않고 톡 누르면 선택을 모두 푼다.
                    Drag.SLIDE -> if (!moved && hitButton == null) selection.clear()
                    Drag.MOVE -> if (!moved && wasSelected) {
                        // 이미 선택된 버튼을 다시 누름: 다중 선택에서는 선택 해제, 아니면 속성 열기
                        if (multi) hitButton?.let { selection -= it } else selected?.let(onEdit)
                    }
                    else -> {}
                }
            }
        }
        invalidate()
        return true
    }
}
