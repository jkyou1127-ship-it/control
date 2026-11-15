package com.controlpad.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
            status.text = (if (Link.connected) "● " else "○ ") + Link.status()
            status.setTextColor(if (Link.connected) 0xFF81C784.toInt() else 0xFFFFB74D.toInt())
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "ControlPad"; textSize = 24f; setTextColor(Themes.current.text) })
        status = TextView(this).apply { textSize = 14f; setPadding(pad, 0, pad, 0) }
        header.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "설정"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
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
        header.addView(Button(this).apply { text = "＋ 새 레이아웃"; setOnClickListener { newLayout() } })
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
        root.addView(header)

        search = android.widget.EditText(this).apply {
            hint = "레이아웃 검색"; setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refresh()
            })
        }
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
        val layouts = LayoutList.arrange(everything, search.text.toString())
        if (layouts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (everything.isEmpty()) "레이아웃이 없습니다. '＋ 새 레이아웃'으로 만들어 보세요." else "검색 결과가 없습니다."
                setPadding(0, dp(24), 0, 0)
            })
        }
        for (l in layouts) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setBackgroundColor(Themes.current.card)
                isClickable = true
                setOnClickListener { open(PlayActivity::class.java, l.id) }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = (if (l.pinned) "★ " else "") + l.name; textSize = 18f; setTextColor(Themes.current.text) })
            texts.addView(TextView(this).apply { text = "버튼 ${l.buttons.size}개 · 눌러서 실행"; textSize = 13f })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "편집"; setOnClickListener { open(EditorActivity::class.java, l.id) } })
            row.addView(Button(this).apply {
                text = "⋮"
                setOnClickListener { v ->
                    PopupMenu(this@MainActivity, v).apply {
                        val pin = if (l.pinned) "고정 해제" else "맨 위에 고정"
                        menu.add(pin); menu.add("홈 화면에 추가")
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
            }, LinearLayout.LayoutParams(dp(56), -2))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
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
