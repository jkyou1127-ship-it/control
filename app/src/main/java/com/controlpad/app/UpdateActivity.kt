package com.controlpad.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 설정에서 따로 뺀 업데이트 화면: 지금 버전 카드, 두 채널의 최신 버전, 업데이트 설정, 업데이트 내역. */
class UpdateActivity : BaseActivity() {
    private lateinit var stableText: TextView
    private lateinit var alphaText: TextView
    private var showAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Themes.current
        val pad = dp(16)
        val root = SectionedLayout(this).apply { setPadding(pad, pad, pad, pad) }
        fun muted(text: String, size: Float = 13f) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(t.text and 0x00FFFFFF or 0x99000000.toInt())
        }

        root.addView(Ui.header(this, "업데이트", "새 버전 확인과 업데이트 내역"))
        root.addView(Ui.tabs(this))

        // 지금 버전: 강조색 띠를 두른 큰 카드
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = Ui.rounded(this@UpdateActivity, Ui.surface(), 18, Ui.accent() and 0x00FFFFFF or 0x88000000.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }
        }
        hero.addView(muted("지금 쓰는 빌드  ·  코드명 " + Updater.installedName(this)))
        hero.addView(TextView(this).apply {
            text = Updater.BUILD; textSize = 30f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(t.text)
        })
        hero.addView(TextView(this).apply {
            text = if (Updater.alpha) "알파 채널" else "정식 채널"
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (Themes.luminance(Ui.accent()) > 0.5f) 0xFF0B1A24.toInt() else 0xFFFFFFFF.toInt())
            background = Ui.rounded(this@UpdateActivity, Ui.accent(), 12)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, dp(6), 0, dp(12)) }
        })
        // 두 채널의 최신 버전
        val channels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun channelBox(label: String): TextView {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.rounded(this@UpdateActivity, t.bg, 12, Ui.line())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
            }
            box.addView(muted(label, 12f))
            val v = TextView(this).apply { text = "확인 중…"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(t.text) }
            box.addView(v)
            channels.addView(box)
            return v
        }
        stableText = channelBox("정식 최신")
        alphaText = channelBox("알파 최신")
        hero.addView(channels)
        hero.addView(Button(this).apply {
            text = "지금 업데이트 확인"; tag = Ui.PRIMARY
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) }
            setOnClickListener { Updater.check(this@UpdateActivity); loadLatest() }
        })
        root.addView(hero)

        root.addView(Ui.section(this, "설정"))
        root.addView(CheckBox(this).apply {
            text = "자동 업데이트 — 앱을 켤 때 새 버전이 있으면 묻지 않고 바로 설치합니다"
            isChecked = Updater.auto
            setOnCheckedChangeListener { _, on -> Updater.auto = on }
        })
        root.addView(CheckBox(this).apply {
            text = "알파 빌드 받기 — 최신 빌드를 바로 받습니다. 끄면 한 번 전의 빌드(정식)를 받습니다"
            isChecked = Updater.alpha
            setOnCheckedChangeListener { _, on ->
                Updater.alpha = on
                Updater.check(this@UpdateActivity)      // 채널을 바꾸면 그 채널의 새 버전을 바로 확인한다
                recreate()
            }
        })

        root.addView(Ui.section(this, "업데이트 내역"))
        val history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val more = Button(this)
        fun fillHistory() {
            history.removeAllViews()
            val list = if (showAll) Changelog.entries else Changelog.entries.take(6)
            list.forEachIndexed { i, e -> history.addView(entryView(e, i == 0, i == list.size - 1)) }
            more.visibility = if (showAll || Changelog.entries.size <= 6) View.GONE else View.VISIBLE
        }
        more.text = "이전 내역 모두 보기"
        more.setOnClickListener { showAll = true; fillHistory(); Ui.restyle(history) }
        fillHistory()
        root.addView(history)
        root.addView(more)

        setContentView(ScrollView(this).apply { addView(root) })
        loadLatest()
    }

    /** 내역 한 줄: 왼쪽에 점과 세로선(타임라인), 오른쪽에 버전과 바뀐 점. */
    private fun entryView(e: Changelog.Entry, first: Boolean, last: Boolean): View {
        val t = Themes.current
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rail = object : View(this) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) {
                val cx = width / 2f
                val cy = dp(13).toFloat()
                paint.color = Ui.line(); paint.strokeWidth = dp(2).toFloat()
                if (!first) c.drawLine(cx, 0f, cx, cy, paint)
                if (!last) c.drawLine(cx, cy, cx, height.toFloat(), paint)
                paint.color = if (first) Ui.accent() else t.text and 0x00FFFFFF or 0x66000000
                c.drawCircle(cx, cy, dp(if (first) 6 else 4).toFloat(), paint)
            }
        }
        row.addView(rail, LinearLayout.LayoutParams(dp(24), -1))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), 0, dp(14))
        }
        body.addView(TextView(this).apply {
            text = e.label + if (first) "  ·  최신" else ""
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (first) Ui.accent() else t.text)
        })
        for (n in e.notes) body.addView(TextView(this).apply {
            text = "· $n"; textSize = 14f; setLineSpacing(0f, 1.15f)
            setTextColor(t.text and 0x00FFFFFF or 0xCC000000.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun loadLatest() {
        stableText.text = "확인 중…"; alphaText.text = "확인 중…"
        Thread {
            val stable = Updater.latest(false)
            val alpha = Updater.latest(true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val mine = Updater.installedCode(this)
                fun show(v: TextView, info: Updater.Info?) {
                    v.text = when {
                        info == null -> "확인 못 함"
                        info.code > mine -> info.build.ifEmpty { info.name } + "  ↑"
                        else -> info.build.ifEmpty { info.name }
                    }
                    v.setTextColor(if (info != null && info.code > mine) Ui.accent() else Themes.current.text)
                }
                show(stableText, stable); show(alphaText, alpha)
            }
        }.start()
    }
}
