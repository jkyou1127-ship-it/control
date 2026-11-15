package com.controlpad.app

import android.os.Bundle
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView

/** 설정에서 따로 뺀 화면: 실험실에서 올라온 기능(켜고 끄기)과 기타 설정. */
class FeaturesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = SectionedLayout(this).apply { setPadding(pad, pad, pad, pad) }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 14f; setLineSpacing(0f, 1.2f) }
        fun check(label: String, on: Boolean, set: (Boolean) -> Unit) = root.addView(CheckBox(this).apply {
            text = label; isChecked = on
            setOnCheckedChangeListener { _, v -> set(v) }
        })

        root.addView(Ui.header(this, "기능 · 기타", "켜고 끄는 기능과 그 밖의 설정"))

        root.addView(Ui.section(this, "기능"))
        val promoted = Labs.settingFeatures()
        if (promoted.isEmpty()) root.addView(note("실험실에서 올라온, 켜고 끄는 기능이 아직 없습니다."))
        else {
            root.addView(note("평가단 건의로 실험실에서 올라온 기능입니다. 처음에는 켜져 있습니다."))
            for (f in promoted) check(f.title + if (f.id in Labs.graduated) "" else "  (알파)", Labs.isOn(f.id)) { Labs.set(f.id, it) }
        }

        root.addView(Ui.section(this, "기타"))
        check("눌리고 있는 키 표시 — 실행 화면 위쪽에 지금 입력 중인 키를 보여 줍니다", Link.showPressed) { Link.showPressed = it }
        check("버튼에 키 이름 표시 — 버튼 아래쪽에 그 버튼이 입력하는 키를 작게 보여 줍니다", Link.showKeyNames) { Link.showKeyNames = it }
        check("버튼을 누를 때 진동", Link.haptic) { Link.prefs.edit().putBoolean("haptic", it).apply() }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
