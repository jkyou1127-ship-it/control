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
        fun title(t: String) = TextView(this).apply { text = t; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, pad, 0, dp(4)) }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 14f; setLineSpacing(0f, 1.2f) }

        root.addView(TextView(this).apply { text = "연결 설정"; textSize = 24f; setTextColor(Color.WHITE) })
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
            "PC에서 controlpad_receiver.py 를 실행하면 창에 PC 주소가 표시됩니다. 그 주소를 아래에 입력하세요. " +
                "태블릿과 PC가 같은 공유기에 연결되어 있어야 합니다."
        ))
        val host = EditText(this).apply {
            hint = "PC 주소 (예: 192.168.0.10)"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Link.prefs.getString("udpHost", ""))
        }
        val port = EditText(this).apply {
            hint = "포트"; setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER
            setText(Link.prefs.getInt("udpPort", Link.DEFAULT_PORT).toString())
        }
        udpBox.addView(host); udpBox.addView(port)
        udpBox.addView(Button(this).apply {
            text = "저장하고 연결 확인"
            setOnClickListener {
                val p = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: Link.DEFAULT_PORT
                Link.prefs.edit().putString("udpHost", host.text.toString().trim()).putInt("udpPort", p).apply()
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
        })
        root.addView(udpBox)

        root.addView(title("기타"))
        root.addView(CheckBox(this).apply {
            text = "버튼을 누를 때 진동"; isChecked = Link.haptic
            setOnCheckedChangeListener { _, on -> Link.prefs.edit().putBoolean("haptic", on).apply() }
        })

        root.addView(title("업데이트"))
        root.addView(note("현재 버전 " + Updater.installedName(this) + " · 앱을 켤 때 새 버전을 자동으로 확인합니다."))
        root.addView(CheckBox(this).apply {
            text = "알파 빌드 받기 — 최신 빌드를 바로 받습니다. 끄면 한 번 전의 빌드(정식)를 받습니다"
            isChecked = Updater.alpha
            setOnCheckedChangeListener { _, on -> Updater.alpha = on }
        })
        root.addView(Button(this).apply { text = "업데이트 확인"; setOnClickListener { Updater.check(this@SettingsActivity) } })

        setContentView(ScrollView(this).apply { addView(root) })

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
}
