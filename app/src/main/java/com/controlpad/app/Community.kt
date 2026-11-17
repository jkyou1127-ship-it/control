package com.controlpad.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 레이아웃 커뮤니티(LOD): 레이아웃을 올리고(배포) 다른 사람이 올린 것을 받는 곳.
 *  · 올리기: 중계 서버(release/labs.json 의 relay) 의 /community 로 보낸다 → 저장소 community/layouts/ 에 저장
 *  · 목록: community/index.json (저장소 자동화가 만든다) 을 GitHub 에서 바로 읽는다
 *  · 받기: community/layouts/<id>.json 안의 레이아웃을 '파일로 불러오기'와 같은 방법으로 넣는다
 *  · 지우기(KDSKD): 올릴 때 받은 '지우기 열쇠'를 이 기기에 적어 두었다가 /community/delete 로 보낸다
 */
object Community {
    class Entry(val id: String, val name: String, val author: String, val created: String, val buttons: Int, val bytes: Int, val tier: String = "")

    class Post(val id: String, val title: String, val text: String, val author: String, val tier: String, val created: String)

    fun parsePosts(json: String): List<Post> {
        val a = JSONObject(json).optJSONArray("posts") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (!Regex("^[0-9A-Za-z_-]{1,64}$").matches(id)) return@mapNotNull null
            Post(id, o.optString("title").take(60), o.optString("text").take(2000), o.optString("author").take(20).ifEmpty { "익명" },
                o.optString("tier").take(12), o.optString("created"))
        }
    }

    fun postBody(title: String, text: String, author: String, uploads: Int = 0, adminPassword: String? = null): String =
        JSONObject().put("title", title.take(60)).put("text", text.take(2000)).put("author", author.take(20)).put("uploads", uploads)
            .apply { if (adminPassword != null) put("adminPassword", adminPassword) }.toString()

    /** 글 올리기. 잘 되면 지우기 열쇠를 적어 두고 null. */
    fun writePost(title: String, text: String, author: String): String? = runCatching {
        if (Labs.relay.isEmpty()) return "커뮤니티 서버가 아직 준비되지 않았습니다"
        val (status, body) = post("/community/post", postBody(title, text, author, uploads, adminPassword))
        problem(status, body) ?: run {
            val o = JSONObject(body)
            val id = o.optString("id"); val key = o.optString("key")
            if (id.isNotEmpty() && key.isNotEmpty()) saveKey(id, key)
            if (id.isNotEmpty()) addPending("post", o.optJSONObject("line") ?: JSONObject().put("id", id).put("title", title)
                .put("text", text).put("author", author.ifEmpty { "익명" }).put("tier", myTier()).put("created", nowIso()))
            null
        }
    }.getOrElse { "커뮤니티 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요" }

    // ── 회원 등급 · 관리자 ──

    const val ADMIN_NAME = "cupompu"
    const val MANAGER = "매니저"

    /** 매니저는 등급 앞에 검정 바탕 흰색 M 아이콘을 붙여 보여 준다. 다른 등급은 글자 그대로. */
    fun isManager(tier: String) = tier == MANAGER || tier.endsWith(" 매니저") || tier.endsWith("관리자")

    /** 등급이 들어 있는 한 줄을 그린다: 매니저면 'M' 아이콘을 붙인다. */
    fun styled(before: String, tier: String, after: String, density: Float): CharSequence {
        val sb = android.text.SpannableStringBuilder(before)
        if (tier.isNotEmpty()) {
            if (isManager(tier)) {
                val start = sb.length
                sb.append("M")
                sb.setSpan(ManagerBadge(density), start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" ").append(MANAGER)
            } else sb.append(tier)
        }
        return sb.append(after)
    }

    /** 회원 등급: 이 기기에서 올린 레이아웃 수로 정한다(중계 서버의 tierOf 와 같다). */
    fun tierOf(uploads: Int, admin: Boolean = false): String = when {
        admin -> MANAGER                          // 관리자 등급은 매니저로 고정
        uploads >= 30 -> "🏆 장인"
        uploads >= 10 -> "🌳 활동가"
        uploads >= 3 -> "🌿 일반"
        else -> "🌱 새싹"
    }

    /** 다음 등급까지 몇 개 더 올려야 하는지(장인이면 0). */
    fun toNextTier(uploads: Int): Int = listOf(3, 10, 30).firstOrNull { it > uploads }?.let { it - uploads } ?: 0

    fun isAdminName(name: String) = name.trim().equals(ADMIN_NAME, ignoreCase = true)

    /** 화면에 보여 줄 등급: 관리자 닉네임은 늘 매니저(예전에 등급 없이 올라간 것도). */
    fun shownTier(author: String, tier: String): String = if (isAdminName(author)) tierOf(0, true) else tier

    // ── 목록에 반영되기 전에도 내가 올린 것을 바로 보여 준다 ──

    /** 올리기에 성공한 줄을 적어 둔다(목록에 실리면 지운다, 하루가 지나도 지운다). */
    fun addPending(kind: String, line: JSONObject) {
        val a = runCatching { JSONArray(Link.prefs.getString("communityPending", "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("kind", kind).put("at", System.currentTimeMillis()).put("line", line))
        Link.prefs.edit().putString("communityPending", a.toString()).apply()
    }

    /** 아직 목록에 없는 내 것들(kind: layout / post). 목록에 실린 것과 오래된 것은 지운다. */
    fun pending(kind: String, listed: Set<String>, now: Long = System.currentTimeMillis()): List<JSONObject> {
        val a = runCatching { JSONArray(Link.prefs.getString("communityPending", "[]")) }.getOrDefault(JSONArray())
        val keep = JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val line = o.optJSONObject("line") ?: continue
            if (line.optString("id") in listed || now - o.optLong("at") > 86_400_000L) continue
            keep.put(o)
            if (o.optString("kind") == kind) out += line
        }
        Link.prefs.edit().putString("communityPending", keep.toString()).apply()
        return out
    }

    fun nowIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    var uploads: Int
        get() = Link.prefs.getInt("communityUploads", 0)
        set(v) = Link.prefs.edit().putInt("communityUploads", v).apply()

    /** 관리자로 접속해 있으면 그 비밀번호(이 기기에만), 아니면 null. */
    var adminPassword: String?
        get() = Link.prefs.getString("communityAdmin", null)
        set(v) = Link.prefs.edit().apply { if (v == null) remove("communityAdmin") else putString("communityAdmin", v) }.apply()

    val isAdmin get() = adminPassword != null

    fun myTier() = tierOf(uploads, isAdmin)

    // ── 기본 레이아웃은 올리지 않는다 ──

    /** 버튼 배치의 지문: 자리 · 크기 · 종류 · 키. 이름이 달라도 배치가 같으면 같은 지문. */
    fun fingerprint(l: PadLayout): String = l.buttons.map { b ->
        "%.3f,%.3f,%.3f,%.3f,%d,%s".format(java.util.Locale.ROOT, b.x, b.y, b.w, b.h, b.type, b.keys.joinToString("+"))
    }.sorted().joinToString("|")

    private val builtInPrints: Set<String> by lazy {
        (1 until Templates.names.size).map { fingerprint(Templates.create(it, Templates.names[it])) }.toSet()
    }

    /** 앱에 처음부터 들어 있는 예시 레이아웃 그대로인지(이름만 바꾼 것도 포함). 버튼이 없으면 올릴 것이 없다. */
    fun isBuiltIn(l: PadLayout): Boolean = l.buttons.isEmpty() || fingerprint(l) in builtInPrints

    /** 저장소의 community 폴더(GitHub 에서 바로 읽는 주소). */
    val BASE: String get() = Updater.BASE.removeSuffix("release/") + "community/"

    const val MAX_UPLOAD = 900_000          // 중계 서버의 COMMUNITY_MAX 와 같다

    /** 같은 파일을 GitHub API 로 받는 주소(캐시 없이 최신). */
    fun apiUrl(path: String): String {
        val m = Regex("""raw\.githubusercontent\.com/([^/]+)/([^/]+)/(.+)/release/$""").find(Updater.BASE)!!
        val (owner, repo, branch) = m.destructured
        return "https://api.github.com/repos/$owner/$repo/contents/community/$path?ref=" + java.net.URLEncoder.encode(branch, "UTF-8")
    }

    /**
     * community 폴더의 파일을 받는다. raw.githubusercontent.com 은 주소 끝을 바꿔도 5분 동안 옛 내용을 주므로
     * 먼저 GitHub API(캐시 없음)로 받고, 안 되면(한 시간 60번 제한 등) raw 주소로 받는다.
     */
    fun fetch(path: String): String {
        runCatching {
            val c = URL(apiUrl(path)).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/vnd.github.raw")
            c.setRequestProperty("User-Agent", "ControlPad")
            try {
                if (c.responseCode == 200) return c.inputStream.use { String(it.readBytes()) }
            } finally {
                c.disconnect()
            }
        }
        return Labs.get(BASE + path)
    }

    fun parseIndex(json: String): List<Entry> {
        val a = JSONObject(json).optJSONArray("layouts") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (!Regex("^[0-9A-Za-z_-]{1,64}$").matches(id)) return@mapNotNull null      // 주소에 넣으므로 모양을 확인한다
            Entry(id, o.optString("name").take(40), o.optString("author").take(20).ifEmpty { "익명" },
                o.optString("created"), o.optInt("buttons"), o.optInt("bytes"), o.optString("tier").take(12))
        }
    }

    /**
     * 올라온 레이아웃 파일 받기. 한 번 올라간 파일은 바뀌지 않으므로 raw 주소(캐시돼도 괜찮음)로 먼저 받고,
     * 막 올라와 아직 raw 에 없으면 API 로 받는다(API 는 한 시간 60번 제한이 있어 목록에만 먼저 쓴다).
     */
    fun fetchLayoutFile(id: String): String =
        runCatching { Labs.get(BASE + "layouts/$id.json") }.getOrElse { fetch("layouts/$id.json") }

    /** 받은 파일에서 레이아웃 글자(파일로 저장 형식)를 꺼낸다. */
    fun layoutText(file: String): String = JSONObject(file).getJSONObject("layout").toString()

    /** 중계 서버에 보낼 내용. */
    fun uploadBody(name: String, author: String, layoutText: String, uploads: Int = 0, adminPassword: String? = null): String =
        JSONObject().put("name", name.take(40)).put("author", author.take(20)).put("layout", layoutText)
            .put("uploads", uploads).apply { if (adminPassword != null) put("adminPassword", adminPassword) }.toString()

    /** '2026-11-17T08:30:00.000Z' → '2026.11.17' */
    fun date(created: String): String = created.take(10).replace('-', '.')

    /** 중계 서버 답을 사람이 읽을 말로. 잘 되면 null. */
    fun problem(status: Int, body: String): String? {
        if (status in 200..299) return null
        val code = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return when (code) {
            "too_big" -> "레이아웃이 너무 큽니다(사진이 많으면 커집니다). 사진을 줄이고 다시 올려 보세요"
            "no_name" -> "이름을 적어 주세요"
            "bad_layout" -> "레이아웃을 읽지 못했습니다"
            "relay_not_configured" -> "커뮤니티 서버가 아직 준비되지 않았습니다"
            "bad_key" -> "이 기기에서 올린 레이아웃만 지울 수 있습니다"
            "not_found" -> "이미 지워진 레이아웃입니다"
            "admin_only_name" -> "'$ADMIN_NAME' 은(는) 관리자 닉네임입니다. 관리자로 접속한 뒤 쓸 수 있습니다"
            "bad_password" -> "비밀번호가 맞지 않습니다"
            "weak_password" -> "새 비밀번호는 4자 이상이어야 합니다"
            "no_title" -> "제목을 적어 주세요"
            "no_text" -> "내용을 적어 주세요"
            else -> if (code.startsWith("github_")) "저장소에 올리지 못했습니다($code). 잠시 뒤 다시 해 보세요" else "올리지 못했습니다(HTTP $status)"
        }
    }

    /** 중계 서버에 보내고 (상태, 답) 을 돌려준다. */
    private fun post(path: String, body: String): Pair<Int, String> {
        val c = URL(Labs.relay.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 30000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val status = c.responseCode
            return status to (if (status in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        } finally {
            c.disconnect()
        }
    }

    /** 올리기. 잘 되면 지우기 열쇠를 이 기기에 적어 두고 null, 안 되면 이유. */
    fun upload(name: String, author: String, layoutText: String, buttons: Int = 0): String? = runCatching {
        if (Labs.relay.isEmpty()) return "커뮤니티 서버가 아직 준비되지 않았습니다"
        val (status, body) = post("/community", uploadBody(name, author, layoutText, uploads + 1, adminPassword))
        problem(status, body) ?: run {
            uploads = uploads + 1
            val o = JSONObject(body)
            val id = o.optString("id"); val key = o.optString("key")
            if (id.isNotEmpty() && key.isNotEmpty()) saveKey(id, key)
            // 목록에 실리기 전에도 바로 보이게(중계 서버가 준 줄, 없으면 직접 만든 줄)
            if (id.isNotEmpty()) addPending("layout", o.optJSONObject("line") ?: JSONObject().put("id", id).put("name", name)
                .put("author", author.ifEmpty { "익명" }).put("created", nowIso()).put("buttons", buttons)
                .put("tier", myTier()).put("bytes", layoutText.length))
            null
        }
    }.getOrElse { "커뮤니티 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요" }

    /** 지우기. 잘 되면 열쇠도 지우고 null, 안 되면 이유. */
    fun delete(id: String, kind: String = "layout"): String? = runCatching {
        if (Labs.relay.isEmpty()) return "커뮤니티 서버가 아직 준비되지 않았습니다"
        val key = keyOf(id)
        val admin = adminPassword
        if (key == null && admin == null) return "이 기기에서 올린 레이아웃만 지울 수 있습니다"
        val (status, body) = post("/community/delete", deleteBody(id, key ?: "", admin, kind))
        problem(status, body) ?: run { forgetKey(id); null }
    }.getOrElse { "커뮤니티 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요" }

    fun deleteBody(id: String, key: String, adminPassword: String? = null, kind: String = "layout"): String =
        JSONObject().put("id", id).put("key", key).put("kind", kind)
            .apply { if (adminPassword != null) put("adminPassword", adminPassword) }.toString()

    /** 관리자 접속: 맞으면 비밀번호를 이 기기에 적어 두고 null. */
    fun adminLogin(password: String): String? = runCatching {
        if (Labs.relay.isEmpty()) return "커뮤니티 서버가 아직 준비되지 않았습니다"
        val (status, body) = post("/community/admin/login", JSONObject().put("password", password).toString())
        problem(status, body) ?: run { adminPassword = password; null }
    }.getOrElse { "커뮤니티 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요" }

    /** 관리자 비밀번호 바꾸기(4자 이상). */
    fun changeAdminPassword(next: String): String? = runCatching {
        val now = adminPassword ?: return "관리자로 접속해 있지 않습니다"
        val (status, body) = post("/community/admin/password", JSONObject().put("password", now).put("newPassword", next).toString())
        problem(status, body) ?: run { adminPassword = next; null }
    }.getOrElse { "커뮤니티 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요" }

    // 지우기 열쇠는 올린 기기에만 남는다(다른 기기 · 앱을 지우면 사라진다).
    private fun saveKey(id: String, key: String) = Link.prefs.edit().putString("communityKey_$id", key).apply()
    fun keyOf(id: String): String? = Link.prefs.getString("communityKey_$id", null)
    private fun forgetKey(id: String) = Link.prefs.edit().remove("communityKey_$id").apply()
    fun mine(id: String) = keyOf(id) != null
}

/** 커뮤니티 화면: 위쪽에 '내 레이아웃 올리기'와 검색, 아래는 올라온 레이아웃 카드 격자(⬇ 받기). */
class CommunityActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var info: TextView
    private lateinit var account: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var actionButton: Button
    private var posts: List<Community.Post> = emptyList()
    private var showPosts = false            // false: 레이아웃, true: 글
    private var waiting: Set<String> = emptySet()   // 올렸지만 아직 목록에 반영되지 않은 내 것

    /** 내 계정 줄: 닉네임 · 등급 · (관리자면) 비밀번호 바꾸기 · 로그아웃. */
    private fun fillAccount() {
        account.removeAllViews()
        val nick = Link.prefs.getString("communityAuthor", "").orEmpty().ifEmpty { "익명" }
        val next = Community.toNextTier(Community.uploads)
        account.addView(TextView(this).apply {
            text = Community.styled("👤 $nick  ·  ", Community.myTier(),
                if (Community.isAdmin) "" else "  ·  올린 레이아웃 ${Community.uploads}개" + if (next > 0) " (다음 등급까지 ${next}개)" else "", resources.displayMetrics.density)
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.rounded(this@CommunityActivity, Ui.surface(), 16, if (Community.isAdmin) 0xFFFFC107.toInt() else Ui.line())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        account.addView(Button(this).apply { text = "닉네임"; setOnClickListener { askNickname {} } })
        if (Community.isAdmin) {
            account.addView(Button(this).apply { text = "비밀번호 바꾸기"; setOnClickListener { changePassword() } })
            account.addView(Button(this).apply {
                text = "관리자 로그아웃"
                setOnClickListener { Community.adminPassword = null; Link.prefs.edit().putString("communityAuthor", "").apply(); fillAccount(); fill() }
            })
        }
        Ui.restyle(account)
    }

    /** 닉네임 정하기. 관리자 닉네임(cupompu)이면 비밀번호를 물어 관리자로 접속한다. */
    private fun askNickname(then: () -> Unit) {
        val input = EditText(this).apply { hint = "닉네임(비우면 익명)"; setText(Link.prefs.getString("communityAuthor", "")); setSingleLine() }
        android.app.AlertDialog.Builder(this).setTitle("닉네임")
            .setView(android.widget.FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("확인") { _, _ ->
                val nick = input.text.toString().trim()
                if (Community.isAdminName(nick) && !Community.isAdmin) askAdminPassword(nick, then)
                else {
                    if (!Community.isAdminName(nick) && Community.isAdmin) Community.adminPassword = null   // 다른 닉네임이면 관리자에서 나간다
                    Link.prefs.edit().putString("communityAuthor", nick).apply()
                    fillAccount(); fill(); then()
                }
            }
            .setNegativeButton("취소", null).show().wide()
    }

    private fun askAdminPassword(nick: String, then: () -> Unit) {
        val input = EditText(this).apply {
            hint = "관리자 비밀번호"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this).setTitle("관리자 계정")
            .setMessage("'$nick' 은(는) 관리자 닉네임입니다. 비밀번호를 입력하세요.")
            .setView(android.widget.FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("접속") { _, _ ->
                val pw = input.text.toString()
                Thread {
                    val problem = Community.adminLogin(pw)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (problem == null) {
                            Link.prefs.edit().putString("communityAuthor", nick).apply()
                            toast("관리자로 접속했습니다")
                            fillAccount(); fill(); then()
                        } else toast(problem)
                    }
                }.start()
            }
            .setNegativeButton("취소", null).show().wide()
    }

    private fun changePassword() {
        val input = EditText(this).apply {
            hint = "새 비밀번호(4자 이상)"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this).setTitle("관리자 비밀번호 바꾸기")
            .setView(android.widget.FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("바꾸기") { _, _ ->
                val next = input.text.toString()
                if (next.length < 4) { toast("4자 이상으로 정해 주세요"); return@setPositiveButton }
                Thread {
                    val problem = Community.changeAdminPassword(next)
                    runOnUiThread { toast(problem ?: "비밀번호를 바꿨습니다") }
                }.start()
            }
            .setNegativeButton("취소", null).show().wide()
    }
    private var entries: List<Community.Entry> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(Ui.header(this, "🌐 레이아웃 커뮤니티", "레이아웃을 올려 나누고, 다른 사람이 올린 것을 받습니다"))
        // 레이아웃 | 글 탭
        tabBar = LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = Ui.rounded(this@CommunityActivity, Ui.surface(), 14, Ui.line())
        }
        root.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actionButton = Button(this).apply { tag = Ui.PRIMARY; setOnClickListener { if (showPosts) writePost() else chooseUpload() } }
        top.addView(actionButton)
        top.addView(Button(this).apply { text = "새로고침"; setOnClickListener { load() } })
        top.addView(EditText(this).apply {
            hint = "🔍  이름 · 만든 사람 검색"; setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { query = s.toString().trim(); fill() }
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(top)
        account = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(account, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        fillAccount()
        info = TextView(this).apply { textSize = 13f; setTextColor(Ui.muted()); setPadding(dp(2), dp(10), 0, 0) }
        root.addView(info)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        load()
    }

    private fun load() {
        info.text = "목록을 받는 중…"
        Thread {
            val got = runCatching { Community.fetch("index.json") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                got.onSuccess { text ->
                    val listedLayouts = Community.parseIndex(text)
                    val listedPosts = Community.parsePosts(text)
                    // 내가 올렸는데 아직 목록에 없는 것은 '반영 중'으로 맨 위에
                    val ids = (listedLayouts.map { it.id } + listedPosts.map { it.id }).toSet()
                    val waitingLayouts = Community.parseIndex(org.json.JSONObject().put("layouts", org.json.JSONArray(Community.pending("layout", ids))).toString())
                    val waitingPosts = Community.parsePosts(org.json.JSONObject().put("posts", org.json.JSONArray(Community.pending("post", ids))).toString())
                    waiting = (waitingLayouts.map { it.id } + waitingPosts.map { it.id }).toSet()
                    entries = waitingLayouts + listedLayouts
                    posts = waitingPosts + listedPosts
                    fill()
                }
                    .onFailure { info.text = "목록을 받지 못했습니다. 인터넷 연결을 확인하세요" }
            }
        }.start()
    }

    private fun fillTabs() {
        tabBar.removeAllViews()
        listOf("📦  레이아웃" to false, "💬  글" to true).forEach { (label, posts) ->
            val on = posts == showPosts
            tabBar.addView(TextView(this).apply {
                text = label; textSize = 15f; gravity = Gravity.CENTER
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (on) Ui.onAccent() else Themes.current.text)
                if (on) background = Ui.rounded(this@CommunityActivity, Ui.accent(), 10)
                setPadding(0, dp(9), 0, dp(9)); isClickable = true
                setOnClickListener { showPosts = posts; fill() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        actionButton.text = if (showPosts) "✏ 글쓰기" else "⬆ 내 레이아웃 올리기"
    }

    private fun fillPosts() {
        val shown = posts.filter { query.isEmpty() || it.title.contains(query, true) || it.text.contains(query, true) || it.author.contains(query, true) }
        info.text = when {
            posts.isEmpty() -> "아직 글이 없습니다. 첫 글을 써 보세요!"
            shown.isEmpty() -> "'$query' 에 맞는 글이 없습니다"
            else -> "글 ${shown.size}개"
        }
        for (p in shown) list.addView(postCard(p), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        Ui.restyle(list)
    }

    private fun postCard(p: Community.Post): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@CommunityActivity, Ui.surface(), 16, Ui.line())
        setPadding(dp(16), dp(14), dp(16), dp(12))
        addView(TextView(this@CommunityActivity).apply {
            text = p.title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text)
        })
        addView(TextView(this@CommunityActivity).apply {
            val tier = Community.shownTier(p.author, p.tier)
            text = Community.styled((if (p.id in waiting) "⏳ 목록에 반영 중 · " else if (Community.mine(p.id)) "내가 씀 · " else "") +
                "by ${p.author}" + (if (tier.isNotEmpty()) " " else ""), tier, " · ${Community.date(p.created)}", resources.displayMetrics.density)
            textSize = 12f; setTextColor(Ui.muted()); setPadding(0, dp(3), 0, dp(8))
        })
        addView(TextView(this@CommunityActivity).apply {
            text = p.text; textSize = 14.5f; setLineSpacing(0f, 1.25f); setTextColor(Themes.current.text); setTextIsSelectable(true)
        })
        if (Community.mine(p.id) || Community.isAdmin) addView(Button(this@CommunityActivity).apply {
            text = if (Community.mine(p.id)) "🗑 지우기" else "🗑 지우기(관리자)"
            setOnClickListener { confirmDeletePost(p) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }

    private fun confirmDeletePost(p: Community.Post) {
        android.app.AlertDialog.Builder(this).setMessage("'${p.title}' 글을 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                Thread {
                    val problem = Community.delete(p.id, "post")
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (problem == null) { posts = posts.filter { it.id != p.id }; fill(); toast("지웠습니다. 1~2분 뒤 목록에서도 사라집니다") }
                        else toast(problem)
                    }
                }.start()
            }
            .setNegativeButton("취소", null).show()
    }

    /** 글쓰기: 제목 · 내용(닉네임은 계정 줄의 닉네임). */
    private fun writePost() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply { hint = "제목"; setSingleLine() }
        val text = EditText(this).apply {
            hint = "내용(2000자까지)"; minLines = 5; gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(title); box.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val nick = Link.prefs.getString("communityAuthor", "").orEmpty()
        box.addView(TextView(this).apply {
            this.text = Community.styled("닉네임: " + nick.ifEmpty { "익명" } + " · ", Community.myTier(), " — 올린 글은 누구나 볼 수 있습니다.", resources.displayMetrics.density)
            textSize = 12.5f; setTextColor(Ui.muted()); setPadding(0, dp(8), 0, 0)
        })
        android.app.AlertDialog.Builder(this).setTitle("✏ 글쓰기")
            .setView(box)
            .setPositiveButton("올리기") { _, _ ->
                val t = title.text.toString().trim(); val b = text.text.toString().trim()
                if (t.isEmpty() || b.isEmpty()) { toast("제목과 내용을 적어 주세요"); return@setPositiveButton }
                toast("올리는 중…")
                Thread {
                    val problem = Community.writePost(t, b, nick)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        android.app.AlertDialog.Builder(this).setMessage(problem ?: "올렸습니다! 내 화면에는 바로 보이고, 다른 사람에게는 곧 보입니다.")
                            .setPositiveButton("확인") { _, _ -> if (problem == null) load() }.show()
                    }
                }.start()
            }
            .setNegativeButton("취소", null).show().wide()
    }

    private fun fill() {
        list.removeAllViews()
        fillTabs()
        if (showPosts) { fillPosts(); return }
        val shown = entries.filter { query.isEmpty() || it.name.contains(query, true) || it.author.contains(query, true) }
        info.text = when {
            entries.isEmpty() -> "아직 올라온 레이아웃이 없습니다. 첫 번째로 올려 보세요!"
            shown.isEmpty() -> "'$query' 에 맞는 레이아웃이 없습니다"
            else -> "레이아웃 ${shown.size}개" + if (shown.size != entries.size) " (모두 ${entries.size}개)" else ""
        }
        val cards = shown.map { e -> card(e) }
        if (cards.isNotEmpty()) list.addView(Ui.grid(this, cards, 260))
        Ui.restyle(list)
    }

    private fun card(e: Community.Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@CommunityActivity, Ui.surface(), 18, Ui.line())
        setPadding(dp(14), dp(14), dp(14), dp(12))
        // 미리보기 그림(16:9 정도)
        addView(preview(e), LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(10) })
        addView(TextView(this@CommunityActivity).apply {
            text = e.name; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text); setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(this@CommunityActivity).apply {
            val tier = Community.shownTier(e.author, e.tier)
            text = Community.styled((if (e.id in waiting) "⏳ 목록에 반영 중 · " else if (Community.mine(e.id)) "내가 올림 · " else "") +
                "by ${e.author}" + (if (tier.isNotEmpty()) " " else ""), tier,
                " · 버튼 ${e.buttons}개 · ${Community.date(e.created)}" + (if (e.bytes > 0) " · ${(e.bytes + 1023) / 1024}KB" else ""), resources.displayMetrics.density)
            textSize = 12f; setTextColor(Ui.muted()); setPadding(0, dp(3), 0, dp(10))
        })
        val row = LinearLayout(this@CommunityActivity)
        row.addView(Button(this@CommunityActivity).apply { text = "⬇ 받기"; tag = Ui.PRIMARY; setOnClickListener { download(e) } },
            LinearLayout.LayoutParams(0, -2, 1f))
        // 이 기기에서 올린 것은 지울 수 있다. 관리자는 모든 레이아웃을 지울 수 있다.
        if (Community.mine(e.id) || Community.isAdmin) row.addView(Button(this@CommunityActivity).apply {
            text = if (Community.mine(e.id)) "🗑 지우기" else "🗑 지우기(관리자)"; setOnClickListener { confirmDelete(e) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun confirmDelete(e: Community.Entry) {
        android.app.AlertDialog.Builder(this)
            .setMessage("커뮤니티에서 '${e.name}' 을(를) 지울까요? 이미 받아 간 사람의 레이아웃은 그대로 남습니다.")
            .setPositiveButton("지우기") { _, _ ->
                toast("지우는 중…")
                Thread {
                    val problem = Community.delete(e.id)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (problem == null) {
                            entries = entries.filter { it.id != e.id }; fill()
                            toast("지웠습니다. 1~2분 뒤 목록에서도 사라집니다")
                        } else toast(problem)
                    }
                }.start()
            }
            .setNegativeButton("취소", null).show()
    }

    /** 받은 레이아웃 파일(미리보기와 받기에 같이 쓴다). */
    private val files = HashMap<String, String>()

    private fun download(e: Community.Entry) {
        toast("'${e.name}' 받는 중…")
        Thread {
            val result = runCatching {
                val file = files[e.id] ?: Community.fetchLayoutFile(e.id).also { files[e.id] = it }
                val l = LayoutFile.import(Community.layoutText(file)) { name, bytes -> ImageStore.write(this, name, bytes) }
                LayoutStore.save(this, l)
                l
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    android.app.AlertDialog.Builder(this)
                        .setMessage("'${it.name}' 을(를) 내 레이아웃에 추가했습니다.")
                        .setPositiveButton("바로 실행") { _, _ -> startActivity(android.content.Intent(this, PlayActivity::class.java).putExtra("id", it.id)) }
                        .setNeutralButton("편집") { _, _ -> startActivity(android.content.Intent(this, EditorActivity::class.java).putExtra("id", it.id)) }
                        .setNegativeButton("닫기", null).show()
                }.onFailure { err -> toast("받지 못했습니다: " + (err.message ?: err.javaClass.simpleName)) }
            }
        }.start()
    }

    /** 카드의 미리보기 칸: 레이아웃 파일을 받아 버튼 배치를 작은 그림으로 보여 준다. */
    private fun preview(e: Community.Entry): View {
        val box = android.widget.FrameLayout(this).apply {
            background = Ui.rounded(this@CommunityActivity, Themes.current.bg, 12, Ui.line())
        }
        val wait = TextView(this).apply { text = "미리보기 불러오는 중…"; textSize = 12f; setTextColor(Ui.muted()); gravity = Gravity.CENTER }
        box.addView(wait, android.widget.FrameLayout.LayoutParams(-1, -1))
        Thread {
            val got = runCatching {
                val file = files[e.id] ?: Community.fetchLayoutFile(e.id).also { synchronized(files) { files[e.id] = it } }
                LayoutFile.import(Community.layoutText(file))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                got.onSuccess { l -> box.removeAllViews(); box.addView(LayoutThumb(this, l), android.widget.FrameLayout.LayoutParams(-1, -1)) }
                    .onFailure { wait.text = "미리보기를 불러오지 못했습니다" }
            }
        }.start()
        return box
    }

    /** 올릴 레이아웃 고르기 → 이름 · 만든 사람 확인 → 올리기. */
    private fun chooseUpload() {
        // 앱에 처음부터 들어 있는 예시 레이아웃(그대로인 것)은 올리지 않는다
        val mine = LayoutStore.list(this).filterNot { Community.isBuiltIn(it) }
        if (mine.isEmpty()) { toast("올릴 수 있는 레이아웃이 없습니다. 기본 레이아웃은 올릴 수 없고, 고쳐서 내 것으로 만들면 올릴 수 있습니다"); return }
        android.app.AlertDialog.Builder(this).setTitle("올릴 레이아웃")
            .setItems(mine.map { it.name }.toTypedArray()) { _, i -> confirmUpload(mine[i]) }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun confirmUpload(l: PadLayout) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply { hint = "레이아웃 이름"; setText(l.name); setSingleLine() }
        val author = EditText(this).apply { hint = "만든 사람(닉네임, 비우면 익명)"; setText(Link.prefs.getString("communityAuthor", "")); setSingleLine() }
        box.addView(name); box.addView(author)
        box.addView(TextView(this).apply {
            text = "올린 레이아웃은 누구나 볼 수 있고 받을 수 있습니다. 사진이 들어 있으면 사진도 함께 올라갑니다."
            textSize = 12.5f; setTextColor(Ui.muted()); setPadding(0, dp(8), 0, 0)
        })
        android.app.AlertDialog.Builder(this).setTitle("커뮤니티에 올리기")
            .setView(box)
            .setPositiveButton("올리기") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { l.name }
                val a = author.text.toString().trim()
                if (Community.isAdminName(a) && !Community.isAdmin) { askAdminPassword(a) { upload(l, n, a) }; return@setPositiveButton }
                Link.prefs.edit().putString("communityAuthor", a).apply()
                upload(l, n, a)
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun upload(l: PadLayout, name: String, author: String) {
        if (Community.isBuiltIn(l)) { toast("기본 레이아웃은 올릴 수 없습니다"); return }
        val text = LayoutFile.export(l) { n -> ImageStore.read(this, n) }
        if (text.length > Community.MAX_UPLOAD) { toast("레이아웃이 너무 큽니다(사진이 많으면 커집니다)"); return }
        toast("올리는 중…")
        Thread {
            val problem = Community.upload(name, author, text, l.buttons.size)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.app.AlertDialog.Builder(this)
                    .setMessage(problem ?: "올렸습니다! 내 화면에는 바로 보이고, 다른 사람에게는 곧 보입니다.")
                    .setPositiveButton("확인") { _, _ -> if (problem == null) { fillAccount(); load() } }
                    .show()
            }
        }.start()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}

/** 매니저 아이콘: 검정 둥근 사각형 안에 흰색 굵은 'M'. */
class ManagerBadge(private val density: Float) : android.text.style.ReplacementSpan() {
    override fun getSize(p: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int =
        (p.textSize * 1.25f).toInt() + (2 * density).toInt()

    override fun draw(c: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint) {
        val size = p.textSize * 1.25f
        val cy = (top + bottom) / 2f
        val box = android.graphics.RectF(x, cy - size / 2, x + size, cy + size / 2)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        c.drawRoundRect(box, size * 0.22f, size * 0.22f, bg)
        val fg = android.graphics.Paint(p).apply {
            color = 0xFFFFFFFF.toInt(); isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = p.textSize * 0.9f
        }
        c.drawText("M", box.centerX(), cy - (fg.descent() + fg.ascent()) / 2, fg)
    }
}
