package com.controlpad.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** PC 로 키 상태를 보내는 통로. 블루투스 키보드(HID) 또는 UDP(PC 수신 프로그램) 중 하나를 쓴다. */
object Link {
    const val MODE_BT = 0
    const val MODE_UDP = 1
    const val DEFAULT_PORT = 34555
    private const val HEARTBEAT_MS = 50L

    private lateinit var app: Context
    // 입력을 보내는 스레드는 화면 그리기보다 먼저 돌도록 우선순위를 높인다.
    private val thread = HandlerThread("link", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val worker = Handler(thread.looper)

    @Volatile var bt: BtHid? = null
        private set
    @Volatile var udp: UdpLink? = null
        private set
    @Volatile private var lastReport = ByteArray(KeyState.REPORT_SIZE)
    private var active = false

    val prefs: SharedPreferences get() = app.getSharedPreferences("link", Context.MODE_PRIVATE)
    val mode: Int get() = prefs.getInt("mode", MODE_BT)
    val haptic: Boolean get() = prefs.getBoolean("haptic", true)

    /** 실행 화면 위쪽에 지금 눌리고 있는 키를 보여 줄지. */
    var showPressed: Boolean
        get() = prefs.getBoolean("showPressed", true)
        set(on) = prefs.edit().putBoolean("showPressed", on).apply()

    /** 버튼 아래쪽에 그 버튼이 입력하는 키 이름을 보여 줄지. */
    var showKeyNames: Boolean
        get() = prefs.getBoolean("showKeyNames", true)
        set(on) = prefs.edit().putBoolean("showKeyNames", on).apply()

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** 설정에 맞게 통로를 다시 만든다. 화면이 앞으로 올 때마다 불러도 된다. */
    fun ensure() {
        if (mode == MODE_BT) {
            udp?.close(); udp = null
            val b = bt ?: BtHid(app).also { bt = it }
            b.ensure()
        } else {
            bt?.stop(); bt = null
            val host = prefs.getString("udpHost", "") ?: ""
            val port = prefs.getInt("udpPort", DEFAULT_PORT)
            val u = udp
            if (u == null || u.host != host || u.port != port) {
                u?.close()
                udp = if (host.isBlank()) null else UdpLink(host, port)
            }
        }
    }

    fun send(report: ByteArray) {
        lastReport = report
        worker.post { transmit(report) }
    }

    @Volatile private var mouseButtons = 0

    /** 마우스 상대 이동과 휠 (노브, 트랙패드). */
    fun sendMouse(dx: Int, dy: Int, wheel: Int = 0) {
        if (dx == 0 && dy == 0 && wheel == 0) return
        val buttons = mouseButtons
        worker.post {
            runCatching { bt?.sendMouse(dx, dy, wheel, buttons) }
            runCatching { udp?.sendMouse(dx, dy, wheel, buttons) }
        }
    }

    /** 눌린 마우스 버튼이 바뀌었을 때만 보낸다. */
    fun setMouseButtons(buttons: Int) {
        if (buttons == mouseButtons) return
        mouseButtons = buttons
        worker.post {
            runCatching { bt?.sendMouse(0, 0, 0, buttons) }
            runCatching { udp?.sendMouse(0, 0, 0, buttons) }
        }
    }

    private fun transmit(report: ByteArray) {
        runCatching { bt?.send(report) }
        runCatching { udp?.send(report) }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            // UDP 는 유실될 수 있으므로 현재 상태를 주기적으로 다시 보낸다.
            runCatching { udp?.send(lastReport) }
            runCatching { udp?.sendMouse(0, 0, 0, mouseButtons) }
            worker.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** 연주 화면이 떠 있는 동안만 켠다. */
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        worker.removeCallbacks(heartbeat)
        if (on) worker.post(heartbeat)
        lowLatencyWifi(on && mode == MODE_UDP)
    }

    /** Wi-Fi 절전은 패킷을 수십 ms 씩 묶어 보내므로, 컨트롤러로 쓰는 동안에는 끈다. */
    private fun lowLatencyWifi(on: Boolean) {
        runCatching {
            if (on && wifiLock == null) {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val kind = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(kind, "controlpad").apply { setReferenceCounted(false); acquire() }
            } else if (!on) {
                wifiLock?.release(); wifiLock = null
            }
        }
    }

    val connected: Boolean
        get() = if (mode == MODE_BT) bt?.host != null else udp?.alive == true

    fun status(): String =
        if (mode == MODE_BT) bt?.status() ?: "블루투스 준비 중"
        else {
            val u = udp
            when {
                u == null -> "PC 주소가 설정되지 않음"
                u.alive -> "PC 연결됨 (${u.host})" + if (u.rttMs >= 0) " · 왕복 ${u.rttMs}ms" else ""
                else -> "PC 응답 없음 (${u.host}:${u.port})"
            }
        }

    fun btPermitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

/** 태블릿을 블루투스 키보드로 보이게 한다. PC 에는 아무것도 설치할 필요가 없다. */
@SuppressLint("MissingPermission")
class BtHid(private val ctx: Context) {
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile var host: BluetoothDevice? = null
        private set
    @Volatile private var registered = false
    private var proxyRequested = false
    private var unsupported = false
    @Volatile private var last = ByteArray(KeyState.REPORT_SIZE)

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, reg: Boolean) {
            registered = reg
            if (!reg) { host = null; return }
            // 마지막으로 쓰던 PC 에 자동으로 다시 붙는다.
            val addr = Link.prefs.getString("btAddr", null) ?: return
            val dev = runCatching { adapter?.bondedDevices?.firstOrNull { it.address == addr } }.getOrNull()
            if (dev != null && host == null) runCatching { hid?.connect(dev) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                Link.prefs.edit().putString("btAddr", device.address).apply()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device == host) {
                host = null
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            runCatching { hid?.replyReport(device, type, id, if (id.toInt() == MOUSE_ID) ByteArray(4) else last) }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            runCatching { hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hid = proxy as BluetoothHidDevice
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null; host = null; registered = false; proxyRequested = false
        }
    }

    private fun register() {
        val h = hid ?: return
        if (registered) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "ControlPad", "ControlPad Keyboard and Mouse", "ControlPad",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR,
        )
        // 블루투스가 허용하는 가장 짧은 전송 간격(11.25ms)을 요청한다. 받아들이지 않는 기기에서는 기본값으로 등록한다.
        val qos = android.bluetooth.BluetoothHidDeviceAppQosSettings(
            android.bluetooth.BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
            android.bluetooth.BluetoothHidDeviceAppQosSettings.MAX,
        )
        val ok = runCatching { h.registerApp(sdp, null, qos, ctx.mainExecutor, callback) }.getOrDefault(false)
        if (!ok) runCatching { h.registerApp(sdp, null, null, ctx.mainExecutor, callback) }
    }

    /** 앱이 뒤로 가면 시스템이 등록을 풀어버리므로, 화면이 앞으로 올 때마다 다시 확인한다. */
    fun ensure() {
        val a = adapter
        if (a == null || !Link.btPermitted(ctx) || !a.isEnabled) return
        if (hid != null) { register(); return }
        if (proxyRequested) return
        val ok = runCatching { a.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE) }.getOrDefault(false)
        proxyRequested = ok
        unsupported = !ok
    }

