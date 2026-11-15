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
 * 평가단이 정식 추가를 건의하면 10분 뒤 release/labs.json 의 alpha 에 올라가 알파 사용자 모두에게 켜지고,
 * 그로부터 20분 뒤 graduated 에 올라가 정식 사용자에게도 켜진다.
 */
object Labs {
    /**
     * button 이 참이면 버튼·도구로 나타나는 기능(편집 도구, 메뉴, 속성 조절 등). 알파·정식으로 올라가면 그 버튼이 그냥 생긴다.
     * 거짓이면 동작을 바꾸는 기능. 알파·정식으로 올라가면 설정 화면의 켜기/끄기 항목이 된다.
     */
    class Feature(val id: String, val title: String, val description: String, val button: Boolean = false)

    val features = listOf(
        Feature("kps", "초당 입력 수 표시", "실행 화면 왼쪽 위에 최근 1초 동안 버튼을 누른 횟수를 보여 줍니다."),
        Feature("battery", "배터리 · 시각 표시", "실행 화면에 태블릿의 배터리 잔량과 현재 시각을 보여 줍니다."),
        Feature("list-search", "레이아웃 검색", "목록 화면 위쪽에 검색창이 생겨, 이름으로 레이아웃을 찾습니다.", button = true),
        Feature("list-pin", "맨 위에 고정", "목록의 ⋮ 메뉴에서 자주 쓰는 레이아웃을 맨 위에 고정합니다(★ 표시).", button = true),
        Feature("home-shortcut", "홈 화면 바로가기", "목록의 ⋮ 메뉴에서 레이아웃을 바로 실행하는 아이콘을 홈 화면에 추가합니다.", button = true),
        Feature("select-all", "전체 선택", "편집 화면에서 버튼을 한 번에 모두 선택하는 도구가 생깁니다.", button = true),
        Feature("align", "맞춤", "편집 화면에서 여러 버튼을 왼쪽·오른쪽·위·아래·가운데로 줄 맞춥니다.", button = true),
        Feature("distribute", "균등 배치 · 같은 크기", "편집 화면에서 여러 버튼의 간격을 같게 놓거나 너비·높이를 맞춥니다.", button = true),
        Feature("lock", "버튼 잠금", "편집 화면에서 선택한 버튼을 옮기거나 크기를 바꿀 수 없게 잠급니다.", button = true),
        Feature("opacity", "버튼 불투명도", "버튼 속성에서 버튼을 비치게 만들 수 있습니다(10~100%).", button = true),
        Feature("text-scale", "버튼 글자 크기", "버튼 속성에서 글자 크기를 바꿀 수 있습니다(50~200%).", button = true),
        Feature("exit-guard", "실수로 나가기 방지", "실행 화면에서 뒤로 가기를 두 번 눌러야 나갑니다."),
        Feature("hide-status", "연결 상태 숨기기", "실행 화면 왼쪽 위의 연결 상태 글자를 감춰 화면을 깔끔하게 합니다."),
        Feature("press-sound", "누를 때 소리", "버튼을 누를 때마다 짧은 클릭 소리를 냅니다."),
        Feature("key-history", "최근 입력 기록", "실행 화면에 방금 누른 버튼의 키를 최근 5개까지 차례로 보여 줍니다."),
        Feature("editor-coords", "위치 · 크기 표시", "편집 화면에서 선택한 것의 위치와 크기를 화면 대비 %로 보여 줍니다."),
        Feature("center-guides", "가운데 안내선", "편집 화면 가운데에 가로·세로 안내선을 그려, 좌우 대칭으로 놓기 쉽게 합니다."),
        Feature("long-press-edit", "길게 눌러 속성 열기", "편집 화면에서 버튼을 길게 누르면 바로 속성 창이 열립니다."),
        Feature("touch-dots", "터치 위치 표시", "실행 화면에서 손가락이 닿은 자리에 원을 그려 보여 줍니다."),
        Feature("hold-time", "누른 시간 표시", "실행 화면에서 누르고 있는 버튼 위에 몇 초째 누르고 있는지 보여 줍니다."),
        Feature("auto-dim", "쉬는 동안 어둡게", "실행 화면에서 30초 동안 아무것도 누르지 않으면 화면을 어둡게 합니다. 누르면 바로 돌아옵니다."),
        Feature("big-handle", "큰 크기 조절 손잡이", "편집 화면의 크기 조절 손잡이(흰 점)를 크게 그리고, 잡히는 범위도 넓힙니다."),
        Feature("confirm-delete", "삭제 전에 확인", "편집 화면에서 '삭제'를 누르면 정말 지울지 한 번 더 묻습니다."),
        Feature("dup-in-place", "제자리 복제", "편집 화면에서 복제할 때 옆으로 비켜 놓지 않고 같은 자리에 겹쳐 놓습니다."),
        Feature("edge-flash", "누를 때 화면 테두리 번쩍", "실행 화면에서 버튼을 누를 때마다 화면 테두리가 잠깐 빛나, 입력이 들어갔는지 알 수 있습니다."),
        Feature("status-bottom", "상태 표시를 아래로", "실행 화면의 연결 상태와 실험실 표시를 왼쪽 위 대신 왼쪽 아래에 보여 줍니다."),
        Feature("option-buttons", "옵션 버튼", "편집 화면 ＋추가에 '옵션 버튼'이 생깁니다. 실행 중에 누르면 진동, 키 표시, 실험실 기능 같은 설정을 바로 켜고 끕니다.", button = true),
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

    /** 이 기기에서 바로 숨긴 것으로 친다(숨김 건의를 보낸 직후). 다음에 목록을 받아 오면 저장소 값으로 맞춰진다. */
    fun hideNow(id: String) = prefs.edit().putStringSet("labsHidden", hidden + id).apply()

    /** 정식 추가 1단계로 알파 사용자 모두에게 켜진 기능들(마지막으로 받아 온 목록). */
    val alphaWide: Set<String> get() = prefs.getStringSet("labsAlpha", emptySet()) ?: emptySet()

    /** 실험실 전체 켜기/끄기. 끄면 실험실에서만 켜 둔 기능들이 한꺼번에 꺼진다(각각의 선택은 그대로 남는다). */
    var enabled: Boolean
        get() = prefs.getBoolean("labsEnabled", true)
        set(on) = prefs.edit().putBoolean("labsEnabled", on).apply()

    fun feature(id: String): Feature? = features.firstOrNull { it.id == id }

    /** 이 사람에게 알파·정식으로 올라와 있는지(실험실을 벗어난 기능). */
    fun promoted(id: String): Boolean = id !in hidden && (id in graduated || (Updater.alpha && id in alphaWide))

    /** 올라온 기능 중 설정 화면에서 켜고 끄는 것들. */
    fun settingFeatures(): List<Feature> = features.filter { !it.button && promoted(it.id) }

    fun isOn(id: String): Boolean = when {
        id in hidden -> false
        // 올라온 기능: 버튼형은 늘 있고, 설정형은 설정 화면의 켜기/끄기를 따른다(처음엔 켜짐).
        promoted(id) -> feature(id)?.button != false || prefs.getBoolean("set_$id", true)
        else -> Updater.alpha && enabled && prefs.getBoolean("lab_$id", false)
    }

    /** 켜기/끄기: 올라온 설정형 기능은 설정 값을, 아직 실험실에 있는 기능은 실험실 선택을 바꾼다. */
    fun set(id: String, on: Boolean) {
        val key = if (promoted(id) && feature(id)?.button == false) "set_$id" else "lab_$id"
        prefs.edit().putBoolean(key, on).apply()
    }

    /** release/labs.json 에서 정식 기능이 된 것들을 꺼낸다. 이 앱이 모르는 이름은 버린다. */
    fun parseGraduated(json: String): Set<String> = parseList(json, "graduated")

    /** release/labs.json 에서 숨긴 기능들을 꺼낸다. */
    fun parseHidden(json: String): Set<String> = parseList(json, "hidden")

    /** release/labs.json 에서 알파 사용자 모두에게 켜진 기능들을 꺼낸다. */
    fun parseAlpha(json: String): Set<String> = parseList(json, "alpha")

    /** 건의를 보낸 뒤 알려 줄 진행 계획. scripts/panel.py 의 plan 과 같은 일정이다. */
    fun plan(kind: String): String = when (kind) {
        "build" -> "요청했습니다. 30분 뒤에 정식 채널에 올라갑니다."
        "lab" -> "요청했습니다. 10분 뒤에 알파 사용자 모두에게 켜지고, 그로부터 20분 뒤에 정식 기능이 됩니다."
        "hide" -> "숨겼습니다. 다른 사람들에게도 1분쯤 안에 숨겨집니다."
        else -> "요청했습니다. 5분 뒤에 실험실로 돌아옵니다."
    }

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
                .putStringSet("labsAlpha", parseAlpha(json))
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
                // 숨김은 기다리지 않으므로, 건의한 사람의 화면에서는 바로 숨긴다(저장소 반영은 곧 뒤따른다).
                if (problem == null && kind == "hide") Labs.hideNow(target)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    android.app.AlertDialog.Builder(this)
                        .setMessage(problem ?: Labs.plan(kind))
                        .setPositiveButton("확인") { _, _ -> if (problem == null && kind == "hide") recreate() }
                        .show()
                }
            }.start()
        }
        fun chip(label: String, action: () -> Unit) =
            root.addView(toolChip(this, label, action), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        root.addView(TextView(this).apply { this.text = "실험실 · 평가단"; textSize = 24f; setTextColor(text) })

        title("평가단")
        if (viaRelay) {
            note("알파 빌드를 써 보고 괜찮으면 정식 추가를 요청합니다. 요청하면 30분 뒤에 정식 채널에 올라갑니다. 평가단 코드가 필요합니다.")
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
            "알파 빌드를 써 보고 괜찮으면 정식 추가를 요청합니다. 요청하면 30분 뒤에 지금 알파 빌드가 정식 채널에 올라갑니다.\n" +
                "· 요청은 저장소의 이슈로 접수되어 GitHub 로그인이 필요합니다.\n" +
                "· 평가단으로 등록된 계정의 요청만 반영됩니다.\n" +
                "· 반영되기 전에 그 이슈를 닫으면 취소됩니다."
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
            "아직 다듬는 중인 기능입니다. 켜서 써 보고 평가단으로 건의하세요.\n" +
                "· 정식 추가: 10분 뒤 알파 사용자 모두에게 켜지고, 그로부터 20분 뒤 정식 사용자에게도 켜집니다\n" +
                "· 숨김: 바로 실험실에서 빠지고 누구에게도 켜지지 않습니다\n" +
                "· 복귀: 5분 뒤 숨긴 기능이나 정식이 된 기능을 실험실로 되돌립니다"
        )
        // 실험실 전체 켜기/끄기
        val boxes = ArrayList<CheckBox>()
        root.addView(CheckBox(this).apply {
            this.text = "실험실 사용"; textSize = 17f; isChecked = Labs.enabled; setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, on ->
                Labs.enabled = on
                for (b in boxes) b.isEnabled = on
            }
        })
        note("끄면 실험실에서 켜 둔 기능이 모두 꺼집니다. 다시 켜면 전에 골라 둔 대로 돌아옵니다. 알파·정식으로 올라간 기능은 영향을 받지 않습니다.")
        val hidden = Labs.hidden
        for (f in Labs.features.filter { it.id !in hidden }) {
            val done = f.id in Labs.graduated
            val rolling = !done && f.id in Labs.alphaWide
            val moved = done || rolling
            root.addView(CheckBox(this).apply {
                this.text = f.title + when {
                    moved && f.button -> if (done) "  (정식 · 버튼으로 추가됨)" else "  (알파 · 버튼으로 추가됨 · 곧 정식)"
                    moved -> if (done) "  (정식 · 설정으로 옮겨짐)" else "  (알파 · 설정으로 옮겨짐 · 곧 정식)"
                    else -> ""
                }
                textSize = 16f; isChecked = Labs.isOn(f.id)
                setPadding(0, dp(10), 0, 0)
                if (moved) isEnabled = false         // 올라간 기능은 설정 화면(또는 버튼)에서 다룬다
                else { isEnabled = Labs.enabled; boxes += this; setOnCheckedChangeListener { _, on -> Labs.set(f.id, on) } }
            })
            note(f.description)
            val row = LinearLayout(this)
            fun rowChip(label: String, action: () -> Unit) =
                row.addView(toolChip(this, label, action), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6); topMargin = dp(6) })
            if (done) rowChip("실험실로 복귀 건의") {
                request("restore", f.id, Labs.restoreTitle(f.id), "정식 기능 '${f.title}' 을(를) 다시 실험실로 돌려 주세요.\n\n이유:\n")
            } else if (!rolling) rowChip("정식 추가 건의") {
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
            note("평가단 건의로 숨긴 기능입니다. 복귀를 건의하면 5분 뒤 숨기기 전 상태로 돌아옵니다.")
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
