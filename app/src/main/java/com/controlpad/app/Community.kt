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
 */
object Community {
    class Entry(val id: String, val name: String, val author: String, val created: String, val buttons: Int, val bytes: Int)

    /** 저장소의 community 폴더(GitHub 에서 바로 읽는 주소). */
    val BASE: String get() = Updater.BASE.removeSuffix("release/") + "community/"

    const val MAX_UPLOAD = 900_000          // 중계 서버의 COMMUNITY_MAX 와 같다

    fun parseIndex(json: String): List<Entry> {
        val a = JSONObject(json).optJSONArray("layouts") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (!Regex("^[0-9A-Za-z_-]{1,64}$").matches(id)) return@mapNotNull null      // 주소에 넣으므로 모양을 확인한다
            Entry(id, o.optString("name").take(40), o.optString("author").take(20).ifEmpty { "익명" },
                o.optString("created"), o.optInt("buttons"), o.optInt("bytes"))
        }
    }

    /** 받은 파일에서 레이아웃 글자(파일로 저장 형식)를 꺼낸다. */
    fun layoutText(file: String): String = JSONObject(file).getJSONObject("layout").toString()

    /** 중계 서버에 보낼 내용. */
    fun uploadBody(name: String, author: String, layoutText: String): String =
        JSONObject().put("name", name.take(40)).put("author", author.take(20)).put("layout", layoutText).toString()

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
            else -> if (code.startsWith("github_")) "저장소에 올리지 못했습니다($code). 잠시 뒤 다시 해 보세요" else "올리지 못했습니다(HTTP $status)"
        }
    }

    fun upload(name: String, author: String, layoutText: String): String? = runCatching {
        val relay = Labs.relay
        if (relay.isEmpty()) return "커뮤니티 서버가 아직 준비되지 않았습니다"
        val c = URL(relay.trimEnd('/') + "/community").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 30000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            c.outputStream.use { it.write(uploadBody(name, author, layoutText).toByteArray()) }
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
            problem(status, body)
        } finally {
            c.disconnect()
        }
    }.getOrElse { "커뮤니티 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요" }
}

/** 커뮤니티 화면: 위쪽에 '내 레이아웃 올리기'와 검색, 아래는 올라온 레이아웃 카드 격자(⬇ 받기). */
class CommunityActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var info: TextView
    private var entries: List<Community.Entry> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(Ui.header(this, "🌐 레이아웃 커뮤니티", "레이아웃을 올려 나누고, 다른 사람이 올린 것을 받습니다"))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply { text = "⬆ 내 레이아웃 올리기"; tag = Ui.PRIMARY; setOnClickListener { chooseUpload() } })
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
            val got = runCatching { Community.parseIndex(Labs.get(Community.BASE + "index.json")) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                got.onSuccess { entries = it; fill() }
                    .onFailure { info.text = "목록을 받지 못했습니다. 인터넷 연결을 확인하세요" }
            }
        }.start()
    }

    private fun fill() {
        list.removeAllViews()
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
        addView(TextView(this@CommunityActivity).apply {
            text = e.name; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Themes.current.text); setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(this@CommunityActivity).apply {
            text = "by ${e.author} · 버튼 ${e.buttons}개 · ${Community.date(e.created)}" + if (e.bytes > 0) " · ${(e.bytes + 1023) / 1024}KB" else ""
            textSize = 12f; setTextColor(Ui.muted()); setPadding(0, dp(3), 0, dp(10))
        })
        addView(Button(this@CommunityActivity).apply { text = "⬇ 받기"; tag = Ui.PRIMARY; setOnClickListener { download(e) } },
            LinearLayout.LayoutParams(-1, -2))
    }

    private fun download(e: Community.Entry) {
        toast("'${e.name}' 받는 중…")
        Thread {
            val result = runCatching {
                val text = Community.layoutText(Labs.get(Community.BASE + "layouts/" + e.id + ".json"))
                val l = LayoutFile.import(text) { name, bytes -> ImageStore.write(this, name, bytes) }
                LayoutStore.save(this, l)
                l
            }
            runOnUiThread {
                result.onSuccess { toast("'${it.name}' 을(를) 내 레이아웃에 추가했습니다") }
                    .onFailure { toast("받지 못했습니다. 잠시 뒤 다시 해 보세요") }
            }
        }.start()
    }

    /** 올릴 레이아웃 고르기 → 이름 · 만든 사람 확인 → 올리기. */
    private fun chooseUpload() {
        val mine = LayoutStore.list(this)
        if (mine.isEmpty()) { toast("올릴 레이아웃이 없습니다"); return }
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
                Link.prefs.edit().putString("communityAuthor", a).apply()
                upload(l, n, a)
            }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun upload(l: PadLayout, name: String, author: String) {
        val text = LayoutFile.export(l) { n -> ImageStore.read(this, n) }
        if (text.length > Community.MAX_UPLOAD) { toast("레이아웃이 너무 큽니다(사진이 많으면 커집니다)"); return }
        toast("올리는 중…")
        Thread {
            val problem = Community.upload(name, author, text)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.app.AlertDialog.Builder(this)
                    .setMessage(problem ?: "올렸습니다! 1~2분 뒤 목록에 나타납니다.")
                    .setPositiveButton("확인") { _, _ -> if (problem == null) load() }
                    .show()
            }
        }.start()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
