package com.controlpad.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * 새 버전 알림 창(NWE 01): 둥근 카드에 새 빌드 번호를 크게, 코드명과 바뀐 점을 아래에 보여 준다.
 * '지금 업데이트'를 누르면 같은 창 안에서 진행 막대가 차오르고, 다 받으면 설치 화면으로 넘어간다.
 */
object UpdateDialog {
    fun show(a: Activity, info: Updater.Info, alpha: Boolean, start: (onProgress: (Int) -> Unit, onDone: (String?) -> Unit) -> Unit) {
        val t = Themes.current
        val sub = t.text and 0x00FFFFFF or 0x99000000.toInt()
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(24), a.dp(22), a.dp(24), a.dp(18))
            background = Ui.rounded(a, t.card, 24, Ui.accent() and 0x00FFFFFF or 0x66000000)
        }
        card.addView(TextView(a).apply {
            text = if (alpha) "새 알파 빌드" else "새 버전"
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (Themes.luminance(Ui.accent()) > 0.5f) 0xFF0B1A24.toInt() else 0xFFFFFFFF.toInt())
            background = Ui.rounded(a, Ui.accent(), 12)
            setPadding(a.dp(10), a.dp(3), a.dp(10), a.dp(3))
        }, LinearLayout.LayoutParams(-2, -2))
        card.addView(TextView(a).apply {
            text = info.build.ifEmpty { info.name }
            textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(t.text)
            setPadding(0, a.dp(10), 0, 0)
        })
        card.addView(TextView(a).apply {
            text = (if (info.build.isEmpty()) "" else "코드명 ${info.name}  ·  ") + "지금 쓰는 빌드 " + Updater.installedLabel(a)
            textSize = 13f; setTextColor(sub)
        })
        val notes = TextView(a).apply {
            text = info.notes.ifEmpty { "새 버전이 있습니다." }
            textSize = 15f; setTextColor(t.text); setLineSpacing(0f, 1.25f)
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
            background = Ui.rounded(a, t.bg, 14, Ui.line())
        }
        card.addView(ScrollView(a).apply { addView(notes) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(14) })
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = android.view.View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent())
        }
        card.addView(bar, LinearLayout.LayoutParams(-1, a.dp(10)).apply { topMargin = a.dp(14) })
        val state = TextView(a).apply { textSize = 13f; setTextColor(sub); visibility = android.view.View.GONE }
        card.addView(state)
        val buttons = LinearLayout(a).apply { setPadding(0, a.dp(14), 0, 0) }
        val later = Button(a).apply { text = "나중에"; setOnClickListener { d.dismiss() } }
        val go = Button(a).apply { text = "지금 업데이트"; tag = Ui.PRIMARY }
        buttons.addView(later, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(go, LinearLayout.LayoutParams(0, -2, 1.4f))
        card.addView(buttons)
        go.setOnClickListener {
            go.isEnabled = false; later.isEnabled = false; d.setCancelable(false)
            bar.visibility = android.view.View.VISIBLE; state.visibility = android.view.View.VISIBLE
            state.text = "내려받는 중… 0%"
            start({ p -> bar.progress = p; state.text = "내려받는 중… $p%" }) { error ->
                if (error == null) { state.text = "설치 화면을 여는 중…"; runCatching { d.dismiss() } }
                else { state.text = "받지 못했습니다: $error"; go.isEnabled = true; later.isEnabled = true; d.setCancelable(true); go.text = "다시 시도" }
            }
        }
        Ui.restyle(card)
        d.setContentView(card)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        d.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(a.dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }
}
