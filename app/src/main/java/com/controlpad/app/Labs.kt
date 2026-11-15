package com.controlpad.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 실험실: 아직 다듬는 중인 기능들. 알파 빌드를 받는 사람만 켜 볼 수 있다.
 * 평가단이 정식 추가를 요청하면(저장소의 이슈) 5분 뒤 release/labs.json 의 graduated 에 올라가고,
 * 그때부터는 정식 사용자에게도 켜진다.
 */
object Labs {
    class Feature(val id: String, val title: String, val description: String)

    val features = listOf(
        Feature("kps", "초당 입력 수 표시", "실행 화면 왼쪽 위에 최근 1초 동안 버튼을 누른 횟수를 보여 줍니다."),
        Feature("press-pop", "눌린 버튼 크게 표시", "누르고 있는 버튼이 살짝 커져서, 눌렸는지 더 잘 보입니다."),
    )

    const val REQUEST_PREFIX = "[정식 요청]"
    private const val ISSUES = "https://github.com/jkyou1127-ship-it/control/issues/new"
    private const val RECHECK_MS = 10 * 60 * 1000L
    private var lastRefresh = 0L

    private val prefs get() = Link.prefs

    /** 정식 기능이 된 실험실 기능들(마지막으로 받아 온 목록). */
    val graduated: Set<String> get() = prefs.getStringSet("labsGraduated", emptySet()) ?: emptySet()

    fun isOn(id: String): Boolean = id in graduated || (Updater.alpha && prefs.getBoolean("lab_$id", false))

    fun set(id: String, on: Boolean) = prefs.edit().putBoolean("lab_$id", on).apply()

    /** release/labs.json 에서 정식 기능이 된 것들을 꺼낸다. 이 앱이 모르는 이름은 버린다. */
    fun parseGraduated(json: String): Set<String> {
        val a = JSONObject(json).optJSONArray("graduated") ?: return emptySet()
        val known = features.map { it.id }.toSet()
        return (0 until a.length()).map { a.optString(it) }.filter { it in known }.toSet()
    }

    /** 정식 기능 목록을 받아 온다(10분에 한 번까지). 실패하면 전에 받은 목록을 그대로 쓴다. */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (lastRefresh != 0L && now - lastRefresh < RECHECK_MS) return
        lastRefresh = now
        Thread {
            runCatching { prefs.edit().putStringSet("labsGraduated", parseGraduated(get(Updater.BASE + "labs.json"))).apply() }
        }.start()
    }

    fun get(url: String): String {
        val c = URL(url + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        try {
            if (c.responseCode != 200) error("HTTP " + c.responseCode)
            return c.inputStream.use { String(it.readBytes()) }
        } finally {
            c.disconnect()
        }
    }

    fun buildTitle(version: String) = "$REQUEST_PREFIX 빌드 $version"
    fun labTitle(id: String) = "$REQUEST_PREFIX 실험실 $id"

    /** 정식 추가 요청을 적는 페이지(저장소의 새 이슈) 주소. */
    fun requestUrl(title: String, body: String): String =
        ISSUES + "?title=" + URLEncoder.encode(title, "UTF-8") + "&body=" + URLEncoder.encode(body, "UTF-8")
}

/** 실험실과 평가단 화면. 알파 빌드를 받는 사람에게만 보인다. */
class LabsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val text = Themes.current.text
        fun title(t: String) = root.addView(TextView(this).apply { this.text = t; textSize = 18f; setTextColor(text); setPadding(0, pad, 0, dp(4)) })
        fun note(t: String) = root.addView(TextView(this).apply { this.text = t; textSize = 14f; setLineSpacing(0f, 1.2f) })
        fun request(titleText: String, body: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Labs.requestUrl(titleText, body)))) }
                .onFailure { Toast.makeText(this, "웹 브라우저를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
        }
        fun chip(label: String, action: () -> Unit) =
            root.addView(toolChip(this, label, action), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        root.addView(TextView(this).apply { this.text = "실험실 · 평가단"; textSize = 24f; setTextColor(text) })

        title("평가단")
        note(
            "알파 빌드를 써 보고 괜찮으면 정식 추가를 요청합니다. 요청하면 5분 뒤에 지금 알파 빌드가 정식 채널에 올라갑니다.\n" +
                "· 요청은 저장소의 이슈로 접수되어 GitHub 로그인이 필요합니다.\n" +
                "· 평가단으로 등록된 계정의 요청만 반영됩니다.\n" +
                "· 5분 안에 그 이슈를 닫으면 취소됩니다."
        )
        val versions = TextView(this).apply { textSize = 15f; setTextColor(text); setPadding(0, dp(8), 0, 0) }
        val mine = Updater.installedName(this)
        versions.text = "지금 쓰는 빌드: $mine · 정식 채널: 확인 중…"
        root.addView(versions)
        Thread {
            val stable = runCatching { JSONObject(Labs.get(Updater.BASE + "version.json")).optString("versionName") }.getOrNull()
            runOnUiThread { versions.text = "지금 쓰는 빌드: $mine · 정식 채널: " + (stable ?: "확인하지 못함") }
        }.start()
        chip("이 빌드($mine)를 정식에 추가 요청") {
            request(Labs.buildTitle(mine), "알파 빌드 $mine 을(를) 써 보았고, 정식 채널에 올려 주세요.\n\n써 본 내용:\n")
        }

        title("실험실")
        note("아직 다듬는 중인 기능입니다. 켜서 써 보고, 괜찮으면 정식 추가를 요청하세요. 반영되면 정식 사용자에게도 켜집니다.")
        for (f in Labs.features) {
            val done = f.id in Labs.graduated
            root.addView(CheckBox(this).apply {
                this.text = f.title + if (done) "  (정식 기능이 되었습니다)" else ""
                textSize = 16f; isChecked = Labs.isOn(f.id); isEnabled = !done
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, on -> Labs.set(f.id, on) }
            })
            note(f.description)
            if (!done) chip("'${f.title}' 정식 추가 요청") {
                request(Labs.labTitle(f.id), "실험실 기능 '${f.title}' 을(를) 써 보았고, 정식 기능으로 올려 주세요.\n\n써 본 내용:\n")
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
