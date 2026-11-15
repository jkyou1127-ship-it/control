package com.controlpad.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 기능 탭(UIUPV! 에서 새로 꾸밈): 레이아웃 목록처럼 카드 격자로 보여 준다.
 * 위쪽에 켜진 개수 요약과 검색, 그 아래 묶음(기능 · 모바일 · 기타)마다 제목과 카드들.
 * 카드는 아이콘 · 스위치 · 제목 · 설명으로 되어 있고, 카드를 누르면 켜고 끈다.
 */
class FeaturesActivity : BaseActivity() {
    private class Item(val icon: String, val title: String, val note: String, val get: () -> Boolean, val set: (Boolean) -> Unit, val badge: String? = null)

    private class Group(val icon: String, val title: String, val note: String, val items: List<Item>)

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var query = ""

    private val mobileIcons = mapOf(
        "mMaxBright" to "☀", "mBlockVolume" to "🔇", "mTimer" to "⏱", "mSecure" to "🔒", "mPausedBanner" to "⏸",
        "mAutoTheme" to "🌓", "mReconnect" to "🔁", "mLowBattery" to "🔋", "mScreenOff" to "💤", "mShake" to "📳",
        "mLockRotation" to "🔄", "mReleaseOnDrop" to "✋", "mPowerSave" to "⚡", "mClock" to "🕒", "mLagWarn" to "🐢",
        "mWeakWifi" to "📶", "mConnectBuzz" to "📡",
    )

    private fun groups(): List<Group> {
        val promoted = Labs.settingFeatures().map { f ->
            Item("✨", f.title, f.description, { Labs.isOn(f.id) }, { Labs.set(f.id, it) }, if (f.id in Labs.graduated) null else "알파")
        }
        val etc = listOf(
            Item("⌨", "눌리고 있는 키 표시", "실행 화면 위쪽에 지금 입력 중인 키를 보여 줍니다", { Link.showPressed }, { Link.showPressed = it }),
            Item("🏷", "버튼에 키 이름 표시", "버튼 아래쪽에 그 버튼이 입력하는 키를 작게 보여 줍니다", { Link.showKeyNames }, { Link.showKeyNames = it }),
            Item("📳", "버튼을 누를 때 진동", "버튼을 누르는 순간 짧게 진동합니다", { Link.haptic }, { Link.prefs.edit().putBoolean("haptic", it).apply() }),
            Item("🔌", "연결이 끊기면 알림", "실행 중에 PC 연결이 끊기면 진동과 알림으로 알려 줍니다", { Link.disconnectAlert }, { Link.disconnectAlert = it }),
        )
        val mobile = Mobile.toggles.map { t -> Item(mobileIcons[t.key] ?: "📱", t.title, t.note, { Mobile.isOn(t.key) }, { Mobile.set(t.key, it) }) }
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
        root.addView(Ui.header(this, "기능", "켜고 끄는 기능을 한곳에서"))
        root.addView(Ui.tabs(this))

        // 위쪽 줄: 켜진 개수(왼쪽) + 검색(오른쪽)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        summary = TextView(this).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.accent())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.rounded(this@FeaturesActivity, Ui.surface(), 20, Ui.accent() and 0x00FFFFFF or 0x66000000)
        }
        top.addView(summary)
        top.addView(EditText(this).apply {
            hint = "🔍  기능 검색"; setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { query = s.toString().trim(); fill() }
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        root.addView(top)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        fill()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateSummary(all: List<Group> = groups()) {
        summary.text = "켜짐 ${all.sumOf { g -> g.items.count { it.get() } }} / ${all.sumOf { it.items.size }}"
    }

    private fun fill() {
        list.removeAllViews()
        val all = groups()
        updateSummary(all)
        for (g in all) {
            val items = g.items.filter { query.isEmpty() || it.title.contains(query, true) || it.note.contains(query, true) }
            if (items.isEmpty() && query.isNotEmpty()) continue
            list.addView(groupHeader(g, items.count { it.get() }, items.size))
            val cards = items.map { item -> card(item) }.toMutableList<View>()
            if (g.title == "모바일" && query.isEmpty()) cards += cardSizeCard()
            if (cards.isEmpty()) list.addView(TextView(this).apply { text = "아직 없습니다"; setPadding(dp(4), dp(8), 0, 0) })
            else list.addView(Ui.grid(this, cards, 240))
        }
        if (list.childCount == 0) list.addView(TextView(this).apply {
            text = "'$query' 에 맞는 기능이 없습니다"; setPadding(0, dp(24), 0, 0); gravity = Gravity.CENTER
        })
        Ui.restyle(list)
    }

    /** 카드 하나. 누르면 켜고 끈 뒤 이 카드만 새로 만들어 바꿔 끼운다(스크롤 위치 유지). */
    private fun card(item: Item): View {
        var self: View? = null
        val view = Ui.featureCard(this, item.icon, item.title, item.note, item.get(), item.badge) {
            item.set(!item.get())
            if (item.title.startsWith("시스템 다크 모드")) { recreate(); return@featureCard }   // 테마가 바뀌므로 다시 그린다
            val old = self ?: return@featureCard
            val parent = old.parent as? LinearLayout ?: return@featureCard
            val i = parent.indexOfChild(old)
            val params = old.layoutParams
            parent.removeViewAt(i)
            parent.addView(card(item).also { Ui.restyle(it) }, i, params)
            updateSummary()
        }
        self = view
        return view
    }

    private fun groupHeader(g: Group, on: Int, total: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(22), dp(2), 0)
        addView(TextView(this@FeaturesActivity).apply { text = g.icon; textSize = 20f })
        val texts = LinearLayout(this@FeaturesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        texts.addView(TextView(this@FeaturesActivity).apply {
            text = g.title; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
        })
        texts.addView(TextView(this@FeaturesActivity).apply { text = g.note; textSize = 12f; setTextColor(Ui.muted()) })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (total > 0) addView(TextView(this@FeaturesActivity).apply {
            text = "$on/$total"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.accent())
        })
    }

    /** 목록 카드 크기: 세 칸 중 하나를 고르는 카드. */
    private fun cardSizeCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@FeaturesActivity, Ui.surface(), 18, Ui.line())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(TextView(this@FeaturesActivity).apply {
            text = "🗂"; textSize = 20f; gravity = Gravity.CENTER
            background = Ui.rounded(this@FeaturesActivity, Themes.current.bg, 12)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(TextView(this@FeaturesActivity).apply {
            text = "목록 카드 크기"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
            setPadding(0, dp(12), 0, 0)
        })
        val seg = LinearLayout(this@FeaturesActivity).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = Ui.rounded(this@FeaturesActivity, Themes.current.bg, 12, Ui.line())
        }
        fun paint() {
            for (i in 0 until seg.childCount) (seg.getChildAt(i) as TextView).apply {
                val sel = i == Mobile.cardSize
                background = if (sel) Ui.rounded(this@FeaturesActivity, Ui.accent(), 9) else null
                setTextColor(if (sel) Ui.onAccent() else Themes.current.text)
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
