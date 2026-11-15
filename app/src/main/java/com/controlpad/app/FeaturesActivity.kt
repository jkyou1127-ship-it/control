package com.controlpad.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 기능 · 기타 탭(hd12 에서 새로 꾸밈).
 * 위쪽에 켜짐 개수 요약과 검색, 아래로 묶음(기능 · 모바일 · 기타)마다 둥근 카드가 이어지고,
 * 카드 안의 각 줄은 '제목 + 설명 + 스위치'다. 줄 어디를 눌러도 스위치가 바뀐다.
 */
class FeaturesActivity : BaseActivity() {
    /** 한 줄짜리 켜고 끄는 설정. */
    private class Item(val title: String, val note: String, val get: () -> Boolean, val set: (Boolean) -> Unit)

    private class Group(val icon: String, val title: String, val note: String, val items: List<Item>)

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var query = ""

    private fun groups(): List<Group> {
        val promoted = Labs.settingFeatures().map { f ->
            Item(f.title + if (f.id in Labs.graduated) "" else "  · 알파", f.description, { Labs.isOn(f.id) }, { Labs.set(f.id, it) })
        }
        val etc = listOf(
            Item("눌리고 있는 키 표시", "실행 화면 위쪽에 지금 입력 중인 키를 보여 줍니다", { Link.showPressed }, { Link.showPressed = it }),
            Item("버튼에 키 이름 표시", "버튼 아래쪽에 그 버튼이 입력하는 키를 작게 보여 줍니다", { Link.showKeyNames }, { Link.showKeyNames = it }),
            Item("버튼을 누를 때 진동", "버튼을 누르는 순간 짧게 진동합니다", { Link.haptic }, { Link.prefs.edit().putBoolean("haptic", it).apply() }),
            Item("연결이 끊기면 알림", "실행 중에 PC 연결이 끊기면 진동과 알림으로 알려 줍니다", { Link.disconnectAlert }, { Link.disconnectAlert = it }),
        )
        val mobile = Mobile.toggles.map { t -> Item(t.title, t.note, { Mobile.isOn(t.key) }, { Mobile.set(t.key, it) }) }
        return listOf(
            Group("⚡", "기능", "평가단 건의로 실험실에서 올라온 기능", promoted),
            Group("📱", "모바일", "태블릿을 쓰는 동안의 편의 기능", mobile),
            Group("⚙", "기타", "표시와 진동, 알림", etc),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(Ui.header(this, "기능 · 기타", "켜고 끄는 기능을 한곳에서"))
        root.addView(Ui.tabs(this, 1))

        // 요약 카드: 켜진 개수 + 모두 켜기/끄기 없이 한눈에
        summary = TextView(this).apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.rounded(this@FeaturesActivity, Ui.surface(), 18, Ui.accent() and 0x00FFFFFF or 0x66000000)
        }
        root.addView(summary)

        // 검색
        root.addView(EditText(this).apply {
            hint = "🔍  기능 검색 (예: 진동, 밝기, 알림)"; setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { query = s.toString().trim(); fill() }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        fill()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun fill() {
        list.removeAllViews()
        val all = groups()
        val on = all.sumOf { g -> g.items.count { it.get() } }
        val total = all.sumOf { it.items.size }
        summary.text = "켜진 기능 $on / $total"
        for (g in all) {
            val items = g.items.filter { query.isEmpty() || it.title.contains(query, true) || it.note.contains(query, true) }
            if (items.isEmpty() && query.isNotEmpty()) continue
            list.addView(groupHeader(g, items.count { it.get() }, items.size))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(this@FeaturesActivity, Ui.surface(), 18, Ui.line())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            if (items.isEmpty()) card.addView(TextView(this).apply {
                text = "아직 없습니다"; textSize = 13f; setPadding(dp(14), dp(12), dp(14), dp(12))
            })
            items.forEachIndexed { i, item ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(Ui.line()) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(14); rightMargin = dp(14) })
                card.addView(row(item))
            }
            // 모바일 묶음 끝에 목록 카드 크기(세 칸 중 하나 고르기)
            if (g.title == "모바일" && query.isEmpty()) {
                card.addView(View(this).apply { setBackgroundColor(Ui.line()) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(14); rightMargin = dp(14) })
                card.addView(cardSizeRow())
            }
            list.addView(card)
        }
        if (list.childCount == 0) list.addView(TextView(this).apply {
            text = "'$query' 에 맞는 기능이 없습니다"; setPadding(0, dp(24), 0, 0); gravity = Gravity.CENTER
        })
        Ui.restyle(list)
    }

    private fun groupHeader(g: Group, on: Int, total: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(18), dp(4), dp(8))
        addView(TextView(this@FeaturesActivity).apply {
            text = g.icon; textSize = 18f; gravity = Gravity.CENTER
            background = Ui.rounded(this@FeaturesActivity, Ui.accent() and 0x00FFFFFF or 0x33000000, 10)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val texts = LinearLayout(this@FeaturesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        texts.addView(TextView(this@FeaturesActivity).apply {
            text = g.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
        })
        texts.addView(TextView(this@FeaturesActivity).apply { text = g.note; textSize = 12f })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (total > 0) addView(TextView(this@FeaturesActivity).apply {
            text = "$on/$total 켜짐"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.accent())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ui.rounded(this@FeaturesActivity, 0, 12, Ui.accent())
        })
    }

    private fun row(item: Item): View {
        val sw = Switch(this).apply { isChecked = item.get(); isClickable = false; isFocusable = false }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.accent() and 0x00FFFFFF or 0x22000000), null, Ui.rounded(this@FeaturesActivity, -1, 14))
            val texts = LinearLayout(this@FeaturesActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@FeaturesActivity).apply {
                text = item.title; textSize = 15f; setTextColor(Themes.current.text); typeface = Typeface.DEFAULT_BOLD
            })
            if (item.note.isNotEmpty()) texts.addView(TextView(this@FeaturesActivity).apply {
                text = item.note; textSize = 12.5f; setLineSpacing(0f, 1.15f)
                setTextColor(Themes.current.text and 0x00FFFFFF or 0x99000000.toInt()); setPadding(0, dp(2), dp(8), 0)
            })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw)
            setOnClickListener {
                val next = !item.get()
                item.set(next)
                sw.isChecked = next
                val all = groups()
                summary.text = "켜진 기능 ${all.sumOf { g -> g.items.count { it.get() } }} / ${all.sumOf { it.items.size }}"
                if (item.title.startsWith("시스템 다크 모드")) recreate()   // 테마가 바뀌므로 화면을 다시 그린다
            }
        }
    }

    private fun cardSizeRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(this@FeaturesActivity).apply {
            text = "목록 카드 크기"; textSize = 15f; setTextColor(Themes.current.text); typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@FeaturesActivity).apply {
            text = "레이아웃 목록의 카드와 미리보기 크기"; textSize = 12.5f
            setTextColor(Themes.current.text and 0x00FFFFFF or 0x99000000.toInt())
        })
        // 세 칸짜리 고르기(선택한 칸만 강조색)
        val seg = LinearLayout(this@FeaturesActivity).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = Ui.rounded(this@FeaturesActivity, Themes.current.bg, 12, Ui.line())
        }
        fun paint() {
            for (i in 0 until seg.childCount) (seg.getChildAt(i) as TextView).apply {
                val sel = i == Mobile.cardSize
                background = if (sel) Ui.rounded(this@FeaturesActivity, Ui.accent(), 9) else null
                setTextColor(if (sel) (if (Themes.luminance(Ui.accent()) > 0.5f) 0xFF0B1A24.toInt() else 0xFFFFFFFF.toInt()) else Themes.current.text)
                typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        Mobile.cardSizes.forEachIndexed { i, name ->
            seg.addView(TextView(this@FeaturesActivity).apply {
                text = name; gravity = Gravity.CENTER; textSize = 14f; setPadding(0, dp(8), 0, dp(8)); isClickable = true
                setOnClickListener { Mobile.cardSize = i; paint() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint()
        addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
}
