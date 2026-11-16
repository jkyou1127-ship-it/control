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
    override val followsOrientation = true
    private lateinit var pad: PadLayout
    private lateinit var view: EditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = LayoutStore.load(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }

        // 화면 구성: 위쪽 막대(이름, 되돌리기, 저장·실행·완료) / 왼쪽 도구 모음(묶음별) / 나머지는 편집 영역.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        top.addView(TextView(this).apply {
            text = pad.name; textSize = 17f; setTextColor(Themes.current.text); setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        fun section(name: String) = rail.addView(TextView(this).apply {
            text = name; textSize = 12f; setPadding(dp(2), dp(10), 0, dp(4))
            setTextColor(Themes.current.text and 0x00FFFFFF or 0x99000000.toInt())
        })
        fun railItem(v: View) = rail.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        // 도구가 놓일 자리: 이름으로 정한다. 여기에 없는 도구는 위쪽 막대에 놓인다.
        val pending = LinkedHashMap<String, View>()

        view = EditorView(this, pad)
        val snapBox = CheckBox(this).apply {
            text = "격자 맞춤"; isChecked = true
            setOnCheckedChangeListener { _, on -> view.snap = on; view.invalidate() }
        }
        val multi = CheckBox(this).apply {
            text = "다중 선택"
            setOnCheckedChangeListener { _, on -> view.multi = on; view.invalidate() }
        }
        val slideBox = CheckBox(this).apply {
            text = "슬라이드 입력"; isChecked = pad.slide
            setOnCheckedChangeListener { _, on -> pad.slide = on }
        }
        fun tool(label: String, action: () -> Unit) { pending[label] = toolChip(this, label, action) }
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
        add("조이스틱 (4방향 키)") {
            val h = 0.42f
            val w = (h * view.height / view.width.coerceAtLeast(1)).coerceIn(0.08f, 0.6f)
            val k = PadButton(
                0.5f - w / 2, 0.5f - h / 2, w, h, keys = Stick.ARROWS.toMutableList(),
                type = PadButton.TYPE_STICK, color = PadButton.PALETTE[6],
            )
            pad.buttons += k; view.selected = k; view.invalidate()
            editButton(k, isNew = true)
        }
        add("키 입력 표시 패널") {
            val t = PadButton(0.35f, 0.03f, 0.3f, 0.12f, type = PadButton.TYPE_DISPLAY, color = PadButton.PALETTE[7])
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        // 초당 입력 수처럼 화면에 놓는 표시 패널들. 실험실 기능을 켜지 않아도 쓸 수 있다.
        for (kind in DisplayKind.choices.filter { it != DisplayKind.KEYS }) add(DisplayKind.names[kind] + " 패널") {
            val wide = kind == DisplayKind.HISTORY || kind == DisplayKind.STATUS
            val t = PadButton(
                if (wide) 0.3f else 0.4f, 0.03f, if (wide) 0.4f else 0.2f, 0.12f,
                type = PadButton.TYPE_DISPLAY, mode = kind, color = PadButton.PALETTE[0],
                rate = if (kind == DisplayKind.COUNTDOWN) Timers.rateFor(4) else 10,     // 카운트다운 기본 3분
            )
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        add("사진") {
            pickImage { name ->
                checkpoint()
                // 사진의 가로세로 비율대로 가운데에 놓고, 버튼들 뒤에 깔리도록 맨 뒤에 넣는다.
                val bmp = ImageStore.get(this, name)
                val w = 0.3f
                val ratio = if (bmp != null) bmp.height.toFloat() / bmp.width else 1f
                val h = (w * ratio * view.width / view.height.coerceAtLeast(1)).coerceIn(0.05f, 1f)
                val t = PadButton(0.5f - w / 2, (0.5f - h / 2).coerceAtLeast(0f), w, h, type = PadButton.TYPE_IMAGE, image = name)
                pad.buttons.add(0, t); view.selected = t; view.invalidate()
            }
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
        if (Labs.isOn("option-buttons")) add("옵션 버튼 (설정 켜기/끄기)") {
            val t = PadButton(0.43f, 0.03f, 0.14f, 0.12f, type = PadButton.TYPE_OPTION, link = "haptic", color = PadButton.PALETTE[6])
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        add("모든 키 떼기 버튼") {
            // 키가 눌린 채로 남았을 때 한 번에 푸는 버튼(옵션 버튼의 한 종류).
            val t = PadButton(0.43f, 0.03f, 0.14f, 0.12f, type = PadButton.TYPE_OPTION, link = Options.RELEASE_ALL, color = PadButton.PALETTE[3])
            pad.buttons += t; view.selected = t; view.invalidate()
            editButton(t, isNew = true)
        }
        add("레이아웃 전환 버튼") {
            if (LayoutStore.list(this).none { it.id != pad.id }) {
                android.widget.Toast.makeText(this, "넘어갈 다른 레이아웃이 없습니다. 먼저 레이아웃을 하나 더 만드세요", android.widget.Toast.LENGTH_LONG).show()
            } else {
                val t = PadButton(0.43f, 0.03f, 0.14f, 0.14f, type = PadButton.TYPE_SWITCH, color = PadButton.PALETTE[4])
                pad.buttons += t; view.selected = t; view.invalidate()
                editButton(t, isNew = true)
            }
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
        // 아래 도구들은 실험실 기능이라, 켜져 있을 때만 도구 모음에 놓인다.
        val lockOn = Labs.isOn("lock")
        view.lockOn = lockOn
        view.coordsOn = Labs.isOn("editor-coords")
        view.divisions = GridSize.divisions[Link.gridSize]
        if (Labs.isOn("big-handle")) view.handleScale = 1.8f
        view.snapEdges = Labs.isOn("snap-edges")
        view.countOn = Labs.isOn("selection-count")
        view.numbersOn = Labs.isOn("editor-numbers")
        view.outlineHidden = Labs.isOn("show-hidden-outline")
        view.longPressOn = Labs.isOn("long-press-edit")
        val alignOn = Labs.isOn("align")
        val distributeOn = Labs.isOn("distribute")
        if (Labs.isOn("select-all")) tool("전체 선택") {
            multi.isChecked = true
            view.selection.clear(); view.selection += pad.buttons.filter { it !in view.hidden }
            view.invalidate()
        }
        if (lockOn) tool("잠금") {
            // 실수로 옮기거나 크기를 바꾸지 않게 한다. 선택한 것이 모두 잠겨 있으면 푼다.
            val sel = view.selection.toList()
            if (sel.isEmpty()) hint()
            else {
                checkpoint()
                val lock = !sel.all { it.locked }
                sel.forEach { it.locked = lock }
                android.widget.Toast.makeText(this, if (lock) "${sel.size}개를 잠갔습니다" else "잠금을 풀었습니다", android.widget.Toast.LENGTH_SHORT).show()
                view.invalidate()
            }
        }
        if (alignOn || distributeOn) tool("맞춤") {
            val sel = view.selection.toList()
            // 켜져 있는 종류만 보여 준다.
            val choices = Arrange.names.indices.filter { if (it < Arrange.ALIGN_COUNT) alignOn else distributeOn }
            if (sel.size < 2) android.widget.Toast.makeText(this, "두 개 이상 선택하세요 ('다중 선택'을 켜고 고릅니다)", android.widget.Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(this).setTitle("맞춤 · 균등 배치")
                .setItems(choices.map { Arrange.names[it] }.toTypedArray()) { _, which ->
                    checkpoint()
                    Arrange.apply(choices[which], sel, respectLock = lockOn)
                    view.invalidate()
                }.show()
        }
        tool("크기·위치") {
            val sel = view.selection.toList()
            if (sel.isEmpty()) hint() else editGeometry(sel)
        }
        tool("형변환") {
            // 선택한 것의 종류를 바꾼다(위치·크기·색·이름은 그대로). 사진은 바꿀 수 없다.
            val sel = view.selection.filter { !it.isImage }
            if (sel.isEmpty()) hint()
            else {
                val others = LayoutStore.list(this).any { it.id != pad.id }
                val kinds = SketchKind.names.indices.filter { (it != SketchKind.SWITCH || others) && (it != SketchKind.OPTION || Labs.isOn("option-buttons")) }
                AlertDialog.Builder(this).setTitle("무엇으로 바꿀까요?")
                    .setItems(kinds.map { SketchKind.names[it] }.toTypedArray()) { _, which ->
                        checkpoint()
                        val used = pad.buttons.filter { it.isShow }.map { it.target }
                        val free = (1..PadButton.GROUPS).firstOrNull { it !in used } ?: 1
                        for (b in sel) SketchKind.convert(b, kinds[which], free)
                        view.invalidate()
                        // 하나만 바꿨으면 새 종류의 속성 창을 바로 연다.
                        if (sel.size == 1) editButton(sel[0])
                    }.show()
            }
        }
        tool("모양") {
            val sel = view.selection.filter { Shapes.applies(it) }
            if (sel.isEmpty()) android.widget.Toast.makeText(this, "모양을 바꿀 수 있는 버튼을 선택하세요 (노브·조이스틱·마우스·패널·사진은 제 모양이 있습니다)", android.widget.Toast.LENGTH_LONG).show()
            else AlertDialog.Builder(this).setTitle("모양")
                .setItems(Shapes.names.toTypedArray()) { _, which ->
                    checkpoint()
                    for (b in sel) b.shape = Shapes.ids[which]
                    view.invalidate()
                }.show()
        }
        tool("순서") {
            val sel = view.selection.toList()
            if (sel.isEmpty()) hint()
            else AlertDialog.Builder(this).setTitle("겹치는 순서")
                .setItems(arrayOf("맨 앞으로 (다른 것 위에)", "맨 뒤로 (다른 것 밑에)")) { _, which ->
                    checkpoint()
                    val keep = pad.buttons.filter { it in view.selection }      // 선택한 것들끼리의 순서는 유지
                    pad.buttons.removeAll(keep)
                    if (which == 0) pad.buttons.addAll(keep) else pad.buttons.addAll(0, keep)
                    view.invalidate()
                }.show()
        }
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
                val shift = 0.03f
                val copies = sel.map { it.copy().apply { x = min(x + shift, 1f - w); y = min(y + shift, 1f - h) } }
                pad.buttons += copies
                view.selection.clear(); view.selection += copies
                view.invalidate()
            }
        }
        tool("삭제") {
            if (view.selection.isEmpty()) hint()
            else { checkpoint(); pad.buttons.removeAll(view.selection); view.selection.clear(); view.invalidate() }
        }
        if (Labs.isOn("nudge")) for ((label, dx, dy) in listOf(Triple("←", -1, 0), Triple("→", 1, 0), Triple("↑", 0, -1), Triple("↓", 0, 1))) tool(label) {
            if (view.selection.isEmpty()) hint() else { checkpoint(); view.nudge(dx, dy) }
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

        // 그리기: 손으로 그린 그림을 그대로 버튼으로 만든다. 켜면 위쪽에 펜 도구 줄이 나타난다.
        val penBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), dp(6)) }
        val penScroll = android.widget.HorizontalScrollView(this).apply { addView(penBar); visibility = View.GONE }
        val swatches = LinearLayout(this)
        fun paintSwatches() {
            for (i in PadButton.PALETTE.indices) swatches.getChildAt(i).background = GradientDrawable().apply {
                setColor(PadButton.PALETTE[i]); cornerRadius = dp(5).toFloat()
                if (PadButton.PALETTE[i] == view.penColor) setStroke(dp(3), Themes.current.text)
            }
        }
        for (c in PadButton.PALETTE) swatches.addView(
            View(this).apply { setOnClickListener { view.penColor = c; paintSwatches(); view.invalidate() } },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(4) },
        )
        paintSwatches()
        penBar.addView(swatches)
        fun pen(label: String, action: () -> Unit) =
            penBar.addView(toolChip(this, label, action), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        pen("＋색") { pickColor(this, view.penColor) { view.penColor = it; paintSwatches(); view.invalidate() } }
        pen("가늘게") { view.penWidth = (view.penWidth - dp(2)).coerceAtLeast(dp(2).toFloat()); view.invalidate() }
        pen("굵게") { view.penWidth = (view.penWidth + dp(2)).coerceAtMost(dp(40).toFloat()); view.invalidate() }
        penBar.addView(CheckBox(this).apply {
            text = "안쪽 채우기"; isChecked = view.penFill
            setOnCheckedChangeListener { _, on -> view.penFill = on; view.invalidate() }
        })
        pen("↶ 한 획 취소") { view.undoStroke() }
        pen("모두 지우기") { view.clearStrokes() }
        pen("✔ 버튼으로 만들기") {
            val made = view.renderStrokes()
            if (made == null) {
                android.widget.Toast.makeText(this, "먼저 화면에 버튼 모양을 그리세요", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                // 그린 그림을 어떤 요소로 만들지 고른다. 기본 편집 화면에서 넣을 수 있는 기능 요소는 모두 된다.
                val (bmp, box) = made
                val kinds = SketchKind.names.toTypedArray()
                AlertDialog.Builder(this).setTitle("무엇으로 만들까요?")
                    .setItems(kinds) { _, which ->
                        val others = LayoutStore.list(this).any { it.id != pad.id }
                        if (which == SketchKind.SWITCH && !others) {
                            android.widget.Toast.makeText(this, "넘어갈 다른 레이아웃이 없습니다. 먼저 레이아웃을 하나 더 만드세요", android.widget.Toast.LENGTH_LONG).show()
                            return@setItems
                        }
                        checkpoint()
                        val used = pad.buttons.filter { it.isShow }.map { it.target }
                        val b = SketchKind.create(
                            which, box.left / view.width, box.top / view.height, box.width() / view.width, box.height() / view.height,
                            ImageStore.save(this, bmp), view.penColor, (1..PadButton.GROUPS).firstOrNull { it !in used } ?: 1,
                        )
                        pad.buttons += b
                        view.clearStrokes()
                        view.selected = b
                        editButton(b, isNew = true)      // 키·이름·동작 방식 등은 같은 종류의 다른 요소와 똑같이 정한다
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
        lateinit var drawChip: TextView
        fun setSketching(on: Boolean) {
            view.sketching = on
            penScroll.visibility = if (on) View.VISIBLE else View.GONE
            styleChip(drawChip, on)
            view.invalidate()
        }
        drawChip = toolChip(this, "✎ 그리기") { setSketching(!view.sketching) }
        pending["✎ 그리기"] = drawChip

        // 왼쪽 도구 모음을 묶음별로 채운다.
        section("추가")
        for (name in listOf("＋추가", "✎ 그리기")) pending.remove(name)?.let { railItem(it) }
        section("선택한 것")
        railItem(multi)
        for (name in listOf("전체 선택", "속성", "형변환", "모양", "복제", "삭제", "잠금")) pending.remove(name)?.let { railItem(it) }
        section("배치")
        for (name in listOf("맞춤", "크기·위치", "순서", "숨김")) pending.remove(name)?.let { railItem(it) }
        // 한 칸씩 옮기기(실험실 기능): 네 방향 버튼을 한 줄에
        if (listOf("←", "→", "↑", "↓").all { it in pending }) {
            val arrows = LinearLayout(this)
            for (name in listOf("←", "↑", "↓", "→")) arrows.addView(pending.remove(name)?.apply {
                setPadding(0, dp(9), 0, dp(9))      // 좁은 칸에서 화살표가 잘리지 않게
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(2) })
            railItem(arrows)
        }
        section("옵션")
        railItem(snapBox); railItem(slideBox)
        // 나머지(되돌리기, 저장, 실행, 완료)는 위쪽 막대에. 화면이 좁아도 잘리지 않게 가로로 밀 수 있는 줄에 넣는다.
        val chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (chip in pending.values) chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        top.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = false
            addView(chips)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        root.addView(top)
        root.addView(penScroll)
        val body = LinearLayout(this)
        // 도구 모음 칸의 글자(체크 상자, 도구 이름)가 잘리지 않게 폭을 넉넉히 둔다.
        body.addView(ScrollView(this).apply { addView(rail) }, LinearLayout.LayoutParams(dp(156), -1))
        body.addView(view, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        // '그림 레이아웃'으로 들어왔으면 그리기를 켠 채로 시작한다.
        if (intent.getBooleanExtra("sketch", false)) setSketching(true)

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
                setOnClickListener { pickKeys(this@EditorActivity, keys) { keys.clear(); keys += it; show() } }
            })
            keyBox.addView(row)
        }
        keyRow("왼쪽(↺)으로 돌릴 때", left)
        keyRow("오른쪽(↻)으로 돌릴 때", right)
        val latch = CheckBox(this).apply {
            text = "계속 누름 — 돌리다 멈춰도 손을 뗄 때까지 키를 누르고 있음"
            isChecked = b.mode == PadButton.KNOB_UNTIL_RELEASE
        }
        keyBox.addView(latch)
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
                b.mode = if (latch.isChecked) PadButton.KNOB_UNTIL_RELEASE else PadButton.KNOB_UNTIL_STOP
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
        if (b.isStick) { editStick(b, isNew); return }
        if (b.isDisplay) { editDisplay(b, isNew); return }
        if (b.isImage) { editImage(b); return }
        if (b.isSwitch) { editSwitch(b, isNew); return }
        if (b.isOption) { editOption(b, isNew); return }
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
            setOnClickListener { pickKeys(this@EditorActivity, keys) { keys.clear(); keys += it; showKeys() } }
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
                    "누르면 일정 시간 동안 입력", "매크로 — 누르면 여러 키 조합을 차례로 입력",
                    "연타 — 누르고 있는 동안 빠르게 반복 입력",
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
        // 연타: 1초에 몇 번
        val rateRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rateRow.addView(TextView(this).apply { text = "1초에 누르는 횟수 (1~30)  " })
        val rate = EditText(this).apply {
            setText(b.rate.toString()); setSingleLine(); minWidth = dp(90)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        rateRow.addView(rate)
        box.addView(rateRow)
        // 매크로: 차례로 입력할 조합들 (위의 '입력할 키'는 쓰지 않는다)
        val macro = b.macro.map { it.toMutableList() }.toMutableList()
        val macroBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val macroText = TextView(this).apply { textSize = 16f; setTextColor(Themes.current.text) }
        fun showMacro() { macroText.text = "순서: " + Macro.describe(macro) }
        showMacro()
        macroBox.addView(macroText)
        val macroButtons = LinearLayout(this)
        macroButtons.addView(Button(this).apply {
            text = "단계 추가"
            setOnClickListener { pickKeys(this@EditorActivity, emptyList()) { if (it.isNotEmpty()) { macro += it.toMutableList(); showMacro() } } }
        })
        macroButtons.addView(Button(this).apply {
            text = "마지막 단계 삭제"
            setOnClickListener { if (macro.isNotEmpty()) macro.removeAt(macro.size - 1); showMacro() }
        })
        macroBox.addView(macroButtons)
        box.addView(macroBox)
        fun showModeRows(pos: Int) {
            holdRow.visibility = if (pos == PadButton.MODE_TIMED) View.VISIBLE else View.GONE
            rateRow.visibility = if (pos == PadButton.MODE_TURBO) View.VISIBLE else View.GONE
            macroBox.visibility = if (pos == PadButton.MODE_MACRO) View.VISIBLE else View.GONE
        }
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = showModeRows(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        showModeRows(b.mode)

        // 모양: 그린 버튼이면 '그린 모양'을 맨 앞에 두어 그대로 둘 수 있게 한다.
        val shapeIds = (if (b.shape == PadButton.SHAPE_FREE) listOf(PadButton.SHAPE_FREE) else emptyList()) + Shapes.ids
        val shapeNames = (if (b.shape == PadButton.SHAPE_FREE) listOf("그린 모양") else emptyList()) + Shapes.names
        box.addView(TextView(this).apply { text = "모양"; setPadding(0, dp(8), 0, 0) })
        val shape = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, shapeNames)
            setSelection(shapeIds.indexOf(b.shape).coerceAtLeast(0))
        }
        box.addView(shape)

        var image = b.image
        val photoRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val photoText = TextView(this)
        fun showPhoto() { photoText.text = "버튼 사진: " + if (image.isEmpty()) "없음" else "있음" }
        showPhoto()
        photoRow.addView(photoText, LinearLayout.LayoutParams(0, -2, 1f))
        photoRow.addView(Button(this).apply { text = "사진 고르기"; setOnClickListener { pickImage { image = it; showPhoto() } } })
        photoRow.addView(Button(this).apply { text = "지우기"; setOnClickListener { image = ""; showPhoto() } })
        box.addView(photoRow)

        // 투명도와 글자 크기
        fun percent(title: String, min: Int, max: Int, step: Int, value: Int): android.widget.SeekBar {
            val shown = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
            val bar = android.widget.SeekBar(this).apply { this.max = (max - min) / step; progress = ((value - min) / step).coerceIn(0, this.max) }
            fun show() { shown.text = "$title: ${min + bar.progress * step}%" }
            show()
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) = show()
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
            box.addView(shown); box.addView(bar)
            return bar
        }
        // 둘 다 실험실 기능이라, 켜져 있을 때만 조절 막대가 나온다.
        val opacity = if (Labs.isOn("opacity")) percent("불투명도", PadButton.MIN_OPACITY, 100, 10, b.opacity) else null
        val textScale = if (Labs.isOn("text-scale")) percent("글자 크기", PadButton.MIN_TEXT_SCALE, PadButton.MAX_TEXT_SCALE, 10, b.textScale) else null

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
                b.image = image
                opacity?.let { b.opacity = PadButton.MIN_OPACITY + it.progress * 10 }
                textScale?.let { b.textScale = PadButton.MIN_TEXT_SCALE + it.progress * 10 }
                b.holdMs = PadButton.parseHold(hold.text.toString())
                b.rate = (rate.text.toString().toIntOrNull() ?: 10).coerceIn(PadButton.MIN_RATE, PadButton.MAX_RATE)
                b.macro = macro
                b.shape = shapeIds.getOrElse(shape.selectedItemPosition) { PadButton.SHAPE_RECT }
                b.color = color; b.textColor = textColor; b.pressColor = pressColor
                b.group = group.selectedItemPosition
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun editStick(b: PadButton, isNew: Boolean) {
        val keys = IntArray(4) { b.keys.getOrNull(it) ?: 0 }
        var color = b.color
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply { text = "손잡이를 기울인 방향의 키를 누르고 있습니다. 손을 떼면 가운데로 돌아옵니다." })

        val rows = ArrayList<() -> Unit>()
        val presets = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        fun preset(name: String, set: List<Int>) = presets.addView(Button(this).apply {
            text = name
            setOnClickListener { for (i in 0..3) keys[i] = set[i]; rows.forEach { it() } }
        })
        preset("방향키", Stick.ARROWS)
        preset("W A S D", Stick.WASD)
        box.addView(presets)

        listOf("위", "아래", "왼쪽", "오른쪽").forEachIndexed { i, name ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val shown = TextView(this).apply { textSize = 16f; setTextColor(Themes.current.text) }
            val show = { shown.text = name + ": " + if (keys[i] > 0) Keys.name(keys[i]) else "(없음)" }
            show(); rows += show
            row.addView(shown, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "키 선택"
                setOnClickListener {
                    // 방향마다 키 하나만 쓴다. 여러 개를 고르면 수정키가 아닌 첫 키를 쓴다.
                    pickKeys(this@EditorActivity, listOfNotNull(keys[i].takeIf { it > 0 })) { picked ->
                        keys[i] = picked.firstOrNull { !Keys.isModifier(it) } ?: picked.firstOrNull() ?: 0
                        show()
                    }
                }
            })
            box.addView(row)
        }

        val diagonal = CheckBox(this).apply {
            text = "대각선 입력 — 비스듬히 기울이면 두 방향 키를 함께 누름"
            isChecked = b.mode != PadButton.STICK_4WAY
        }
        box.addView(diagonal)
        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(colorRow(color) { color = it }, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(this).setTitle(if (isNew) "새 조이스틱" else "조이스틱 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.keys = keys.toMutableList()
                b.mode = if (diagonal.isChecked) PadButton.STICK_8WAY else PadButton.STICK_4WAY
                b.group = group.selectedItemPosition
                b.color = color
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun editDisplay(b: PadButton, isNew: Boolean) {
        var color = b.color
        var textColor = b.textColor
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply {
            text = "실행 화면에서 고른 내용을 이 자리에 보여 줍니다. 버튼처럼 옮기고 크기를 바꿀 수 있습니다.\n" +
                "'눌리고 있는 키' 패널을 넣으면 화면 위쪽의 기본 키 표시는 나오지 않습니다.\n" +
                "스톱워치 · 카운트다운 타이머는 실행 화면에서 눌러 시작 · 멈춤, 길게(0.6초) 눌러 처음으로 돌립니다."
        })
        box.addView(TextView(this).apply { text = "표시할 내용"; setPadding(0, dp(8), 0, 0) })
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, DisplayKind.choices.map { DisplayKind.names[it] })
            setSelection(DisplayKind.choices.indexOf(DisplayKind.of(b)).coerceAtLeast(0))
        }
        box.addView(kind)
        // 카운트다운 타이머일 때만 쓰는 시간
        val durationLabel = TextView(this).apply { text = "카운트다운 시간"; setPadding(0, dp(8), 0, 0) }
        val duration = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, Timers.names)
            setSelection((b.rate - 1).coerceIn(0, Timers.durations.lastIndex))
        }
        box.addView(durationLabel); box.addView(duration)
        fun showDuration() {
            val on = DisplayKind.choices[kind.selectedItemPosition] == DisplayKind.COUNTDOWN
            durationLabel.visibility = if (on) View.VISIBLE else View.GONE
            duration.visibility = if (on) View.VISIBLE else View.GONE
        }
        showDuration()
        kind.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = showDuration()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(TextView(this).apply { text = "패널 색"; setPadding(0, dp(8), 0, 0) })
        box.addView(colorRow(color) { color = it }, LinearLayout.LayoutParams(-1, -2))
        box.addView(optionalColor("글자색", "테마 기본", textColor) { textColor = it })

        AlertDialog.Builder(this).setTitle(if (isNew) "새 표시 패널" else "표시 패널 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.mode = DisplayKind.choices[kind.selectedItemPosition]
                if (b.mode == DisplayKind.COUNTDOWN) b.rate = Timers.rateFor(duration.selectedItemPosition)
                b.group = group.selectedItemPosition
                b.color = color; b.textColor = textColor
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private var onImagePicked: ((String) -> Unit)? = null

    /** 사진을 고르게 하고, 앱 안으로 복사한 뒤 그 이름을 넘겨준다. */
    private fun pickImage(done: (String) -> Unit) {
        onImagePicked = done
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMAGE,
            )
        }.onFailure { android.widget.Toast.makeText(this, "사진을 고를 수 있는 앱이 없습니다", android.widget.Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE) return
        val done = onImagePicked
        onImagePicked = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || done == null) return
        val name = ImageStore.importFrom(this, uri)
        if (name == null) android.widget.Toast.makeText(this, "사진을 읽을 수 없습니다", android.widget.Toast.LENGTH_LONG).show()
        else done(name)
    }

    private fun editImage(b: PadButton) {
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply {
            text = "꾸미기용 사진입니다. 누를 수 없고, 겹쳐 있으면 밑에 있는 버튼이 눌립니다.\n" +
                "배경으로 쓰려면 화면 전체 크기로 늘리고 '순서 > 맨 뒤로'를 누르세요."
        })
        var image = b.image
        box.addView(Button(this).apply { text = "다른 사진으로 바꾸기"; setOnClickListener { pickImage { image = it } } })
        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        AlertDialog.Builder(this).setTitle("사진 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.image = image
                b.group = group.selectedItemPosition
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    /** 선택한 것의 위치와 크기를 숫자로 조금씩 바꾼다. 여러 개면 전체를 감싸는 영역을 기준으로 함께 바뀐다. */
    private fun editGeometry(sel: List<PadButton>) {
        checkpoint()
        val starts = sel.map { floatArrayOf(it.x, it.y, it.w, it.h) }
        val ox = starts.minOf { it[0] }
        val oy = starts.minOf { it[1] }
        // 화면 대비 % 로 보여 준다.
        val v = floatArrayOf(
            ox * 100, oy * 100,
            (starts.maxOf { it[0] + it[2] } - ox) * 100, (starts.maxOf { it[1] + it[3] } - oy) * 100,
        )
        fun apply() {
            val fitted = Geometry.fit(starts, floatArrayOf(v[0] / 100, v[1] / 100, v[2] / 100, v[3] / 100))
            sel.forEachIndexed { i, b -> val f = fitted[i]; b.x = f[0]; b.y = f[1]; b.w = f[2]; b.h = f[3] }
            view.invalidate()
        }
        val m = dp(16)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(4), m, 0) }
        box.addView(TextView(this).apply { text = "화면 대비 % 입니다. 숫자를 직접 적거나 버튼으로 조금씩 바꾸세요. 격자 맞춤과 관계없이 그대로 적용됩니다." })
        listOf("가로 위치", "세로 위치", "너비", "높이").forEachIndexed { i, name ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = name }, LinearLayout.LayoutParams(0, -2, 1f))
            val field = EditText(this).apply {
                setText("%.1f".format(v[i])); setSingleLine(); gravity = Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            var quiet = false       // 버튼으로 숫자를 바꿀 때는 글자 변경 알림을 무시한다
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (quiet) return
                    v[i] = s.toString().replace(',', '.').toFloatOrNull() ?: return
                    apply()
                }
            })
            fun step(label: String, delta: Float) = Button(this).apply {
                text = label; minWidth = dp(52); minimumWidth = dp(52); setPadding(0, 0, 0, 0)
                setOnClickListener {
                    v[i] = (v[i] + delta).coerceIn(0f, 100f)
                    quiet = true; field.setText("%.1f".format(v[i])); quiet = false
                    apply()
                }
            }
            row.addView(step("−1", -1f)); row.addView(step("−0.1", -0.1f))
            row.addView(field, LinearLayout.LayoutParams(dp(84), -2))
            row.addView(step("+0.1", 0.1f)); row.addView(step("+1", 1f))
            box.addView(row)
        }
        val dialog = AlertDialog.Builder(this).setTitle("크기·위치 세밀 조정")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인", null)
            .setNegativeButton("취소") { _, _ ->
                sel.forEachIndexed { i, b -> val s = starts[i]; b.x = s[0]; b.y = s[1]; b.w = s[2]; b.h = s[3] }
                view.invalidate()
            }
            .show()
        // 바뀌는 모습이 보이도록 화면을 어둡게 하지 않고 창을 아래쪽에 둔다.
        dialog.window?.setDimAmount(0f)
        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    /** 누르면 다른 레이아웃으로 넘어가는 버튼. 여러 레이아웃을 페이지처럼 오갈 때 쓴다. */
    private fun editSwitch(b: PadButton, isNew: Boolean) {
        val others = LayoutStore.list(this).filter { it.id != pad.id }
        var color = b.color
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply {
            text = "실행 화면에서 이 버튼을 누르면 아래 레이아웃으로 바로 넘어갑니다. " +
                "돌아오려면 그 레이아웃에도 전환 버튼을 하나 만들어 두세요."
        })
        box.addView(TextView(this).apply { text = "넘어갈 레이아웃"; setPadding(0, dp(8), 0, 0) })
        val target = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, others.map { it.name })
            setSelection(others.indexOfFirst { it.id == b.link }.coerceAtLeast(0))
        }
        box.addView(target)
        box.addView(TextView(this).apply { text = "버튼에 표시할 이름 (비우면 레이아웃 이름)"; setPadding(0, dp(8), 0, 0) })
        val label = EditText(this).apply { setText(if (isNew) "" else b.label); setSingleLine() }
        box.addView(label)
        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(colorRow(color) { color = it }, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(this).setTitle(if (isNew) "새 레이아웃 전환 버튼" else "레이아웃 전환 버튼 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                val to = others.getOrNull(target.selectedItemPosition)
                b.link = to?.id ?: ""
                b.label = label.text.toString().trim().ifEmpty { "→ " + (to?.name ?: "?") }
                b.group = group.selectedItemPosition
                b.color = color
                view.invalidate()
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    /** 실행 중에 누르면 고른 설정을 켜고 끄는 버튼. 켜져 있는 동안 버튼이 눌린 색으로 보인다. */
    private fun editOption(b: PadButton, isNew: Boolean) {
        val options = Options.all
        var color = b.color
        val m = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dp(8), m, 0) }
        box.addView(TextView(this).apply {
            text = "실행 화면에서 누를 때마다 아래 설정이 켜지고 꺼집니다. 켜져 있는 동안 버튼이 눌린 색으로 보입니다.\n" +
                "설정 화면과 실험실의 같은 설정도 함께 바뀝니다."
        })
        box.addView(TextView(this).apply { text = "켜고 끌 설정"; setPadding(0, dp(8), 0, 0) })
        val which = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, options.map { it.title })
            setSelection(options.indexOfFirst { it.id == b.link }.coerceAtLeast(0))
        }
        box.addView(which)
        box.addView(TextView(this).apply { text = "버튼에 표시할 이름 (비우면 설정 이름)"; setPadding(0, dp(8), 0, 0) })
        val label = EditText(this).apply { setText(b.label); setSingleLine() }
        box.addView(label)
        val group = groupSpinner(b.group)
        box.addView(TextView(this).apply { text = "표시 조건"; setPadding(0, dp(8), 0, 0) })
        box.addView(group)
        box.addView(colorRow(color) { color = it }, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(this).setTitle(if (isNew) "새 옵션 버튼" else "옵션 버튼 속성")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("확인") { _, _ ->
                checkpoint()
                b.link = options.getOrNull(which.selectedItemPosition)?.id ?: "haptic"
                b.label = label.text.toString().trim()
                b.group = group.selectedItemPosition
                b.color = color
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

    companion object {
        private const val REQ_IMAGE = 3
    }
}

class EditorView(ctx: Context, private val pad: PadLayout) : View(ctx) {
    /** 선택된 버튼들. 다중 선택이 꺼져 있으면 많아야 하나다. */
    val selection = LinkedHashSet<PadButton>()
    var selected: PadButton?
        get() = selection.lastOrNull()
        set(v) { selection.clear(); if (v != null) selection += v }

    /** 선택한 것의 위치·크기를 화면 아래쪽에 보여 줄지(실험실 기능). */
    var coordsOn = false

    // 실험실 기능들
    var snapEdges = false
    var countOn = false
    var numbersOn = false        // 편집 화면 버튼 번호(실험실 기능)
    var outlineHidden = false
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.dp(6).toFloat(), ctx.dp(4).toFloat()), 0f)
    }

    /** 한 칸씩 옮기기: 선택한 것을 격자 한 칸만큼 옮긴다(화면 밖으로는 안 나감, 잠긴 것은 그대로). */
    fun nudge(dx: Int, dy: Int) {
        val movable = selection.filter { !lockOn || !it.locked }
        if (movable.isEmpty() || width == 0) return
        val sx = cell / width * dx
        val sy = cell / height * dy
        val mx = sx.coerceIn(-movable.minOf { it.x }, 1f - movable.maxOf { it.x + it.w })
        val my = sy.coerceIn(-movable.minOf { it.y }, 1f - movable.maxOf { it.y + it.h })
        for (b in movable) { b.x += mx; b.y += my }
        invalidate()
    }

    /** 화면 가운데 안내선(실험실 기능). */

    /** 버튼을 길게 누르면 속성 열기(실험실 기능). */
    var longPressOn = false
    private var longFired = false
    private val longPress = Runnable {
        val b = hitButton
        if (drag == Drag.MOVE && !moved && b != null) {
            longFired = true
            drag = Drag.NONE
            selected = b
            invalidate()
            onEdit(b)
        }
    }

    /** 버튼 잠금(실험실 기능)이 켜져 있는지. 꺼져 있으면 잠긴 버튼도 평소처럼 움직인다. */
    var lockOn = false

    // ----- 그리기: 손으로 그린 획들을 모아 그림 버튼으로 만든다 -----
    var sketching = false
    var penColor = PadButton.PALETTE[0]
    var penWidth = ctx.dp(8).toFloat()
    var penFill = true
    private val strokes = ArrayList<FloatArray>()       // 다 그린 획들: x0, y0, x1, y1, …
    private val stroke = ArrayList<Float>()             // 그리고 있는 획
    private val penLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val penInside = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val penPath = android.graphics.Path()

    fun undoStroke() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
        invalidate()
    }

    fun clearStrokes() {
        strokes.clear(); stroke.clear()
        invalidate()
    }

    /** 획들을 (dx, dy) 만큼 옮겨 그린다. 채우기가 켜져 있으면 획마다 닫힌 안쪽을 옅게 칠한다. */
    private fun drawStrokes(c: Canvas, all: List<FloatArray>, dx: Float, dy: Float) {
        penLine.color = penColor; penLine.strokeWidth = penWidth
        penInside.color = penColor and 0x00FFFFFF or 0x66000000
        for (s in all) {
            if (s.size < 2) continue
            penPath.reset()
            penPath.moveTo(s[0] + dx, s[1] + dy)
            for (i in 1 until s.size / 2) penPath.lineTo(s[i * 2] + dx, s[i * 2 + 1] + dy)
            if (s.size == 2) penPath.lineTo(s[0] + dx + 0.1f, s[1] + dy)      // 점 하나도 보이게
            if (penFill && s.size >= 6) c.drawPath(penPath, penInside)
            c.drawPath(penPath, penLine)
        }
    }

    /**
     * 그린 것을 투명한 배경의 그림으로 만든다. 그림과, 화면에서 그 그림이 차지하는 영역을 돌려준다.
     * 아무것도 그리지 않았거나 너무 작으면 null.
     */
    fun renderStrokes(): Pair<android.graphics.Bitmap, RectF>? {
        val box = Sketch.bounds(strokes, penWidth / 2 + 2, width.toFloat(), height.toFloat()) ?: return null
        val w = (box[2] - box[0]).toInt()
        val h = (box[3] - box[1]).toInt()
        if (w < context.dp(24) || h < context.dp(24)) return null
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        drawStrokes(Canvas(bmp), strokes, -box[0], -box[1])
        return bmp to RectF(box[0], box[1], box[0] + w, box[1] + h)
    }

    private fun sketchTouch(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(0f, width.toFloat())
        val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stroke.clear(); stroke += x; stroke += y }
            MotionEvent.ACTION_MOVE -> {
                // 화면 갱신 사이에 들어온 점들도 빠뜨리지 않아야 선이 매끄럽다.
                for (i in 0 until e.historySize) {
                    stroke += e.getHistoricalX(i).coerceIn(0f, width.toFloat())
                    stroke += e.getHistoricalY(i).coerceIn(0f, height.toFloat())
                }
                stroke += x; stroke += y
            }
            MotionEvent.ACTION_UP -> {
                if (stroke.size >= 2) strokes += stroke.toFloatArray()
                stroke.clear()
            }
            MotionEvent.ACTION_CANCEL -> stroke.clear()
        }
        invalidate()
        return true
    }

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

    private val baseHandle = ctx.dp(22).toFloat()
    /** 큰 크기 조절 손잡이(실험실 기능)면 1.8배. */
    var handleScale = 1f
    private val handle get() = baseHandle * handleScale
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
        for (b in selection) if (!lockOn || !b.locked) starts[b] = floatArrayOf(b.x, b.y, b.w, b.h)     // 잠긴 것은 움직이지 않는다
        if (starts.isEmpty()) selectionBox(box)
        else box.set(
            starts.keys.minOf { it.x }, starts.keys.minOf { it.y },
            starts.keys.maxOf { it.x + it.w }, starts.keys.maxOf { it.y + it.h },
        )
    }

    /** 격자 한 칸 크기. 설정의 격자 크기를 따른다. */
    var divisions = 48
    private val cell: Float get() = width / divisions.toFloat()
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
            if (b in hidden) {
                if (outlineHidden) {
                    dashPaint.color = theme.text and 0x00FFFFFF or 0x88000000.toInt()
                    c.drawRect(rectOf(b), dashPaint)
                }
                continue
            }
            painter.draw(c, b, rectOf(b), false, b in selection, alt = 2)
            if (numbersOn) {
                val r = rectOf(b)
                hintPaint.textAlign = Paint.Align.LEFT
                c.drawText("#" + (pad.buttons.indexOf(b) + 1), r.left + hintPaint.textSize * 0.3f, r.top + hintPaint.textSize * 1.1f, hintPaint)
                hintPaint.textAlign = Paint.Align.CENTER
            }
            val marks = listOfNotNull(if (b.group > 0) "그룹 ${b.group}" else null, if (lockOn && b.locked) "잠금" else null)
            if (marks.isNotEmpty()) painter.badge(c, rectOf(b), marks.joinToString(" · "))
        }
        if (countOn && selection.isNotEmpty()) {
            c.drawText("선택 ${selection.size}개", width / 2f, hintPaint.textSize * (if (hidden.isNotEmpty()) 2.6f else 1.4f), hintPaint)
        }
        if (coordsOn && selection.isNotEmpty()) {
            val r = selectionBox(rect)
            c.drawText(Geometry.describe(r.left, r.top, r.width(), r.height()), width / 2f, height - hintPaint.textSize * 0.8f, hintPaint)
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
        if (sketching) {
            if (strokes.isEmpty() && stroke.isEmpty()) {
                c.drawText("손가락으로 버튼 모양을 그린 뒤 '✔ 버튼으로 만들기'를 누르세요", width / 2f, hintPaint.textSize * 3f, hintPaint)
            }
            drawStrokes(c, strokes, 0f, 0f)
            if (stroke.size >= 2) drawStrokes(c, listOf(stroke.toFloatArray()), 0f, 0f)
        }
        if (drag == Drag.DRAW) {
            c.drawRect(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY), drawPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        if (sketching) return sketchTouch(e)
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
                    longFired = false
                    if (longPressOn && hit != null && !multi) postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
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
                if (abs(x - downX) > slop || abs(y - downY) > slop) { moved = true; removeCallbacks(longPress) }
                when (drag) {
                    Drag.DRAW -> { curX = snapPx(x); curY = snapPx(y) }
                    Drag.SLIDE -> hitAt(x, y)?.let { selection += it }
                    Drag.MOVE -> if (moved && starts.isNotEmpty()) {
                        beforeChange()
                        // 선택된 것들이 함께 움직이되, 어느 것도 화면 밖으로 나가지 않게 한다.
                        var dx = (snapPx(x - downX) / width).coerceIn(-box.left, 1f - box.right)
                        var dy = (snapPx(y - downY) / height).coerceIn(-box.top, 1f - box.bottom)
                        if (snapEdges) {
                            // 가장자리에서 1.5% 안으로 오면 딱 붙인다.
                            val e = 0.015f
                            if (box.left + dx < e) dx = -box.left else if (1f - (box.right + dx) < e) dx = 1f - box.right
                            if (box.top + dy < e) dy = -box.top else if (1f - (box.bottom + dy) < e) dy = 1f - box.bottom
                        }
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
                removeCallbacks(longPress)
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
