package com.controlpad.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 업데이트 탭(ENN 에서 새로 꾸밈).
 *  · 맨 위: 강조색이 은은하게 번지는 큰 카드 — 지금 빌드, 코드명, 채널, 상태 한 줄, '업데이트 확인' 버튼
 *  · 채널 카드 두 개(알파 · 최신): 각 채널의 지금 빌드를 보여 주고, 눌러서 받을 채널을 바꾼다
 *  · 자동 업데이트 카드
 *  · 업데이트 내역: 버전마다 카드, 맨 위는 'NEW' 표시
 */
class UpdateActivity : BaseActivity() {
    private var stableInfo: Updater.Info? = null
    private var alphaInfo: Updater.Info? = null
    private lateinit var statusLine: TextView
    private lateinit var channels: LinearLayout
    private var showAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(Ui.header(this, "업데이트", "새 빌드 확인과 업데이트 내역"))
        root.addView(Ui.tabs(this))

        root.addView(hero())

        root.addView(sectionTitle("채널", "받을 채널을 눌러 고릅니다"))
        channels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(channels)
        fillChannels()

        root.addView(sectionTitle("설정", null))
        var autoCard: View? = null
        fun auto(): View = Ui.featureCard(this, "⚡", "자동 업데이트", "앱을 켤 때 새 빌드가 있으면 묻지 않고 바로 설치합니다", Updater.auto) {
            Updater.auto = !Updater.auto
            val old = autoCard ?: return@featureCard
            val parent = old.parent as LinearLayout
            val i = parent.indexOfChild(old)
            parent.removeViewAt(i)
            autoCard = auto().also { Ui.restyle(it); parent.addView(it, i, old.layoutParams) }
        }
        autoCard = auto()
        root.addView(autoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root.addView(sectionTitle("업데이트 내역", "바뀐 점을 버전마다 모았습니다"))
        val history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val more = Button(this)
        fun fillHistory() {
            history.removeAllViews()
            val list = if (showAll) Changelog.entries else Changelog.entries.take(6)
            list.forEachIndexed { i, e -> history.addView(entryCard(e, i == 0), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
            more.visibility = if (showAll || Changelog.entries.size <= 6) View.GONE else View.VISIBLE
        }
        more.text = "이전 내역 모두 보기"
        more.setOnClickListener { showAll = true; fillHistory(); Ui.restyle(history) }
        fillHistory()
        root.addView(history)
        root.addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        setContentView(ScrollView(this).apply { addView(root) })
        loadLatest()
    }

    /** 맨 위 큰 카드. */
    private fun hero(): View {
        val t = Themes.current
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            // 왼쪽 위에서 강조색이 은은하게 번지는 바탕
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Ui.accent() and 0x00FFFFFF or 0x40000000, Ui.surface(), Ui.surface())).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Ui.accent() and 0x00FFFFFF or 0x66000000)
            }
        }
        val chips = LinearLayout(this)
        fun chip(text: String, filled: Boolean) = chips.addView(TextView(this).apply {
            this.text = text; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (filled) Ui.onAccent() else Ui.accent())
            background = Ui.rounded(this@UpdateActivity, if (filled) Ui.accent() else 0, 12, if (filled) 0 else Ui.accent())
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        chip(if (Updater.alpha) "알파 채널" else "최신 채널", true)
        val code = Updater.installedName(this)
        if (code != Updater.BUILD) chip("코드명 $code", false)
        card.addView(chips)
        card.addView(TextView(this).apply {
            text = "지금 쓰는 빌드"; textSize = 12.5f; setTextColor(Ui.muted()); setPadding(0, dp(14), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = Updater.BUILD; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(t.text)
        })
        statusLine = TextView(this).apply {
            text = "새 빌드를 확인하는 중…"; textSize = 14f; setTextColor(Ui.muted()); setPadding(0, dp(4), 0, 0)
        }
        card.addView(statusLine)
        card.addView(Button(this).apply {
            text = "업데이트 확인"; tag = Ui.PRIMARY
            setOnClickListener { Updater.check(this@UpdateActivity); loadLatest() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        return card
    }

    /** 채널 카드 두 개(알파 · 최신). 지금 받는 채널은 강조 테두리와 '받는 중' 표시. */
    private fun fillChannels() {
        channels.removeAllViews()
        val mine = Updater.installedCode(this)
        fun card(alpha: Boolean, info: Updater.Info?): View {
            val on = Updater.alpha == alpha
            val name = info?.let { it.build.ifEmpty { it.name } } ?: "확인 중…"
            val newer = info != null && info.code > mine
            val note = (if (alpha) "가장 새 빌드 · 실험실과 평가단" else "한 단계 전 빌드 · 더 안정적") +
                if (newer) "\n새 빌드가 있습니다 ↑" else if (info != null) "\n지금 쓰는 것과 같거나 이전 빌드" else ""
            return Ui.featureCard(this, if (alpha) "🧪" else "✅", (if (alpha) "알파  ·  " else "최신  ·  ") + name, note, null,
                if (on) "받는 중" else null) {
                if (Updater.alpha != alpha) {
                    Updater.alpha = alpha
                    Updater.check(this)          // 채널을 바꾸면 그 채널의 새 빌드를 바로 확인한다
                    recreate()                   // 탭(실험실)과 맨 위 카드도 바뀐다
                }
            }.apply {
                if (on) background = Ui.rounded(this@UpdateActivity, Ui.surface(), 18, Ui.accent())
            }
        }
        channels.addView(Ui.grid(this, listOf(card(true, alphaInfo), card(false, stableInfo)), 260))
        Ui.restyle(channels)
    }

    private fun sectionTitle(title: String, note: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(24), dp(2), 0)
        addView(TextView(this@UpdateActivity).apply {
            text = title; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
        })
        if (note != null) addView(TextView(this@UpdateActivity).apply { text = note; textSize = 12f; setTextColor(Ui.muted()) })
    }

    /** 업데이트 내역 한 개: 둥근 카드에 버전(빌드 · 코드명)과 바뀐 점. 맨 위는 강조 테두리와 NEW. */
    private fun entryCard(e: Changelog.Entry, first: Boolean): View {
        val t = Themes.current
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.rounded(this@UpdateActivity, Ui.surface(), 16,
                if (first) Ui.accent() and 0x00FFFFFF or 0x99000000.toInt() else Ui.line())
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = e.build.ifEmpty { e.version }; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (first) Ui.accent() else t.text)
        })
        if (e.build.isNotEmpty() && e.build != e.version) top.addView(TextView(this).apply {
            text = "  " + e.version; textSize = 12f; setTextColor(Ui.muted())
        })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (first) top.addView(TextView(this).apply {
            text = "NEW"; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.onAccent())
            background = Ui.rounded(this@UpdateActivity, Ui.accent(), 10)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        card.addView(top)
        for (n in e.notes) card.addView(LinearLayout(this).apply {
            setPadding(0, dp(6), 0, 0)
            addView(TextView(this@UpdateActivity).apply {
                text = "•"; textSize = 14f; setTextColor(if (first) Ui.accent() else Ui.muted()); setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(this@UpdateActivity).apply {
                text = n; textSize = 14f; setLineSpacing(0f, 1.2f)
                setTextColor(t.text and 0x00FFFFFF or 0xDD000000.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        return card
    }

    private fun loadLatest() {
        statusLine.text = "새 빌드를 확인하는 중…"
        Thread {
            val stable = Updater.latest(false)
            val alpha = Updater.latest(true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                stableInfo = stable; alphaInfo = alpha
                val mine = Updater.installedCode(this)
                val mineChannel = if (Updater.alpha) alpha else stable
                statusLine.text = when {
                    mineChannel == null -> "새 빌드를 확인하지 못했습니다 · 인터넷 연결을 확인하세요"
                    mineChannel.code > mine -> "새 빌드 ${mineChannel.label} 이(가) 있습니다"
                    else -> "✓ 받는 채널의 가장 새 빌드를 쓰고 있습니다"
                }
                statusLine.setTextColor(if (mineChannel != null && mineChannel.code > mine) Ui.accent() else Ui.muted())
                fillChannels()
            }
        }.start()
    }
}
