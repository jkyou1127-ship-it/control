package com.controlpad.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** PC 와 연결하는 방법을 고르고 연결한다. */
class SettingsActivity : BaseActivity() {
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btBox: LinearLayout
    private lateinit var udpBox: LinearLayout
    private lateinit var devices: LinearLayout
    private var shownDevices = ""
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            status.text = (if (Link.connected) "● " else "○ ") + Link.status()
            status.setTextColor(if (Link.connected) 0xFF81C784.toInt() else 0xFFFFB74D.toInt())
            if (Link.mode == Link.MODE_BT) showDevices()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = SectionedLayout(this).apply { setPadding(pad, pad, pad, pad) }
        fun title(t: String) = Ui.section(this, t)
        fun note(t: String) = TextView(this).apply { text = t; textSize = 14f; setLineSpacing(0f, 1.2f) }

        root.addView(Ui.header(this, "설정", "연결 · 테마 · 오프셋 · 화면"))
        root.addView(Ui.tabs(this))
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, 0) }
        root.addView(status)

        root.addView(title("연결 방식"))
        val group = RadioGroup(this)
        val rbBt = RadioButton(this).apply { id = 1; text = "블루투스 키보드 — PC에 설치할 것 없음" }
        val rbUdp = RadioButton(this).apply { id = 2; text = "Wi-Fi / USB 테더링 — PC 수신 프로그램 필요, 지연이 가장 적음" }
        group.addView(rbBt); group.addView(rbUdp)
        root.addView(group)

        // --- 블루투스 ---
        btBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btBox.addView(title("블루투스"))
        btBox.addView(note(
            "처음 한 번: 아래 '검색 허용'을 누른 뒤 PC의 블루투스 설정에서 '장치 추가'로 이 태블릿을 찾아 연결하세요. " +
                "키보드로 인식됩니다.\n이미 태블릿과 PC가 페어링되어 있는데 키보드로 동작하지 않으면, " +
                "양쪽에서 페어링을 지우고 이 화면을 켠 상태로 다시 페어링하세요."
        ))
        btBox.addView(Button(this).apply { text = "PC에서 검색 허용 (5분)"; setOnClickListener { discoverable() } })
        btBox.addView(note("페어링된 기기 — 눌러서 연결"))
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btBox.addView(devices)
        btBox.addView(Button(this).apply { text = "연결 끊기"; setOnClickListener { Link.bt?.disconnect() } })
        root.addView(btBox)

        // --- UDP ---
        udpBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        udpBox.addView(title("Wi-Fi / USB 테더링"))
        udpBox.addView(note(
            "PC(Windows)에서 ControlPadReceiver.exe 를 실행한 뒤 아래 'PC 자동으로 찾기'를 누르세요. " +
                "태블릿과 PC가 같은 공유기에 연결되어 있어야 합니다.\n\n" +
                "수신 프로그램은 PC 브라우저에서 아래 주소로 받습니다. 한 번 받으면 실행할 때마다 스스로 업데이트합니다."
        ))
        udpBox.addView(TextView(this).apply {
            text = Updater.RECEIVER_URL; textSize = 13f; setTextIsSelectable(true); setPadding(0, dp(4), 0, dp(8))
        })
        val host = EditText(this).apply {
            hint = "PC 주소 (예: 192.168.0.10)"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Link.prefs.getString("udpHost", ""))
        }
        val port = EditText(this).apply {
            hint = "포트"; setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER
            setText(Link.prefs.getInt("udpPort", Link.DEFAULT_PORT).toString())
        }
        fun portNumber() = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: Link.DEFAULT_PORT
        fun saveAndTest() {
            Link.prefs.edit().putString("udpHost", host.text.toString().trim()).putInt("udpPort", portNumber()).apply()
            Link.ensure()
            // 빈 상태를 몇 번 보내 응답이 오는지 본다.
            repeat(3) { i -> ui.postDelayed({ Link.send(ByteArray(KeyState.REPORT_SIZE)) }, i * 150L) }
            ui.postDelayed({
                Toast.makeText(
                    this@SettingsActivity,
                    if (Link.connected) "PC와 연결되었습니다" else "PC에서 응답이 없습니다. 주소와 수신 프로그램, 방화벽을 확인하세요",
                    Toast.LENGTH_LONG,
                ).show()
            }, 900)
        }
        udpBox.addView(Button(this).apply {
            text = "PC 자동으로 찾기"
            setOnClickListener {
                isEnabled = false
                text = "찾는 중…"
                Discovery.find(portNumber()) { pcs ->
                    isEnabled = true
                    text = "PC 자동으로 찾기"
                    if (isFinishing || isDestroyed) return@find
                    fun choose(pc: Discovery.Pc) { host.setText(pc.address); saveAndTest() }
                    when (pcs.size) {
                        0 -> Toast.makeText(
                            this@SettingsActivity,
                            "PC를 찾지 못했습니다. PC에서 수신 프로그램이 켜져 있는지, 같은 공유기에 연결되어 있는지 확인하세요",
                            Toast.LENGTH_LONG,
                        ).show()
                        1 -> choose(pcs[0])
                        else -> android.app.AlertDialog.Builder(this@SettingsActivity).setTitle("연결할 PC")
                            .setItems(pcs.map { it.name + "  (" + it.address + ")" }.toTypedArray()) { _, i -> choose(pcs[i]) }
                            .show()
                    }
                }
            }
        })
        udpBox.addView(note("찾지 못하면 수신 프로그램 창에 표시된 주소를 직접 입력하세요."))
        udpBox.addView(host); udpBox.addView(port)
        udpBox.addView(Button(this).apply { text = "저장하고 연결 확인"; setOnClickListener { saveAndTest() } })
        root.addView(udpBox)

        root.addView(title("테마"))
        root.addView(android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, Themes.names,
            )
            setSelection(Themes.index)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (pos == Themes.index) return
                    Themes.index = pos
                    reload()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        })
        root.addView(ThemePreview(this), LinearLayout.LayoutParams(-1, dp(110)).apply { topMargin = dp(8) })
        root.addView(Button(this).apply {
            text = "테마 에디터 — 나만의 테마 만들기"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, ThemeEditorActivity::class.java)) }
        })

        root.addView(title("오프셋"))
        root.addView(note("손가락이 닿는 자리와 버튼이 눌리는 자리가 어긋나거나, 다른 기기와 박자가 안 맞을 때 맞춥니다."))
        root.addView(Button(this).apply {
            text = "기본 프리셋: " + (OffsetPresets.current()?.name ?: "직접 맞춤")
            setOnClickListener {
                val names = OffsetPresets.all.map { it.name + "\n   " + it.note }.toTypedArray()
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("오프셋 기본 프리셋")
                    .setItems(names) { _, i -> OffsetPresets.apply(OffsetPresets.all[i]); reload() }
                    .setNegativeButton("닫기", null)
                    .show().wide()
            }
        })
        fun offsetSlider(label: (Int) -> String, min: Int, max: Int, value: Int, set: (Int) -> Unit) {
            val shown = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
            val bar = android.widget.SeekBar(this).apply { this.max = max - min; progress = value - min }
            shown.text = label(value)
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { shown.text = label(p + min); set(p + min) }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
            root.addView(shown); root.addView(bar)
        }
        offsetSlider({ "터치 가로 보정: " + (if (it > 0) "오른쪽 " else if (it < 0) "왼쪽 " else "") + "${kotlin.math.abs(it)}dp" },
            -Link.OFFSET_MAX, Link.OFFSET_MAX, Link.touchOffsetX) { Link.touchOffsetX = it }
        offsetSlider({ "터치 세로 보정: " + (if (it > 0) "아래 " else if (it < 0) "위 " else "") + "${kotlin.math.abs(it)}dp" },
            -Link.OFFSET_MAX, Link.OFFSET_MAX, Link.touchOffsetY) { Link.touchOffsetY = it }
        offsetSlider({ "입력 지연: ${it}ms" + if (it == 0) " (바로 보냄)" else "" }, 0, Link.DELAY_MAX, Link.inputDelay) { Link.inputDelay = it }

        root.addView(title("화면"))
        fun choice(label: String, names: List<String>, selected: Int, set: (Int) -> Unit) {
            root.addView(TextView(this).apply { text = label; setPadding(0, dp(6), 0, 0) })
            root.addView(android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(selected)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = set(pos)
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
            })
        }
        choice("편집·실행 화면 방향", ScreenOrientation.names, Link.orientation) { Link.orientation = it }
        choice("편집 화면 격자 크기", GridSize.names, Link.gridSize) { Link.gridSize = it }
        root.addView(CheckBox(this).apply {
            text = "목록에 레이아웃 미리보기 표시"; isChecked = Link.thumbnails
            setOnCheckedChangeListener { _, on -> Link.thumbnails = on }
        })

        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        if (keepScroll >= 0) scroll.post { scroll.scrollTo(0, keepScroll); keepScroll = -1 }

        group.check(if (Link.mode == Link.MODE_BT) 1 else 2)
        showMode()
        group.setOnCheckedChangeListener { _, id ->
            Link.prefs.edit().putInt("mode", if (id == 1) Link.MODE_BT else Link.MODE_UDP).apply()
            showMode()
            if (id == 1) askBtPermission()
            Link.ensure()
        }
        if (Link.mode == Link.MODE_BT) askBtPermission()
    }

    /** 테마가 바뀌었을 때 화면을 다시 만든다. 보고 있던 위치는 유지한다. */
    private fun reload() {
        keepScroll = scroll.scrollY
        recreate()
    }

    private fun showMode() {
        val bt = Link.mode == Link.MODE_BT
        btBox.visibility = if (bt) android.view.View.VISIBLE else android.view.View.GONE
        udpBox.visibility = if (bt) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun askBtPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !Link.btPermitted(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Link.ensure()
    }

    @SuppressLint("MissingPermission")
    private fun discoverable() {
        if (!Link.btPermitted(this)) { askBtPermission(); return }
        runCatching {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        }.onFailure { Toast.makeText(this, "검색 허용을 켤 수 없습니다", Toast.LENGTH_SHORT).show() }
    }

    @SuppressLint("MissingPermission")
    private fun showDevices() {
        val list = Link.bt?.bonded() ?: emptyList()
        val key = list.joinToString { it.address }
        if (key == shownDevices && devices.childCount > 0) return
        shownDevices = key
        devices.removeAllViews()
        if (list.isEmpty()) {
            devices.addView(TextView(this).apply { text = "(없음)" })
        }
        for (d in list) {
            devices.addView(Button(this).apply {
                text = runCatching { d.name }.getOrNull() ?: d.address
                isAllCaps = false
                setOnClickListener { Link.bt?.connect(d) }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    companion object {
        private var keepScroll = -1
    }
}
