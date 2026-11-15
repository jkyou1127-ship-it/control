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
        Feature("combo", "콤보 표시", "1초 안에 이어서 누르면 올라가는 연속 입력 수를 실행 화면에 보여 줍니다."),
        Feature("total", "총 입력 수 표시", "실행 화면을 연 뒤로 버튼을 누른 횟수를 모두 세어 보여 줍니다."),
        Feature("timer", "실행 시간 표시", "실행 화면을 연 뒤로 지난 시간을 보여 줍니다."),
        Feature("battery", "배터리 · 시각 표시", "실행 화면에 태블릿의 배터리 잔량과 현재 시각을 보여 줍니다."),
        Feature("strong-haptic", "강한 진동", "버튼을 누를 때 더 길고 뚜렷한 진동을 씁니다 ('버튼을 누를 때 진동'이 켜져 있어야 합니다)."),
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

    /** 평가단 요청을 받아 주는 중계 서버 주소(마지막으로 받아 온 값). 비어 있으면 GitHub 이슈로 요청한다. */
    val relay: String get() = prefs.getString("labsRelay", "") ?: ""

    /** 평가단 코드. 평가단에게 따로 알려 준 값을 각자 입력해 둔다. */
    var code: String
        get() = prefs.getString("panelCode", "") ?: ""
        set(v) = prefs.edit().putString("panelCode", v).apply()

    /** release/labs.json 의 중계 서버 주소. https 주소가 아니면 쓰지 않는다. */
    fun parseRelay(json: String): String =
        JSONObject(json).optString("relay").trim().takeIf { it.startsWith("https://") } ?: ""

    /** release/labs.json 을 받아 저장한다. 실패하면 전에 받은 값을 그대로 쓴다. */
    fun fetchNow() {
        runCatching {
            val json = get(Updater.BASE + "labs.json")
            prefs.edit().putStringSet("labsGraduated", parseGraduated(json)).putString("labsRelay", parseRelay(json)).apply()
        }
    }

    /** 정식 기능 목록과 중계 서버 주소를 받아 온다(10분에 한 번까지). */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (lastRefresh != 0L && now - lastRefresh < RECHECK_MS) return
        lastRefresh = now
        Thread { fetchNow() }.start()
    }

    /** 중계 서버에 보낼 요청 본문. */
    fun relayBody(code: String, kind: String, target: String): String =
        JSONObject().put("code", code).put("kind", kind).put("target", target).toString()

    /** 중계 서버의 답을 사람이 읽을 말로 바꾼다. 성공이면 null. */
    fun relayProblem(status: Int, body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (status == 200 && o?.optBoolean("ok") == true) return null
        val error = o?.optString("error").orEmpty()
        return when {
            error == "bad_code" -> "평가단 코드가 맞지 않습니다"
            error == "bad_request" -> "요청 형식이 맞지 않습니다. 앱을 최신 버전으로 업데이트하세요"
            error == "relay_not_configured" -> "중계 서버 설정이 끝나지 않았습니다"
            error.startsWith("github_") -> "중계 서버가 저장소에 전달하지 못했습니다 (" + error.removePrefix("github_") + ")"
            else -> "중계 서버가 요청을 받지 못했습니다 ($status)"
        }
    }

    /** 중계 서버로 정식 추가 요청을 보낸다. 네트워크 작업이라 메인 스레드에서 부르면 안 된다. 성공이면 null. */
    fun send(kind: String, target: String): String? = runCatching {
        val c = URL(relay).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(relayBody(code, kind, target).toByteArray()) }
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
            relayProblem(status, body)
        } finally {
            c.disconnect()
        }
    }.getOrElse { "중계 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요" }

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
        // 중계 서버가 있으면 앱에서 바로 보내고(평가단 코드만 필요), 없으면 GitHub 이슈 화면을 연다.
        val viaRelay = Labs.relay.isNotEmpty()
        fun request(kind: String, target: String, titleText: String, body: String) {
            if (!viaRelay) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Labs.requestUrl(titleText, body)))) }
                    .onFailure { Toast.makeText(this, "웹 브라우저를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
                return
            }
            if (Labs.code.isBlank()) {
                Toast.makeText(this, "먼저 평가단 코드를 입력하세요", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "요청을 보내는 중…", Toast.LENGTH_SHORT).show()
            Thread {
                val problem = Labs.send(kind, target)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    android.app.AlertDialog.Builder(this)
                        .setMessage(problem ?: "요청했습니다. 5분 뒤에 정식에 올라갑니다.")
                        .setPositiveButton("확인", null).show()
                }
            }.start()
        }
        fun chip(label: String, action: () -> Unit) =
            root.addView(toolChip(this, label, action), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        root.addView(TextView(this).apply { this.text = "실험실 · 평가단"; textSize = 24f; setTextColor(text) })

        title("평가단")
        if (viaRelay) {
            note("알파 빌드를 써 보고 괜찮으면 정식 추가를 요청합니다. 요청하면 5분 뒤에 정식 채널에 올라갑니다. 평가단 코드가 필요합니다.")
            root.addView(android.widget.EditText(this).apply {
                hint = "평가단 코드"; setSingleLine(); setText(Labs.code)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { Labs.code = s.toString().trim() }
                })
            })
        } else note(
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
            request("build", mine, Labs.buildTitle(mine), "알파 빌드 $mine 을(를) 써 보았고, 정식 채널에 올려 주세요.\n\n써 본 내용:\n")
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
                request("lab", f.id, Labs.labTitle(f.id), "실험실 기능 '${f.title}' 을(를) 써 보았고, 정식 기능으로 올려 주세요.\n\n써 본 내용:\n")
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
