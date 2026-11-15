package com.controlpad.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * 나만의 테마를 만들고 고치는 화면. 레이아웃 편집 화면과는 별개다.
 * 여러 개를 만들어 이름을 붙여 둘 수 있고, 저장하면 설정의 테마 목록에 나온다.
 */
class ThemeEditorActivity : BaseActivity() {
    // 고치는 중인 값들. '저장'을 누르기 전에는 미리보기에만 반영된다.
    private var editing = -1            // Themes.user 안에서의 위치. -1 이면 아직 저장하지 않은 새 테마
    private var name = ""
    private var bg = 0
    private var text = 0
    private var card = 0
    private var base = 0
    private var strength = 1f
    private var stroke = 1.5f
    private var colorText = false

    private lateinit var preview: ThemePreview
    private lateinit var form: LinearLayout
    private lateinit var picker: Spinner

    private fun draft() = Themes.make(name.ifBlank { "내 테마" }, bg, text, strength, stroke, colorText, base, card)

    private fun load(t: PadTheme, index: Int, newName: String = t.name) {
        editing = index
        name = newName; bg = t.bg; text = t.text; card = t.card; base = t.base
        strength = t.strength; stroke = t.stroke; colorText = t.colorText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { this.text = "테마 에디터"; textSize = 24f; setTextColor(Themes.current.text) })

        picker = Spinner(this)
        root.addView(picker)
        preview = ThemePreview(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(130)).apply { topMargin = dp(8) })
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(form)
        setContentView(ScrollView(this).apply { addView(root) })

        // 지금 쓰는 테마가 직접 만든 것이면 그것을 고치고, 아니면 지금 테마를 복사한 새 테마로 시작한다.
        val at = Themes.index - Themes.all.size
        if (at >= 0) load(Themes.user[at], at) else load(Themes.current, -1, "내 테마 " + (Themes.user.size + 1))
        fillPicker()
        buildForm()
    }

    /** 맨 위 목록: 직접 만든 테마들 + 새로 만들기. */
    private fun fillPicker() {
        val items = Themes.user.map { it.name } + "＋ 새 테마 (지금 값을 복사)"
        picker.onItemSelectedListener = null
        picker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        picker.setSelection(if (editing >= 0) editing else items.size - 1, false)
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == (if (editing >= 0) editing else items.size - 1)) return
                if (pos < Themes.user.size) load(Themes.user[pos], pos)
                else { editing = -1; name = "내 테마 " + (Themes.user.size + 1) }     // 색은 그대로 두고 새 이름만
                fillPicker()
                buildForm()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun buildForm() {
        form.removeAllViews()
        preview.show(draft())
        fun label(t: String) = form.addView(TextView(this).apply { this.text = t; setPadding(0, dp(10), 0, 0) })

        label("이름")
        form.addView(EditText(this).apply {
            setText(name); setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { name = s.toString().trim() }
            })
        })

        fun color(title: String, get: () -> Int, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
            val swatch = View(this)
            val shown = TextView(this)
            fun show() {
                swatch.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(get()); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Themes.current.text)
                }
                shown.text = title + "  #%06X".format(get() and 0xFFFFFF)
            }
            show()
            row.addView(swatch, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
            row.addView(shown, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                this.text = "바꾸기"
                setOnClickListener { pickColor(this@ThemeEditorActivity, get()) { set(it); show(); preview.show(draft()) } }
            })
            form.addView(row)
        }
        color("배경색", { bg }, { bg = it })
        color("글자색", { text }, { text = it })
        color("목록 항목 배경색", { card }, { card = it })
        color("버튼 바탕색 (눌리지 않은 버튼이 이 색 쪽으로 어두워지거나 밝아짐)", { base }, { base = it })

        fun slider(title: (Int) -> String, max: Int, value: Int, set: (Int) -> Unit) {
            val shown = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
            val bar = SeekBar(this).apply { this.max = max; progress = value.coerceIn(0, max) }
            shown.text = title(bar.progress)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    shown.text = title(p); set(p); preview.show(draft())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            form.addView(shown); form.addView(bar)
        }
        // 진하기 10~160%, 테두리 0.5~5
        slider({ "버튼 색 진하기: ${it * 10 + 10}%" }, 15, ((strength * 100).toInt() - 10) / 10) { strength = (it * 10 + 10) / 100f }
        slider({ "테두리 굵기: ${(it + 1) * 0.5f}" }, 9, (stroke / 0.5f).toInt() - 1) { stroke = (it + 1) * 0.5f }
        form.addView(CheckBox(this).apply {
            this.text = "글자를 버튼 색으로 표시 (네온 느낌)"; isChecked = colorText
            setOnCheckedChangeListener { _, on -> colorText = on; preview.show(draft()) }
        })

        val buttons = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        buttons.addView(Button(this).apply { this.text = "저장하고 적용"; setOnClickListener { save() } })
        if (editing >= 0) buttons.addView(Button(this).apply { this.text = "삭제"; setOnClickListener { delete() } })
        buttons.addView(Button(this).apply { this.text = "닫기"; setOnClickListener { finish() } })
        form.addView(buttons)
        form.addView(TextView(this).apply {
            this.text = "저장한 테마는 설정의 테마 목록 맨 아래에 나옵니다. 배경이 밝으면 목록과 설정 화면도 밝은 모양으로 바뀝니다."
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun save() {
        val t = draft()
        if (editing >= 0) Themes.user[editing] = t
        else { Themes.user += t; editing = Themes.user.size - 1 }
        Themes.saveUser()
        Themes.index = Themes.all.size + editing
        Toast.makeText(this, "'${t.name}' 테마를 저장하고 적용했습니다", Toast.LENGTH_SHORT).show()
        recreate()      // 이 화면에도 새 테마를 입힌다
    }

    private fun delete() {
        val at = editing
        AlertDialog.Builder(this).setMessage("'${Themes.user[at].name}' 테마를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                val chosen = Themes.index - Themes.all.size
                Themes.user.removeAt(at)
                Themes.saveUser()
                // 쓰고 있던 테마를 지웠으면 기본 테마로, 그 뒤의 테마를 쓰고 있었으면 한 칸 당긴다.
                if (chosen == at) Themes.index = 0 else if (chosen > at) Themes.index = Themes.all.size + chosen - 1
                recreate()
            }
            .setNegativeButton("취소", null).show()
    }
}
