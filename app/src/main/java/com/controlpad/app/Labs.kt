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
        Feature("list-search", "레이아웃 검색", "목록 화면 위쪽에 검색창이 생겨, 이름으로 레이아웃을 찾습니다."),
        Feature("list-pin", "맨 위에 고정", "목록의 ⋮ 메뉴에서 자주 쓰는 레이아웃을 맨 위에 고정합니다(★ 표시)."),
        Feature("home-shortcut", "홈 화면 바로가기", "목록의 ⋮ 메뉴에서 레이아웃을 바로 실행하는 아이콘을 홈 화면에 추가합니다."),
        Feature("select-all", "전체 선택", "편집 화면에서 버튼을 한 번에 모두 선택하는 도구가 생깁니다."),
        Feature("align", "맞춤", "편집 화면에서 여러 버튼을 왼쪽·오른쪽·위·아래·가운데로 줄 맞춥니다."),
        Feature("distribute", "균등 배치 · 같은 크기", "편집 화면에서 여러 버튼의 간격을 같게 놓거나 너비·높이를 맞춥니다."),
        Feature("lock", "버튼 잠금", "편집 화면에서 선택한 버튼을 옮기거나 크기를 바꿀 수 없게 잠급니다."),
        Feature("opacity", "버튼 불투명도", "버튼 속성에서 버튼을 비치게 만들 수 있습니다(10~100%)."),
        Feature("text-scale", "버튼 글자 크기", "버튼 속성에서 글자 크기를 바꿀 수 있습니다(50~200%)."),
        Feature("exit-guard", "실수로 나가기 방지", "실행 화면에서 뒤로 가기를 두 번 눌러야 나갑니다."),
        Feature("max-brightness", "최대 밝기", "실행 화면이 떠 있는 동안 화면을 가장 밝게 합니다."),
        Feature("hide-status", "연결 상태 숨기기", "실행 화면 왼쪽 위의 연결 상태 글자를 감춰 화면을 깔끔하게 합니다."),
        Feature("press-sound", "누를 때 소리", "버튼을 누를 때마다 짧은 클릭 소리를 냅니다."),
        Feature("key-history", "최근 입력 기록", "실행 화면에 방금 누른 버튼의 키를 최근 5개까지 차례로 보여 줍니다."),
        Feature("editor-coords", "위치 · 크기 표시", "편집 화면에서 선택한 것의 위치와 크기를 화면 대비 %로 보여 줍니다."),
    )

    const val REQUEST_PREFIX = "[정식 요청]"
    private const val ISSUES = "https://github.com/jkyou1127-ship-it/control/issues/new"
    private const val RECHECK_MS = 10 * 60 * 1000L
    private var lastRefresh = 0L

    private val prefs get() = Link.prefs

    /** 정식 기능이 된 실험실 기능들(마지막으로 받아 온 목록). */
    val graduated: Set<String> get() = prefs.getStringSet("labsGraduated", emptySet()) ?: emptySet()

    /** 평가단 요청으로 숨긴 실험실 기능들(마지막으로 받아 온 목록). 숨긴 기능은 누구에게도 켜지지 않는다. */
    val hidden: Set<String> get() = prefs.getStringSet("labsHidden", emptySet()) ?: emptySet()

    fun isOn(id: String): Boolean =
        id !in hidden && (id in graduated || (Updater.alpha && prefs.getBoolean("lab_$id", false)))

    fun set(id: String, on: Boolean) = prefs.edit().putBoolean("lab_$id", on).apply()

    /** release/labs.json 에서 정식 기능이 된 것들을 꺼낸다. 이 앱이 모르는 이름은 버린다. */
    fun parseGraduated(json: String): Set<String> = parseList(json, "graduated")

    /** release/labs.json 에서 숨긴 기능들을 꺼낸다. */
    fun parseHidden(json: String): Set<String> = parseList(json, "hidden")

    private fun parseList(json: String, key: String): Set<String> {
        val a = JSONObject(json).optJSONArray(key) ?: return emptySet()
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
            prefs.edit().putStringSet("labsGraduated", parseGraduated(json)).putStringSet("labsHidden", parseHidden(json))
                .putString("labsRelay", parseRelay(json)).apply()
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
    fun hideTitle(id: String) = "[숨김 요청] 실험실 $id"
    fun restoreTitle(id: String) = "[복귀 요청] 실험실 $id"

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
        note(
            "아직 다듬는 중인 기능입니다. 켜서 써 보고 평가단으로 건의하세요. 건의는 5분 뒤 모두에게 반영됩니다.\n" +
                "· 정식 추가: 정식 사용자에게도 켜집니다\n" +
                "· 숨김: 실험실에서 빠지고 누구에게도 켜지지 않습니다\n" +
                "· 복귀: 숨긴 기능이나 정식이 된 기능을 실험실로 되돌립니다"
        )
        val hidden = Labs.hidden
        for (f in Labs.features.filter { it.id !in hidden }) {
            val done = f.id in Labs.graduated
            root.addView(CheckBox(this).apply {
                this.text = f.title + if (done) "  (정식 기능이 되었습니다)" else ""
                textSize = 16f; isChecked = Labs.isOn(f.id); isEnabled = !done
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, on -> Labs.set(f.id, on) }
            })
            note(f.description)
            val row = LinearLayout(this)
            fun rowChip(label: String, action: () -> Unit) =
                row.addView(toolChip(this, label, action), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6); topMargin = dp(6) })
            if (done) rowChip("실험실로 복귀 건의") {
                request("restore", f.id, Labs.restoreTitle(f.id), "정식 기능 '${f.title}' 을(를) 다시 실험실로 돌려 주세요.\n\n이유:\n")
            } else rowChip("정식 추가 건의") {
                request("lab", f.id, Labs.labTitle(f.id), "실험실 기능 '${f.title}' 을(를) 써 보았고, 정식 기능으로 올려 주세요.\n\n써 본 내용:\n")
            }
            rowChip("숨김 건의") {
                request("hide", f.id, Labs.hideTitle(f.id), "기능 '${f.title}' 을(를) 숨겨 주세요.\n\n이유:\n")
            }
            root.addView(row)
        }
        // 숨긴 기능: 목록에서 빠져 있고, 복귀를 건의할 수 있다.
        val gone = Labs.features.filter { it.id in hidden }
        if (gone.isNotEmpty()) {
            title("숨긴 기능")
            note("평가단 건의로 숨긴 기능입니다. 복귀를 건의하면 5분 뒤 다시 실험실에 나타납니다.")
            for (f in gone) {
                root.addView(TextView(this).apply { this.text = f.title; textSize = 16f; setTextColor(text); setPadding(0, dp(10), 0, 0) })
                chip("'${f.title}' 복귀 건의") {
                    request("restore", f.id, Labs.restoreTitle(f.id), "숨긴 기능 '${f.title}' 을(를) 다시 실험실로 돌려 주세요.\n\n이유:\n")
                }
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
