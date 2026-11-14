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
            text = "연결 설정"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        header.addView(Button(this).apply { text = "＋ 새 레이아웃"; setOnClickListener { newLayout() } })
        root.addView(header)

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
        refresh()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun refresh() {
        list.removeAllViews()
        val layouts = LayoutStore.list(this)
        if (layouts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "레이아웃이 없습니다. '＋ 새 레이아웃'으로 만들어 보세요."
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
            texts.addView(TextView(this).apply { text = l.name; textSize = 18f; setTextColor(Themes.current.text) })
            texts.addView(TextView(this).apply { text = "버튼 ${l.buttons.size}개 · 눌러서 실행"; textSize = 13f })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "편집"; setOnClickListener { open(EditorActivity::class.java, l.id) } })
            row.addView(Button(this).apply {
                text = "⋮"
                setOnClickListener { v ->
                    PopupMenu(this@MainActivity, v).apply {
                        menu.add("이름 변경"); menu.add("복제"); menu.add("삭제")
                        setOnMenuItemClickListener { item ->
                            when (item.title) {
                                "이름 변경" -> askName("이름 변경", l.name) { l.name = it; LayoutStore.save(this@MainActivity, l); refresh() }
                                "복제" -> {
                                    val c = PadLayout(name = l.name + " 복사본", slide = l.slide,
                                        buttons = l.buttons.map { it.copy() }.toMutableList())
                                    LayoutStore.save(this@MainActivity, c); refresh()
                                }
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
}
