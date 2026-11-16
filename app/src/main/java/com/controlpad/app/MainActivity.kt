package com.controlpad.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

/** 저장된 레이아웃 목록. 누르면 바로 컨트롤러로 실행한다. */
class MainActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var search: android.widget.EditText
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            // NES3: 연결 상태 알약은 상태에 따라 테두리 색도 바꾼다(연결됨 초록 · PC 일시정지 노랑 · 끊김 주황).
            val color = when {
                Link.pcPaused -> 0xFFFBBF24.toInt()
                Link.connected -> 0xFF81C784.toInt()
                else -> 0xFFFFB74D.toInt()
            }
            status.text = (if (Link.connected) "● " else "○ ") + Link.status()
            status.setTextColor(color)
            status.background = Ui.rounded(this@MainActivity, Ui.surface(), 20, color and 0x00FFFFFF or 0x88000000.toInt())
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 마지막 레이아웃 바로 실행(실험실 기능): 앱을 처음 켤 때만(화면을 돌리거나 돌아올 때는 아님).
        if (savedInstanceState == null && Labs.isOn("open-last")) {
            val last = Link.prefs.getString("lastLayout", null)
            if (last != null && LayoutStore.load(this, last) != null) startActivity(Intent(this, PlayActivity::class.java).putExtra("id", last))
        }
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        // 위쪽은 제목 줄(이름 + 연결 상태 + 설정), 그 아래 도구 줄(가로로 밀 수 있음)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "ControlPad"; textSize = 26f; setTextColor(Themes.current.text); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        status = TextView(this).apply {
            textSize = 13f; setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ui.rounded(this@MainActivity, Ui.surface(), 20, Ui.line())
        }
        top.addView(status, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // NES2: 설정은 제목 줄 오른쪽으로
        top.addView(Button(this).apply {
            text = "⚙ 설정"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(top)
        // 목록 인사말(실험실 기능)
        if (Labs.isOn("list-greeting")) root.addView(TextView(this).apply {
            text = Greeting.now(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
            textSize = 14f; setTextColor(Ui.muted()); setPadding(dp(2), dp(4), 0, 0)
        })
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(6)) }
        header.addView(Button(this).apply {
            text = "불러오기"
            setOnClickListener {
                // 파일 형식을 json 으로만 제한하면 파일 앱에 따라 고를 수 없는 경우가 있어 모든 파일을 보여 준다.
                runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT,
                    )
                }.onFailure { toast("파일을 고를 수 있는 앱이 없습니다") }
            }
        })
        header.addView(Button(this).apply {
            fun show() { text = "정렬: " + ListSort.names[Link.listSort] }
            show()
            setOnClickListener { Link.listSort = (Link.listSort + 1) % ListSort.names.size; show(); refresh() }
        })
        header.addView(Button(this).apply { text = "＋ 새 레이아웃"; tag = Ui.PRIMARY; setOnClickListener { newLayout() } })
        header.addView(Button(this).apply {
            text = "✎ 그림 레이아웃"
            setOnClickListener {
                // 빈 레이아웃을 만들고 그리기를 켠 편집 화면을 연다: 손으로 그린 그림이 그대로 버튼이 된다.
                askName("그림 레이아웃 이름", "그림 레이아웃") { name ->
                    val l = PadLayout(name = name)
                    LayoutStore.save(this@MainActivity, l)
                    startActivity(Intent(this@MainActivity, EditorActivity::class.java).putExtra("id", l.id).putExtra("sketch", true))
                }
            }
        })
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(header) })

        search = android.widget.EditText(this).apply {
            hint = "레이아웃 검색"; setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refresh()
            })
        }
        // 검색·고정·바로가기는 실험실 기능이라, 켜져 있을 때만 보인다.
        if (!Labs.isOn("list-search")) search.visibility = android.view.View.GONE
        root.addView(search)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // 처음 실행하면 바로 써 볼 수 있게 예시를 넣어 둔다.
        // 나중 버전에서 예시가 늘어나면, 이미 쓰고 있던 사람에게는 새로 생긴 것만 넣는다.
        val last = Templates.names.lastIndex
        val have = Link.prefs.getInt("seedCount", if (Link.prefs.getBoolean("seeded", false)) 6 else 0)
        if (have < last) {
            for (i in have + 1..last) LayoutStore.save(this, Templates.create(i, Templates.names[i]))
            Link.prefs.edit().putBoolean("seeded", true).putInt("seedCount", last).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        Updater.autoCheck(this)
        Labs.refresh()
        // 업데이트한 뒤 처음 켰을 때 한 번, 무엇이 바뀌었는지 보여 준다(처음 설치했을 때는 보여 주지 않는다).
        val seen = Link.prefs.getString("seenVersion", null)
        val now = Updater.installedName(this)
        if (seen != now) {
            Link.prefs.edit().putString("seenVersion", now).apply()
            if (seen != null) showChangelog(this, latestOnly = true)
        }
        refresh()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun refresh() {
        list.removeAllViews()
        val everything = LayoutStore.list(this)
        val pinOn = Labs.isOn("list-pin")
        val layouts = LayoutList.arrange(ListSort.apply(everything, Link.listSort), if (Labs.isOn("list-search")) search.text.toString() else "", pinOn)
        if (Labs.isOn("list-count") && everything.isNotEmpty()) list.addView(TextView(this).apply {
            text = "레이아웃 ${everything.size}개" + if (layouts.size != everything.size) " 중 ${layouts.size}개" else ""
            setPadding(0, dp(8), 0, 0)
        })
        val kinds = Labs.isOn("list-kinds")
        if (layouts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (everything.isEmpty()) "레이아웃이 없습니다. '＋ 새 레이아웃'으로 만들어 보세요." else "검색 결과가 없습니다."
                setPadding(0, dp(24), 0, 0)
            })
        }
        // NES2: 레이아웃을 카드 격자로 보여 준다(화면이 넓을수록 한 줄에 더 많이).
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = ((widthDp - 32) / Mobile.cardWidthDp()).toInt().coerceIn(1, 5)
        var line: LinearLayout? = null
        layouts.forEachIndexed { i, l ->
            if (i % columns == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(this@MainActivity, Ui.surface(), 18, Ui.line())
                setPadding(dp(12), dp(12), dp(12), dp(10))
                isClickable = true
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.accent() and 0x00FFFFFF or 0x33000000), null,
                    Ui.rounded(this@MainActivity, -1, 18))
                setOnClickListener { open(PlayActivity::class.java, l.id) }
            }
            if (Link.thumbnails) card.addView(LayoutThumb(this, l), LinearLayout.LayoutParams(-1, 0).apply { height = dp(Mobile.thumbHeightDp()) })
            card.addView(TextView(this).apply {
                text = (if (pinOn && l.pinned) "★ " else "") + l.name; textSize = 17f; setTextColor(Themes.current.text)
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(10), dp(2), 0)
            })
            card.addView(TextView(this).apply {
                text = "버튼 ${l.buttons.size}개" + (if (kinds) " · " + LayoutKinds.describe(l) else "") +
                    // 목록에 마지막 실행 시각(실험실 기능)
                    (if (Labs.isOn("list-last-played")) " · " + LastPlayed.describe(Link.prefs.getLong("played_" + l.id, 0L), System.currentTimeMillis()) else "")
                textSize = 12f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Themes.current.text and 0x00FFFFFF or 0x99000000.toInt()); setPadding(dp(2), dp(2), dp(2), dp(8))
            })
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            actions.addView(Button(this).apply { text = "▶ 실행"; tag = Ui.PRIMARY; setOnClickListener { open(PlayActivity::class.java, l.id) } },
                LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this).apply { text = "편집"; setOnClickListener { open(EditorActivity::class.java, l.id) } },
                LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this).apply { text = "⋮"; setOnClickListener { v -> layoutMenu(l, v, pinOn) } },
                LinearLayout.LayoutParams(dp(52), -2))
            card.addView(actions)
            line!!.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i % columns != 0) leftMargin = dp(12)
            })
        }
        // 마지막 줄이 덜 찼으면 빈 칸으로 채워 카드 너비를 맞춘다.
        val rest = (columns - layouts.size % columns) % columns
        repeat(rest) { line?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(12) }) }
        Ui.restyle(list)       // 줄마다 새로 만든 버튼들에도 새 UI 모양을 입힌다
    }

    /** 레이아웃 카드의 ⋮ 메뉴. */
    private fun layoutMenu(l: PadLayout, v: View, pinOn: Boolean) {
        PopupMenu(this@MainActivity, v).apply {
            val pin = if (l.pinned) "고정 해제" else "맨 위에 고정"
            if (pinOn) menu.add(pin)
            if (Labs.isOn("home-shortcut")) menu.add("홈 화면에 추가")
            menu.add("그림 에디터"); menu.add("이름 변경"); menu.add("복제"); menu.add("파일로 저장"); menu.add("삭제")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "그림 에디터" -> startActivity(
                        Intent(this@MainActivity, EditorActivity::class.java).putExtra("id", l.id).putExtra("sketch", true),
                    )
                    "이름 변경" -> askName("이름 변경", l.name) { l.name = it; LayoutStore.save(this@MainActivity, l); refresh() }
                    "복제" -> {
                        val c = PadLayout(name = l.name + " 복사본", slide = l.slide,
                            buttons = l.buttons.map { it.copy() }.toMutableList())
                        LayoutStore.save(this@MainActivity, c); refresh()
                    }
                    "파일로 저장" -> {
                        exporting = l
                        runCatching {
                            @Suppress("DEPRECATION")
                            startActivityForResult(
                                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("application/json")
                                    .putExtra(Intent.EXTRA_TITLE, LayoutFile.fileName(l.name)),
                                REQ_EXPORT,
                            )
                        }.onFailure { toast("파일을 저장할 수 있는 앱이 없습니다") }
                    }
                    pin -> { l.pinned = !l.pinned; LayoutStore.save(this@MainActivity, l); refresh() }
                    "홈 화면에 추가" -> addShortcut(l)
                    "삭제" -> AlertDialog.Builder(this@MainActivity)
                        .setMessage("'${l.name}' 레이아웃을 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ -> LayoutStore.delete(this@MainActivity, l.id); refresh() }
                        .setNegativeButton("취소", null).show()
                }
                true
            }
        }.show()
    }

    /** 홈 화면에 이 레이아웃을 바로 실행하는 바로가기를 만든다. */
    private fun addShortcut(l: PadLayout) {
        val manager = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            toast("이 홈 화면은 바로가기 추가를 지원하지 않습니다")
            return
        }
        val info = android.content.pm.ShortcutInfo.Builder(this, "layout-" + l.id)
            .setShortLabel(l.name.ifBlank { "레이아웃" })
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, PlayActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("id", l.id))
            .build()
        runCatching { manager.requestPinShortcut(info, null) }.onFailure { toast("바로가기를 추가하지 못했습니다") }
    }

    private var exporting: PadLayout? = null

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> {
                val l = exporting ?: return
                exporting = null
                runCatching {
                    contentResolver.openOutputStream(uri)!!.use { it.write(LayoutFile.export(l) { name -> ImageStore.read(this, name) }.toByteArray()) }
                }.onSuccess { toast("'${l.name}' 레이아웃을 파일로 저장했습니다") }
                    .onFailure { toast("파일로 저장하지 못했습니다") }
            }
            REQ_IMPORT -> {
                val l = runCatching {
                    // 레이아웃 파일은 작다. 엉뚱한 큰 파일을 골랐을 때 통째로 읽지 않도록 크기를 제한한다.
                    val text = contentResolver.openInputStream(uri)!!.use { input ->
                        val buf = ByteArray(LayoutFile.MAX_BYTES + 1)
                        var n = 0
                        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                        require(n <= LayoutFile.MAX_BYTES)
                        String(buf, 0, n)
                    }
                    LayoutFile.import(text) { name, bytes -> ImageStore.write(this, name, bytes) }
                }.getOrNull()
                if (l == null) { toast("ControlPad 레이아웃 파일이 아닙니다"); return }
                LayoutStore.save(this, l)
                refresh()
                toast("'${l.name}' 레이아웃을 불러왔습니다")
            }
        }
    }

    private fun open(cls: Class<*>, id: String) = startActivity(Intent(this, cls).putExtra("id", id))

    private fun newLayout() {
        AlertDialog.Builder(this).setTitle("새 레이아웃")
            .setItems(Templates.names.toTypedArray()) { _, which ->
                val default = if (which == 0) "내 레이아웃" else Templates.names[which]
                askName("레이아웃 이름", default) { name ->
                    val l = Templates.create(which, name)
                    LayoutStore.save(this, l)
                    open(EditorActivity::class.java, l.id)
                }
            }.show()
    }

    private fun askName(title: String, initial: String, done: (String) -> Unit) {
        val edit = EditText(this).apply { setText(initial); setSingleLine(); selectAll() }
        AlertDialog.Builder(this).setTitle(title).setView(edit)
            .setPositiveButton("확인") { _, _ -> done(edit.text.toString().trim().ifEmpty { initial }) }
            .setNegativeButton("취소", null).show()
    }

    companion object {
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2
    }
}
