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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun title(t: String) = TextView(this).apply { text = t; textSize = 18f; setTextColor(Themes.current.text); setPadding(0, pad, 0, dp(4)) }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 14f; setLineSpacing(0f, 1.2f) }

        root.addView(TextView(this).apply { text = "연결 설정"; textSize = 24f; setTextColor(Themes.current.text) })
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
                    // 커스텀을 처음 고르면 쓰고 있던 테마에서 시작한다.
                    if (pos == Themes.all.size && !Themes.hasCustom) Themes.copyToCustom(Themes.current)
                    Themes.index = pos
                    reload()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        })
        root.addView(ThemePreview(this), LinearLayout.LayoutParams(-1, dp(110)).apply { topMargin = dp(8) })
        if (Themes.isCustom) {
            fun colorButton(label: String, get: () -> Int, set: (Int) -> Unit) = Button(this).apply {
                text = label + "  " + "#%06X".format(get() and 0xFFFFFF)
                setOnClickListener { pickColor(this@SettingsActivity, get()) { set(it); reload() } }
            }
            root.addView(colorButton("배경색", { Themes.customBg }, { Themes.customBg = it }))
            root.addView(colorButton("글자색", { Themes.customText }, { Themes.customText = it }))
            fun slider(label: (Int) -> String, max: Int, value: Int, set: (Int) -> Unit) {
                val text = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
                val bar = android.widget.SeekBar(this).apply { this.max = max; progress = value.coerceIn(0, max) }
                text.text = label(bar.progress)
                bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { text.text = label(p) }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) { set(bar.progress); reload() }
                })
                root.addView(text); root.addView(bar)
            }
            // 진하기 10~160%, 테두리 0.5~5dp
            slider({ "버튼 색 진하기: ${it * 10 + 10}%" }, 15, ((Themes.customStrength * 100).toInt() - 10) / 10) {
                Themes.customStrength = (it * 10 + 10) / 100f
            }
            slider({ "테두리 굵기: ${(it + 1) * 0.5f}" }, 9, (Themes.customStroke / 0.5f).toInt() - 1) {
                Themes.customStroke = (it + 1) * 0.5f
            }
            root.addView(CheckBox(this).apply {
                text = "글자를 버튼 색으로 표시 (네온 느낌)"; isChecked = Themes.customColorText
                setOnCheckedChangeListener { _, on -> Themes.customColorText = on; reload() }
            })
        } else {
            root.addView(note("맨 아래 '커스텀'을 고르면 배경색, 글자색, 버튼 진하기, 테두리를 직접 정할 수 있습니다."))
        }

        root.addView(title("기타"))
        root.addView(CheckBox(this).apply {
            text = "눌리고 있는 키 표시 — 실행 화면 위쪽에 지금 입력 중인 키를 보여 줍니다"; isChecked = Link.showPressed
            setOnCheckedChangeListener { _, on -> Link.showPressed = on }
        })
        root.addView(CheckBox(this).apply {
            text = "버튼에 키 이름 표시 — 버튼 아래쪽에 그 버튼이 입력하는 키를 작게 보여 줍니다"; isChecked = Link.showKeyNames
            setOnCheckedChangeListener { _, on -> Link.showKeyNames = on; reload() }
        })
        root.addView(CheckBox(this).apply {
            text = "버튼을 누를 때 진동"; isChecked = Link.haptic
            setOnCheckedChangeListener { _, on -> Link.prefs.edit().putBoolean("haptic", on).apply() }
        })

        if (Updater.ENABLED) {
            root.addView(title("업데이트"))
            root.addView(note("현재 버전 " + Updater.installedName(this) + " · 앱을 켤 때 새 버전을 자동으로 확인합니다."))
            root.addView(CheckBox(this).apply {
                text = "자동 업데이트 — 앱을 켤 때 새 버전이 있으면 묻지 않고 바로 설치합니다"
                isChecked = Updater.auto
                setOnCheckedChangeListener { _, on -> Updater.auto = on }
            })
            root.addView(CheckBox(this).apply {
                text = "알파 빌드 받기 — 최신 빌드를 바로 받습니다. 끄면 한 번 전의 빌드(정식)를 받습니다"
                isChecked = Updater.alpha
                setOnCheckedChangeListener { _, on ->
                    Updater.alpha = on
                    // 채널을 바꾸면 그 채널의 새 버전을 바로 확인한다.
                    Updater.check(this@SettingsActivity)
                }
            })
            root.addView(Button(this).apply { text = "업데이트 확인"; setOnClickListener { Updater.check(this@SettingsActivity) } })
        } else {
            root.addView(title("버전"))
            root.addView(note("현재 버전 " + Updater.installedName(this) + " · 업데이트는 Play 스토어에서 받습니다."))
        }

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