    fun bonded(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()

    fun connect(device: BluetoothDevice) {
        runCatching { hid?.connect(device) }
    }

    fun disconnect() {
        val d = host ?: return
        runCatching { hid?.disconnect(d) }
    }

    fun send(report: ByteArray) {
        last = report
        val d = host ?: return
        hid?.sendReport(d, REPORT_ID, report)
    }

    fun sendMouse(dx: Int, dy: Int, wheel: Int, buttons: Int) {
        val d = host ?: return
        var x = dx
        var y = dy
        var w = wheel.coerceIn(-127, 127)
        do {   // 한 리포트는 축마다 -127~127 까지만 담는다
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            hid?.sendReport(d, MOUSE_ID, byteArrayOf(buttons.toByte(), sx.toByte(), sy.toByte(), w.toByte()))
            x -= sx; y -= sy; w = 0
        } while (x != 0 || y != 0)
    }

    fun stop() {
        val h = hid
        runCatching { host?.let { h?.disconnect(it) } }
        runCatching { h?.unregisterApp() }
        runCatching { if (h != null) adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null; host = null; registered = false; proxyRequested = false
    }

    fun status(): String {
        val a = adapter
        return when {
            a == null -> "이 기기에는 블루투스가 없습니다"
            !Link.btPermitted(ctx) -> "블루투스 권한이 필요합니다"
            !a.isEnabled -> "블루투스가 꺼져 있습니다"
            unsupported -> "이 기기는 블루투스 키보드 기능(HID)을 지원하지 않습니다"
            host != null -> "연결됨: " + (runCatching { host?.name }.getOrNull() ?: "PC")
            registered -> "PC 연결 대기 중"
            else -> "블루투스 키보드 준비 중…"
        }
    }

    companion object {
        const val REPORT_ID = 1
        const val MOUSE_ID = 2

        /** 수정키 8비트 + usage 0x00~0x97 비트맵(동시 입력 제한 없음). KeyState.report() 와 같은 형식. */
        val DESCRIPTOR: ByteArray = intArrayOf(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, REPORT_ID,  //   Report ID
            0x05, 0x07,       //   Usage Page (Keyboard)
            0x19, 0xE0, 0x29, 0xE7,  // Usage Min/Max (modifiers)
            0x15, 0x00, 0x25, 0x01,  // Logical Min/Max
            0x75, 0x01, 0x95, 0x08,  // 1 bit x 8
            0x81, 0x02,       //   Input (Data, Var, Abs)
            0x19, 0x00, 0x29, KeyState.MAX_USAGE,
            0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, KeyState.MAX_USAGE + 1,
            0x81, 0x02,
            0xC0,
            // 마우스: 버튼 3개 + X, Y, 휠 상대 이동
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01,
            0x85, MOUSE_ID,
            0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03,
            0x81, 0x06,
            0xC0, 0xC0,
        ).map { it.toByte() }.toByteArray()
    }
}

/**
 * UDP 로 PC 수신 프로그램(receiver/controlpad_receiver.py)에 키 상태를 보낸다.
 * 키 패킷: 'C' 'P' 버전(1) 순번(4바이트, big-endian) 리포트(20바이트). 수신 측은 'C' 'A' + 순번으로 답한다.
 * 마우스 패킷: 'C' 'M' 버전(1) 순번(4바이트) dx(2바이트) dy(2바이트) 버튼(1바이트) 휠(1바이트), 부호 있는 big-endian.
 */
class UdpLink(val host: String, val port: Int) {
    private val socket = DatagramSocket()
    private var addr: InetAddress? = null
    private var seq = 0
    @Volatile private var lastAck = 0L

    /** 최근 키 패킷이 PC 에 갔다가 응답이 돌아오기까지 걸린 시간(ms). 아직 모르면 -1. */
    @Volatile var rttMs = -1
        private set
    private val sentAt = java.util.concurrent.atomic.AtomicLongArray(64)   // 순번 % 64 → 보낸 시각(ns)
    @Volatile private var closed = false

    val alive: Boolean get() = lastAck != 0L && SystemClock.elapsedRealtime() - lastAck < 1500

    init {
        Thread({
            val buf = ByteArray(64)
            while (!closed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    if (p.length >= 2 && buf[0] == 'C'.code.toByte() && buf[1] == 'A'.code.toByte()) {
                        lastAck = SystemClock.elapsedRealtime()
                        if (p.length >= 6) {
                            val seq = (buf[2].toInt() and 0xFF shl 24) or (buf[3].toInt() and 0xFF shl 16) or
                                (buf[4].toInt() and 0xFF shl 8) or (buf[5].toInt() and 0xFF)
                            val sent = sentAt.get(seq and 63)
                            val ms = ((System.nanoTime() - sent) / 1_000_000).toInt()
                            // 여러 번의 평균에 가깝게 보여 줘서 숫자가 덜 튀게 한다.
                            if (sent != 0L && ms in 0..2000) rttMs = if (rttMs < 0) ms else (rttMs * 3 + ms) / 4
                        }
                    }
                } catch (e: Exception) {
                    if (closed) break
                }
            }
        }, "udp-rx").apply { isDaemon = true }.start()
    }

    /** 워커 스레드에서만 부른다(이름 해석과 전송이 네트워크 작업이라서). */
    fun send(report: ByteArray) {
        if (closed) return
        val a = addr ?: InetAddress.getByName(host).also { addr = it }
        val data = header('P', report.size)
        System.arraycopy(report, 0, data, 7, report.size)
        sentAt.set(seq and 63, System.nanoTime())
        socket.send(DatagramPacket(data, data.size, a, port))
    }

    fun sendMouse(dx: Int, dy: Int, wheel: Int, buttons: Int) {
        if (closed) return
        val a = addr ?: InetAddress.getByName(host).also { addr = it }
        val data = header('M', 6)
        data[7] = (dx shr 8).toByte(); data[8] = dx.toByte()
        data[9] = (dy shr 8).toByte(); data[10] = dy.toByte()
        data[11] = buttons.toByte(); data[12] = wheel.coerceIn(-127, 127).toByte()
        socket.send(DatagramPacket(data, data.size, a, port))
    }

    private fun header(kind: Char, payload: Int): ByteArray {
        val s = ++seq
        val data = ByteArray(7 + payload)
        data[0] = 'C'.code.toByte(); data[1] = kind.code.toByte(); data[2] = 1
        data[3] = (s ushr 24).toByte(); data[4] = (s ushr 16).toByte()
        data[5] = (s ushr 8).toByte(); data[6] = s.toByte()
        return data
    }

    fun close() {
        closed = true
        socket.close()
    }
}

/**
 * 같은 네트워크에서 실행 중인 PC 수신 프로그램을 찾는다.
 * 'C' 'D' 버전(1) 을 브로드캐스트로 보내면, 수신 프로그램이 'C' 'R' 버전(1) + PC 이름으로 답한다.
 */
object Discovery {
    class Pc(val address: String, val name: String)

    val QUERY = byteArrayOf('C'.code.toByte(), 'D'.code.toByte(), 1)

    /** 응답 패킷이면 PC 이름을, 아니면 null 을 돌려준다. */
    fun parseReply(data: ByteArray, length: Int): String? {
        if (length < 3 || data[0] != 'C'.code.toByte() || data[1] != 'R'.code.toByte() || data[2].toInt() != 1) return null
        return String(data, 3, length - 3, Charsets.UTF_8).trim()
    }

    /** 약 1.5초 동안 찾고, 찾은 PC 들을 메인 스레드에서 알려 준다. */
    fun find(port: Int, done: (List<Pc>) -> Unit) {
        Thread {
            val found = LinkedHashMap<String, Pc>()
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 250
                    // 전체 브로드캐스트를 막는 공유기가 있어서, 연결된 네트워크마다의 브로드캐스트 주소로도 보낸다.
                    val targets = LinkedHashSet<InetAddress>()
                    targets += InetAddress.getByName("255.255.255.255")
                    runCatching {
                        for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                            if (!nif.isUp || nif.isLoopback) continue
                            for (ia in nif.interfaceAddresses) ia.broadcast?.let { targets += it }
                        }
                    }
                    val buf = ByteArray(256)
                    val end = SystemClock.elapsedRealtime() + 1500
                    var nextSend = 0L
                    while (SystemClock.elapsedRealtime() < end) {
                        if (SystemClock.elapsedRealtime() >= nextSend) {   // 유실에 대비해 몇 번 다시 묻는다
                            for (t in targets) runCatching { socket.send(DatagramPacket(QUERY, QUERY.size, t, port)) }
                            nextSend = SystemClock.elapsedRealtime() + 400
                        }
                        val p = DatagramPacket(buf, buf.size)
                        try { socket.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                        val name = parseReply(buf, p.length) ?: continue
                        val ip = p.address.hostAddress ?: continue
                        found[ip] = Pc(ip, name)
                    }
                }
            }
            Handler(android.os.Looper.getMainLooper()).post { done(found.values.toList()) }
        }.start()
    }
}
