package com.controlpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** 전체 화면 컨트롤러. 뒤로 가기로 나간다. */
class PlayActivity : BaseActivity() {
    override val avoidKeyboard = false
    override val followsOrientation = true
    private var lastBack = 0L

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 연주 중에 뒤로 가기 동작이 잘못 들어가도 바로 나가지 않게 한다.
        val now = SystemClock.uptimeMillis()
        if (Labs.isOn("exit-guard") && now - lastBack > 2000) {
            lastBack = now
            android.widget.Toast.makeText(this, "한 번 더 누르면 나갑니다", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    private var view: PlayView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var wasConnected = false
    private var everConnected = false
    private var batteryWarned = false
    private val startedAt = SystemClock.elapsedRealtime()
    private var banner: android.widget.TextView? = null
    private var lagWarned = false
    private var wifiWarned = false
    private var buzzed = false
    private var lastShake = 0L
    // 흔들어서 모든 키 떼기(모바일 기능)
    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            if (!Mobile.isShake(e.values[0], e.values[1], e.values[2])) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastShake < 1500) return
            lastShake = now
            view?.releaseAll()
            android.widget.Toast.makeText(this@PlayActivity, "흔들어서 모든 키를 뗐습니다", android.widget.Toast.LENGTH_SHORT).show()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private val tick = object : Runnable {
        override fun run() {
            val connected = Link.connected
            // 끊겼던 연결이 돌아오면 알려 준다(모바일 기능).
            if (!wasConnected && connected && everConnected && Mobile.reconnect) {
                view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                android.widget.Toast.makeText(this@PlayActivity, "PC에 다시 연결되었습니다", android.widget.Toast.LENGTH_SHORT).show()
            }
            if (connected) everConnected = true
            // 연결이 끊기는 순간 눌린 것을 모두 뗀다(모바일 기능).
            if (wasConnected && !connected && Mobile.releaseOnDrop) view?.releaseAll()
            // 처음 연결을 확인하면 짧게 진동(모바일 기능).
            if (connected && !buzzed && Mobile.connectBuzz) {
                buzzed = true
                view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            }
            // 지연이 크거나 Wi-Fi 가 약하면 한 번 알려 준다(모바일 기능).
            if (!lagWarned && Mobile.lagWarn && Link.rttMs > 50) {
                lagWarned = true
                android.widget.Toast.makeText(this@PlayActivity, "왕복 지연이 ${Link.rttMs}ms 입니다. 공유기 가까이 가거나 USB 테더링을 써 보세요", android.widget.Toast.LENGTH_LONG).show()
            }
            if (!wifiWarned && Mobile.weakWifi && Link.mode == Link.MODE_UDP) {
                val rssi = Link.wifiRssi()
                if (rssi in -126..-71) {
                    wifiWarned = true
                    android.widget.Toast.makeText(this@PlayActivity, "Wi-Fi 신호가 약합니다 (${rssi}dBm)", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            // 배터리가 15% 이하로 내려가면 한 번 알려 준다(모바일 기능).
            if (!batteryWarned && Mobile.lowBattery) {
                val bm = getSystemService(android.os.BatteryManager::class.java)
                val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (level in 0..15 && bm?.isCharging != true) {
                    batteryWarned = true
                    android.widget.Toast.makeText(this@PlayActivity, "배터리가 ${level}% 남았습니다", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            // PC 에서 입력을 일시정지했으면 위쪽에 크게 알린다(모바일 기능).
            banner?.visibility = if (Mobile.pausedBanner && Link.pcPaused) View.VISIBLE else View.GONE
            // 쓰는 도중에 PC 연결이 끊기면 진동과 알림으로 알려 준다(기능 · 기타에서 끌 수 있음).
            if (wasConnected && !connected && Link.disconnectAlert) {
                view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                android.widget.Toast.makeText(this@PlayActivity, "PC 연결이 끊겼습니다", android.widget.Toast.LENGTH_SHORT).show()
            }
            wasConnected = connected
            val timer = (if (Mobile.timer) " · ⏱ " + Mobile.elapsed(SystemClock.elapsedRealtime() - startedAt) else "") +
                (if (Mobile.clock) " · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date()) else "")
            view?.setStatus(connected, Link.status() + timer)
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LayoutStore.load(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        if (!Mobile.screenOff) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Mobile.secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Mobile.maxBright) window.attributes = window.attributes.apply { screenBrightness = 1f }
        if (Mobile.lockRotation) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        if (Mobile.powerSave && getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true)
            android.widget.Toast.makeText(this, "절전 모드가 켜져 있어 입력이 늦을 수 있습니다", android.widget.Toast.LENGTH_LONG).show()
        // 화면 주사율이 높을수록 터치도 더 자주 읽는 기기가 많으므로, 지원하는 가장 높은 주사율을 쓴다.
        runCatching {
            @Suppress("DEPRECATION")
            val d = windowManager.defaultDisplay
            val now = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
        show(layout)
    }

    private var current: PadLayout? = null

    private fun show(layout: PadLayout) {
        current = layout
        Link.prefs.edit().putString("lastLayout", layout.id).apply()     // '마지막 레이아웃 바로 실행'(실험실 기능)용
        val frame = android.widget.FrameLayout(this)
        view = PlayView(this, layout).also {
            it.onSwitch = { id -> switchTo(id) }
            it.onOption = { id -> toggleOption(id) }
            frame.addView(it)
        }
        banner = android.widget.TextView(this).apply {
            text = "⏸ PC에서 입력을 일시정지했습니다 — 수신 프로그램에서 '입력 다시 받기' 또는 Ctrl+Alt+P"
            textSize = 16f; setTextColor(0xFF1A1205.toInt()); gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ui.rounded(this@PlayActivity, 0xFFFBBF24.toInt(), 14)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            visibility = View.GONE
            isClickable = false
        }
        frame.addView(banner, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        setContentView(frame)
    }

    /** 옵션 버튼: 설정을 바꾸고, 바뀐 설정으로 실행 화면을 다시 띄운다(눌려 있던 키는 모두 뗀다). */
    private fun toggleOption(id: String) {
        if (id == Options.RELEASE_ALL) {
            view?.releaseAll()
            android.widget.Toast.makeText(this, "모든 키를 뗐습니다", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val on = Options.toggle(id)
        android.widget.Toast.makeText(this, Options.title(id) + if (on) " 켬" else " 끔", android.widget.Toast.LENGTH_SHORT).show()
        view?.releaseAll()
        current?.let { show(it) }
        applyWindowOptions()
    }

    private fun applyWindowOptions() {}

    /** 레이아웃 전환 버튼: 지금 눌린 키를 모두 떼고 다른 레이아웃을 띄운다. */
    private fun switchTo(id: String) {
        val next = LayoutStore.load(this, id)
        if (next == null) {
            android.widget.Toast.makeText(this, "넘어갈 레이아웃을 찾을 수 없습니다 (삭제되었을 수 있음)", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        view?.releaseAll()
        show(next)
    }

    // 볼륨 버튼 막기(모바일 기능): 실행 중 실수로 소리가 바뀌지 않게 한다.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (Mobile.blockVolume && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (Mobile.blockVolume && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        applyWindowOptions()
        Link.setActive(true)
        ui.post(tick)
        if (Mobile.shake) getSystemService(android.hardware.SensorManager::class.java)?.let { sm ->
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(shakeListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing && Labs.isOn("session-summary")) view?.summary()?.let { (n, ms) ->
            android.widget.Toast.makeText(this, "이번 기록: ${n}번 누름 · ${Combo.format(ms)}", android.widget.Toast.LENGTH_LONG).show()
        }
        ui.removeCallbacks(tick)
        getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(shakeListener)
        view?.releaseAll()   // 화면을 떠날 때 PC 에 키가 눌린 채로 남지 않게 한다.
        Link.setActive(false)
    }
}

class PlayView(ctx: Context, private val layout: PadLayout) : View(ctx) {
    private val painter = PadPainter(ctx)
    private val rect = RectF()
    private val state = KeyState()

    init {
        setBackgroundColor(Themes.current.bg)
    }

    private val buttons = layout.buttons
    private val holds = IntArray(buttons.size)          // 버튼별로 올라가 있는 손가락 수
    // 타이머 패널 상태(버튼 인덱스별): 도는 중인지, 멈춰 둔 동안 쌓인 시간, 마지막으로 시작한 시각, 누르기 시작한 시각, 카운트다운이 끝났는지
    private val timerRun = BooleanArray(buttons.size)
    private val timerAcc = LongArray(buttons.size)
    private val timerStart = LongArray(buttons.size)
    private val timerDown = LongArray(buttons.size)
    private val timerDone = BooleanArray(buttons.size)

    private fun timerElapsed(i: Int, now: Long) = timerAcc[i] + if (timerRun[i]) now - timerStart[i] else 0L

    /** 타이머 패널을 뗐을 때: 짧게 누르면 시작 · 멈춤, 길게(0.6초) 누르면 처음으로. */
    private fun timerTap(i: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - timerDown[i] >= TIMER_RESET_MS) {
            timerRun[i] = false; timerAcc[i] = 0; timerDone[i] = false
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } else if (timerRun[i]) {
            timerAcc[i] += now - timerStart[i]; timerRun[i] = false
            if (haptic) performHapticFeedback(hapticKind)
        } else {
            if (timerDone[i]) { timerAcc[i] = 0; timerDone[i] = false }     // 끝난 카운트다운은 처음부터 다시
            timerStart[i] = now; timerRun[i] = true
            if (haptic) performHapticFeedback(hapticKind)
        }
        invalidate()
    }
    private val toggled = BooleanArray(buttons.size)
    private val flash = BooleanArray(buttons.size)      // '한 번 입력' 버튼의 눌림 표시
    private val pointers = HashMap<Int, Int>()          // 포인터 id → 버튼 인덱스
    private val haptic = Link.haptic

    // 노브 상태 (버튼 인덱스별)
    private val knobAngle = FloatArray(buttons.size)    // 화면에 그릴 회전 각도
    private val knobRem = FloatArray(buttons.size)      // 아직 보내지 못한 소수점 이하 이동량 / 키 방식의 누적 각도
    private val knobDir = IntArray(buttons.size)        // 키 방식에서 지금 누르고 있는 방향 (-1, 0, 1)
    private val knobStop = Array(buttons.size) { i -> Runnable { knobKey(i, 0) } }
    private val lastAngle = HashMap<Int, Float>()       // 포인터 id → 직전 각도

    /** 손을 뗀 노브의 표시점을 원점(위쪽)으로 부드럽게 되돌린다. */
    private val knobReturn = object : Runnable {
        override fun run() {
            var moving = false
            for (i in buttons.indices) {
                if (holds[i] > 0 || knobAngle[i] == 0f) continue
                knobAngle[i] = Knob.settle(knobAngle[i])
                if (knobAngle[i] != 0f) moving = true
            }
            invalidate()
            if (moving) taps.postDelayed(this, 16)
        }
    }

    // 트랙패드 상태 (버튼 인덱스별). 올라가 있는 손가락 수는 holds 를 같이 쓴다.
    private val density = ctx.resources.displayMetrics.density
    private val padStart = LongArray(buttons.size)      // 첫 손가락이 닿은 시각
    private val padTravel = FloatArray(buttons.size)    // 이번 터치에서 움직인 거리(px)
    private val padFingers = IntArray(buttons.size)     // 이번 터치에서 동시에 닿았던 최대 손가락 수
    private val padRemX = FloatArray(buttons.size)
    private val padRemY = FloatArray(buttons.size)
    private val padRemWheel = FloatArray(buttons.size)
    private val lastX = HashMap<Int, Float>()           // 포인터 id → 직전 위치
    private val lastY = HashMap<Int, Float>()
    private var connected = false
    private var status = ""
    private val taps = Handler(Looper.getMainLooper())  // '한 번 입력'의 키 떼기 예약
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(12).toFloat() }

    fun setStatus(ok: Boolean, text: String) {
        if (ok == connected && text == status) return
        connected = ok; status = text
        invalidate()
    }

    // '일정 시간 입력' 버튼: 지금 키를 누르고 있는지, 시간이 다 되면 떼는 예약
    private val timedOn = BooleanArray(buttons.size)
    private val timedOff = Array(buttons.size) { i ->
        Runnable {
            if (timedOn[i]) { timedOn[i] = false; state.release(buttons[i].keys); send(); invalidate() }
        }
    }

    /** PC 가 한글 입력 상태라고 보고 있는지. 화면을 열 때는 영문으로 시작한다. */
    private var hangul = false

    // 실험실 기능
    private val labKps = Labs.isOn("kps")
    /** 초당 입력 수를 세야 하는지: 실험실 표시가 켜져 있거나, 초당 입력 수 패널을 넣어 둔 경우. */
    private val countKps = labKps || layout.buttons.any { it.isDisplay && DisplayKind.of(it) == DisplayKind.KPS }
    private val pressTimes = ArrayDeque<Long>()         // 최근에 버튼을 누른 시각들(초당 입력 수 계산용)
    private val labBattery = Labs.isOn("battery")
    private val hapticKind = HapticFeedbackConstants.KEYBOARD_TAP
    private var combo = 0
    private var lastPress = 0L
    private var total = 0
    private val startedAt = SystemClock.uptimeMillis()
    private var batteryText = ""
    private var batteryAt = 0L

    /** 버튼을 한 번 눌렀다고 기록한다(초당 입력 수, 콤보, 총 입력 수). */
    private val labHideStatus = Labs.isOn("hide-status")
    private val labFps = Labs.isOn("play-fps")
    private val labAutoHide = Labs.isOn("status-autohide")
    private val labTimerFlash = Labs.isOn("timer-flash")
    private val labKpsColor = Labs.isOn("kps-color")
    private val frameTimes = ArrayDeque<Long>()          // 화면 FPS 표시(실험실 기능): 최근 1초의 그린 시각
    private var timerEndedAt = 0L                        // 마지막으로 카운트다운이 끝난 시각(화면 번쩍용)
    private val flashPaint = Paint()
    private val labSound = Labs.isOn("press-sound")
    private val labHistory = Labs.isOn("key-history")
    private val labDim = Labs.isOn("auto-dim")
    private var lastTouch = SystemClock.uptimeMillis()
    private val dimPaint = Paint().apply { color = 0xB0000000.toInt() }

    /** 실험실 표시들: 터치 위치, 누른 시간, 쉬는 동안 어둡게. */
    private fun drawLabOverlays(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        // 타이머 끝나면 화면 번쩍(실험실 기능): 1.5초 동안 세 번 깜빡인다.
        if (labTimerFlash && timerEndedAt != 0L && now - timerEndedAt < 1500) {
            if ((now - timerEndedAt) / 250 % 2 == 0L) {
                flashPaint.color = Ui.accent() and 0x00FFFFFF or 0x66000000
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
            }
            postInvalidateDelayed(60)
        }
        if (labDim) {
            val idle = now - lastTouch
            if (idle >= DIM_AFTER_MS) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            else postInvalidateDelayed(DIM_AFTER_MS - idle + 50)
        }
    }

    private fun trackTouches(e: MotionEvent) {
        if (labDim) lastTouch = SystemClock.uptimeMillis()
    }
    /** 최근 입력 기록을 남겨야 하는지: 실험실 표시가 켜져 있거나, 최근 입력 기록 패널을 넣어 둔 경우. */
    private val recordHistory = labHistory || layout.buttons.any { it.isDisplay && DisplayKind.of(it) == DisplayKind.HISTORY }
    private val history = ArrayDeque<String>()          // 최근에 누른 버튼의 키들
    init {
        if (labSound) ClickSound.prepare(context)
    }

    private val labBottom = Labs.isOn("status-bottom")

    // 실험실 기능들(실행 화면)
    private val labCount = Labs.isOn("press-count")
    private val labReleaseHaptic = Labs.isOn("release-haptic")
    private val labReleaseSound = Labs.isOn("release-sound")
    private val labDimButtons = Labs.isOn("dim-buttons")
    private val labSeconds = Labs.isOn("clock-seconds")
    private val pressCount = IntArray(layout.buttons.size)
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; textSize = ctx.dp(11).toFloat() }
    private val dimLayer = Paint().apply { alpha = 110 }

    /** 나갈 때 기록 요약(실험실 기능)에 쓰는 값: 누른 횟수와 실행한 시간(ms). */
    fun summary(): Pair<Int, Long> = total to (SystemClock.uptimeMillis() - startedAt)

    init {
        if (labReleaseSound) ClickSound.prepare(ctx)
    }

    private fun notePress(b: PadButton) {
        val now = SystemClock.uptimeMillis()
        buttons.indexOf(b).takeIf { it >= 0 }?.let { pressCount[it]++ }
        if (countKps) pressTimes.addLast(now)
        combo = Combo.next(combo, lastPress, now)
        lastPress = now
        total++
        if (labSound) ClickSound.play()
        if (recordHistory) {
            Combo.remember(history, if (b.mode == PadButton.MODE_MACRO) "매크로" else Keys.combo(b.keys))
        }
    }

    /** 배터리 잔량과 시각. 자주 물어볼 필요가 없어 30초에 한 번만 새로 읽는다. */
    private fun battery(): String {
        val now = SystemClock.uptimeMillis()
        if (batteryText.isEmpty() || now - batteryAt > 30000) {
            batteryAt = now
            val level = runCatching {
                (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrDefault(-1)
            batteryText = if (level in 0..100) "배터리 $level% · " else ""
        }
        // 시각은 매번 새로 읽는다(초 표시가 켜져 있으면 초까지).
        val clock = java.text.SimpleDateFormat(if (labSeconds) "HH:mm:ss" else "HH:mm", java.util.Locale.US).format(java.util.Date())
        return batteryText + clock
    }

    /** 최근 1초 동안 누른 횟수. */
    private fun kps(): Int {
        val now = SystemClock.uptimeMillis()
        while (pressTimes.isNotEmpty() && now - pressTimes.first() > 1000) pressTimes.removeFirst()
        return pressTimes.size
    }

    /** 옵션 버튼을 눌렀을 때 불린다(바꿀 옵션의 이름). */
    var onOption: (String) -> Unit = {}

    /** 레이아웃 전환 버튼을 눌렀을 때 불린다(넘어갈 레이아웃의 id). */
    var onSwitch: (String) -> Unit = {}

    // 매크로·연타 버튼 상태 (버튼 인덱스별). 예약은 버튼마다 따로 취소할 수 있게 tokens 로 구분한다.
    private val tokens = Array(buttons.size) { Any() }
    private val macroOn = BooleanArray(buttons.size)
    private val busyKeys = arrayOfNulls<List<Int>>(buttons.size)   // 매크로·연타가 지금 누르고 있는 키
    private val turboOn = BooleanArray(buttons.size)

    private fun later(i: Int, delay: Long, action: () -> Unit) =
        taps.postAtTime(action, tokens[i], SystemClock.uptimeMillis() + delay)

    private fun hold(i: Int, keys: List<Int>?) {
        busyKeys[i]?.let { state.release(it) }
        keys?.let { state.press(it) }
        busyKeys[i] = keys
        send()
    }

    /** 매크로의 step 번째 조합을 잠깐 눌렀다 떼고 다음으로 넘어간다. */
    private fun runMacro(i: Int, step: Int) {
        val steps = buttons[i].macro
        if (step >= steps.size) { macroOn[i] = false; flash[i] = false; invalidate(); return }
        macroOn[i] = true; flash[i] = true
        hold(i, steps[step])
        later(i, TAP_MS) {
            hold(i, null)
            later(i, MACRO_GAP_MS) { runMacro(i, step + 1) }
        }
    }

    /** 연타: 누르고 있는 동안 키를 눌렀다 뗐다 한다. */
    private fun runTurbo(i: Int) {
        if (!turboOn[i]) return
        val (down, upTime) = Turbo.timing(buttons[i].rate)
        hold(i, buttons[i].keys)
        later(i, down) {
            hold(i, null)
            later(i, upTime) { runTurbo(i) }
        }
    }

    /** 진행 중인 매크로·연타를 멈추고 누르고 있던 키를 뗀다. */
    private fun stopBusy(i: Int) {
        taps.removeCallbacksAndMessages(tokens[i])
        macroOn[i] = false; turboOn[i] = false
        if (busyKeys[i] != null) hold(i, null)
    }

    // 그룹 표시 상태. 0번은 항상 보이는 버튼들이다.
    private val groupOn = BooleanArray(PadButton.GROUPS + 1)     // 표시 버튼으로 켜 둔 그룹
    private val groupHolds = IntArray(PadButton.GROUPS + 1)      // '누르는 동안만' 표시 버튼을 누르고 있는 손가락 수

    private fun groupVisible(g: Int) = g == 0 || groupOn[g] || groupHolds[g] > 0

    /** 그룹이 사라질 때, 그 그룹 버튼에 걸려 있던 입력을 모두 푼다. */
    private fun hideGroup(g: Int) {
        for ((id, i) in pointers.entries.toList()) {
            if (i < 0 || buttons[i].group != g) continue
            if (buttons[i].isMouse) mouseUp(id) else up(i)
            pointers[id] = -1
            lastAngle.remove(id)
        }
        for (i in buttons.indices) {
            val b = buttons[i]
            if (b.group != g) continue
            holds[i] = 0
            if (b.isStick && stickMask[i] != 0) { state.release(Stick.keysFor(stickMask[i], b.keys)); stickMask[i] = 0 }
            if (b.isStick) { stickX[i] = 0f; stickY[i] = 0f }
            if (macroOn[i] || turboOn[i]) { stopBusy(i); flash[i] = false }
            if (toggled[i]) { toggled[i] = false; state.release(b.keys) }
            if (timedOn[i]) { taps.removeCallbacks(timedOff[i]); timedOn[i] = false; state.release(b.keys) }
            // 사라진 그룹 안의 표시 버튼이 켜 둔 그룹도 함께 닫는다.
            if (b.isShow && b.target != g && groupOn[b.target]) {
                groupOn[b.target] = false
                if (!groupVisible(b.target)) hideGroup(b.target)
            }
        }
        send()
    }

    private fun hit(x: Float, y: Float): Int {
        if (width == 0 || height == 0) return -1
        val nx = x / width
        val ny = y / height
        for (i in buttons.indices.reversed()) {
            // 표시 패널과 사진은 누를 수 없으므로 건너뛴다(밑에 있는 버튼이 눌린다).
            // 타이머 패널은 누를 수 있다(시작 · 멈춤 · 처음으로).
            val pressable = !buttons[i].isDisplay || DisplayKind.interactive(DisplayKind.of(buttons[i]))
            if (pressable && !buttons[i].isImage && groupVisible(buttons[i].group) && buttons[i].contains(nx, ny)) return i
        }
        return -1
    }

    // 조이스틱 상태 (버튼 인덱스별): 지금 눌린 방향과 손잡이 위치(-1~1)
    private val stickMask = IntArray(buttons.size)
    private val stickX = FloatArray(buttons.size)
    private val stickY = FloatArray(buttons.size)

    /** 조이스틱 위의 손가락 위치에 맞춰 방향 키를 누르거나 뗀다. */
    private fun stickMove(i: Int, x: Float, y: Float) {
        val b = buttons[i]
        var dx = (x - (b.x + b.w / 2) * width) / (b.w * width / 2)
        var dy = (y - (b.y + b.h / 2) * height) / (b.h * height / 2)
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len > 1f) { dx /= len; dy /= len }       // 손가락이 밖으로 나가도 손잡이는 가장자리까지만
        stickX[i] = dx; stickY[i] = dy
        stickSet(i, Stick.directions(dx, dy, b.mode != PadButton.STICK_4WAY))
    }

    private fun stickSet(i: Int, mask: Int) {
        val old = stickMask[i]
        if (old == mask) return
        val keys = buttons[i].keys
        // 새 방향 키를 먼저 누르고 그다음에 뗀다: 방향을 바꾸는 순간 아무 키도 안 눌린 틈이 생기지 않게.
        state.press(Stick.keysFor(mask and old.inv(), keys))
        state.release(Stick.keysFor(old and mask.inv(), keys))
        stickMask[i] = mask
        send()
        if (haptic && mask != 0) performHapticFeedback(hapticKind)
    }

    // 가상 마우스: 포인터 id → 잡고 있는 부분(MousePart), 휠 부분에서 움직인 거리
    private val mouseParts = HashMap<Int, Int>()
    private val wheelTravel = HashMap<Int, Float>()

    private fun mouseDown(id: Int, i: Int, x: Float, y: Float) {
        val b = buttons[i]
        val part = MousePart.of((x / width - b.x) / b.w, (y / height - b.y) / b.h)
        mouseParts[id] = part
        lastX[id] = x; lastY[id] = y
        when (part) {
            MousePart.LEFT -> { state.press(listOf(Keys.MOUSE_LEFT)); send() }
            MousePart.RIGHT -> { state.press(listOf(Keys.MOUSE_RIGHT)); send() }
            MousePart.WHEEL -> wheelTravel[id] = 0f
        }
        if (haptic && part != MousePart.BODY) performHapticFeedback(hapticKind)
    }

    private fun mouseMove(id: Int, i: Int, x: Float, y: Float) {
        val dx = x - (lastX[id] ?: x)
        val dy = y - (lastY[id] ?: y)
        lastX[id] = x; lastY[id] = y
        when (mouseParts[id]) {
            MousePart.BODY -> {
                val k = buttons[i].sens * PAD_GAIN / density
                padRemX[i] += dx * k; padRemY[i] += dy * k
                val mx = padRemX[i].toInt(); val my = padRemY[i].toInt()
                if (mx != 0 || my != 0) { padRemX[i] -= mx; padRemY[i] -= my; Link.sendMouse(mx, my) }
            }
            MousePart.WHEEL -> {
                wheelTravel[id] = (wheelTravel[id] ?: 0f) + kotlin.math.abs(dy)
                padRemWheel[i] += dy / (SCROLL_STEP_DP * density)
                val w = padRemWheel[i].toInt()
                if (w != 0) { padRemWheel[i] -= w; Link.sendMouse(0, 0, w) }
            }
        }
    }

    private fun mouseUp(id: Int) {
        when (mouseParts.remove(id)) {
            MousePart.LEFT -> { state.release(listOf(Keys.MOUSE_LEFT)); send() }
            MousePart.RIGHT -> { state.release(listOf(Keys.MOUSE_RIGHT)); send() }
            // 휠을 굴리지 않고 눌렀다 떼면 휠클릭
            MousePart.WHEEL -> if ((wheelTravel.remove(id) ?: 0f) < TAP_SLOP_DP * density) click(Keys.MOUSE_MIDDLE)
        }
        lastX.remove(id); lastY.remove(id)
    }

    // 지금 눌리고 있는 키 표시
    private val showPressed = Link.showPressed
    /** 레이아웃에 키 입력 표시 패널이 있으면 그 패널에 보여 주고, 화면 위쪽의 기본 표시는 쓰지 않는다. */
    private val hasPanel = buttons.any { it.isDisplay && DisplayKind.of(it) == DisplayKind.KEYS }
    private var pressedText = ""
    private var pressedAt = 0L          // 마지막으로 키가 눌려 있던 시각
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = ctx.dp(22).toFloat(); isFakeBoldText = true
    }
    private val pressedBack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val theme = Themes.current

    private fun send() {
        Link.send(state.report())
        Link.setMouseButtons(state.mouseButtons())
        if (showPressed || hasPanel) {
            val now = state.pressed()
            if (now.isNotEmpty()) {
                pressedText = Keys.combo(now)
                pressedAt = Long.MAX_VALUE
            } else if (pressedAt == Long.MAX_VALUE) {
                // 짧게 누른 키도 읽을 수 있게 뗀 뒤에도 잠깐 남겨 둔다.
                pressedAt = SystemClock.uptimeMillis()
                postInvalidateDelayed(PRESSED_LINGER_MS + 20)
            }
            invalidate()
        }
    }

    private fun click(button: Int) {
        val keys = listOf(button)
        state.press(keys); send()
        taps.postDelayed({ state.release(keys); send() }, TAP_MS)
    }

    /** 트랙패드 위에서 손가락 하나가 (dx, dy) 만큼 움직였다. */
    private fun padMove(i: Int, dx: Float, dy: Float) {
        val b = buttons[i]
        padTravel[i] += kotlin.math.abs(dx) + kotlin.math.abs(dy)
        if (holds[i] >= 2) {
            // 두 손가락: 스크롤. 손가락마다 이벤트가 오므로 손가락 수로 나눈다.
            padRemWheel[i] += dy / (SCROLL_STEP_DP * density) / holds[i]
            val w = padRemWheel[i].toInt()
            if (w != 0) { padRemWheel[i] -= w; Link.sendMouse(0, 0, w) }
        } else {
            val k = b.sens * PAD_GAIN / density
            padRemX[i] += dx * k; padRemY[i] += dy * k
            val mx = padRemX[i].toInt(); val my = padRemY[i].toInt()
            if (mx != 0 || my != 0) { padRemX[i] -= mx; padRemY[i] -= my; Link.sendMouse(mx, my) }
        }
    }

    private fun pointerAngle(i: Int, x: Float, y: Float): Float {
        val b = buttons[i]
        return Knob.angle(x - (b.x + b.w / 2) * width, y - (b.y + b.h / 2) * height)
    }

    /** 키 방식 노브: 돌리는 방향의 키를 누르고 있다가, 멈추면 뗀다. */
    private fun knobKey(i: Int, dir: Int) {
        if (knobDir[i] == dir) return
        val b = buttons[i]
        if (knobDir[i] != 0) state.release(if (knobDir[i] < 0) b.keys else b.keys2)
        if (dir != 0) state.press(if (dir < 0) b.keys else b.keys2)
        knobDir[i] = dir
        send()
        invalidate()
    }

    private fun turn(i: Int, degrees: Float) {
        val b = buttons[i]
        knobAngle[i] += degrees
        val d = if (b.invert) -degrees else degrees
        if (b.knobOut == PadButton.OUT_KEYS) {
            var keep = false
            if (Knob.sameWay(knobDir[i], d)) {
                // 이미 그 방향 키를 누르고 있으면, 조금이라도 더 돌아갈 때마다 계속 누른 채로 둔다.
                // (일정 각도를 채울 때만 연장하면 천천히 돌릴 때 키가 떼졌다 눌렸다 한다.)
                knobRem[i] = 0f
                keep = true
            } else {
                if (knobRem[i] * d < 0) knobRem[i] = 0f       // 방향이 바뀌면 누적을 버린다
                knobRem[i] += d
                if (kotlin.math.abs(knobRem[i]) >= KEY_STEP_DEG) {
                    knobKey(i, if (knobRem[i] > 0) 1 else -1)
                    knobRem[i] = 0f
                    keep = true
                }
            }
            if (keep) {
                taps.removeCallbacks(knobStop[i])
                // '계속 누름'이면 멈춰도 키를 유지하고, 손을 떼거나 반대로 돌릴 때만 바뀐다.
                if (b.mode != PadButton.KNOB_UNTIL_RELEASE) taps.postDelayed(knobStop[i], KEY_HOLD_MS)
            }
        } else {
            knobRem[i] += d * b.sens
            val n = knobRem[i].toInt()
            if (n != 0) {
                knobRem[i] -= n
                if (b.knobOut == PadButton.OUT_MOUSE_X) Link.sendMouse(n, 0) else Link.sendMouse(0, n)
            }
        }
    }

    private fun down(i: Int) {
        if (i < 0) return
        val n = ++holds[i]
        val b = buttons[i]
        if (b.isDisplay) { if (n == 1) timerDown[i] = SystemClock.uptimeMillis(); return }   // 타이머 패널
        if (b.isPad) {
            if (n == 1) { padStart[i] = SystemClock.uptimeMillis(); padTravel[i] = 0f; padFingers[i] = 1 }
            else padFingers[i] = maxOf(padFingers[i], n)
            return
        }
        if (b.isStick) return                       // 방향은 손가락 위치로 정한다(stickMove)
        if (n != 1) return
        if (b.isKnob) return
        if (b.isShow) {
            if (haptic) performHapticFeedback(hapticKind)
            val g = b.target
            if (b.mode == PadButton.MODE_HOLD) groupHolds[g]++
            else {
                groupOn[g] = !groupOn[g]
                if (!groupVisible(g)) hideGroup(g)
            }
            return
        }
        if (b.isSwitch) {
            if (haptic) performHapticFeedback(hapticKind)
            onSwitch(b.link)
            return
        }
        if (b.isOption) {
            if (haptic) performHapticFeedback(hapticKind)
            post { onOption(b.link) }       // 터치 처리가 끝난 뒤에 화면을 바꾼다
            return
        }
        notePress(b)
        // 한/영을 누르면 글자 키의 표시도 함께 바꾼다(키를 R Alt 로 바꿔 둔 한/영 버튼 포함).
        if (Keys.HANGUL in b.keys || b.label == "한/영") hangul = !hangul
        when (b.mode) {
            PadButton.MODE_TAP -> {
                state.press(b.keys); send(); flash[i] = true
                taps.postDelayed({ state.release(b.keys); send(); flash[i] = false; invalidate() }, TAP_MS)
            }
            PadButton.MODE_TIMED -> {
                // 입력 중에 다시 누르면 시간을 처음부터 다시 센다.
                taps.removeCallbacks(timedOff[i])
                if (!timedOn[i]) { timedOn[i] = true; state.press(b.keys); send() }
                taps.postDelayed(timedOff[i], b.holdMs.toLong())
            }
            PadButton.MODE_TOGGLE -> {
                toggled[i] = !toggled[i]
                if (toggled[i]) state.press(b.keys) else state.release(b.keys)
                send()
            }
            PadButton.MODE_MACRO -> if (!macroOn[i]) runMacro(i, 0)     // 진행 중에 또 누르면 무시
            PadButton.MODE_TURBO -> { turboOn[i] = true; runTurbo(i) }
            else -> { state.press(b.keys); send() }
        }
        // 진동은 시스템 호출이라 시간이 걸릴 수 있으므로, 입력을 먼저 보낸 뒤에 울린다.
        if (haptic) performHapticFeedback(hapticKind)
    }

    private fun up(i: Int) {
        if (i < 0 || holds[i] == 0) return
        val n = --holds[i]
        val b = buttons[i]
        if (b.isDisplay) { if (n == 0) timerTap(i); return }
        if (b.isPad) {
            // 거의 움직이지 않고 짧게 뗐으면 탭: 한 손가락은 좌클릭, 두 손가락은 우클릭
            if (n == 0 && b.mode == PadButton.PAD_TAP && padTravel[i] < TAP_SLOP_DP * density * padFingers[i] &&
                SystemClock.uptimeMillis() - padStart[i] < TAP_TIMEOUT_MS
            ) click(if (padFingers[i] >= 2) Keys.MOUSE_RIGHT else Keys.MOUSE_LEFT)
            return
        }
        if (b.isStick) {
            if (n == 0) { stickSet(i, 0); stickX[i] = 0f; stickY[i] = 0f }
            return
        }
        if (n != 0) return
        if (b.type == PadButton.TYPE_BUTTON) {
            if (labReleaseHaptic && haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (labReleaseSound) ClickSound.play()
        }
        if (b.isShow) {
            val g = b.target
            if (b.mode == PadButton.MODE_HOLD && groupHolds[g] > 0 && --groupHolds[g] == 0 && !groupVisible(g)) hideGroup(g)
            return
        }
        if (b.isKnob) {
            taps.removeCallbacks(knobStop[i])
            knobKey(i, 0)
            knobRem[i] = 0f
            knobAngle[i] = Knob.delta(0f, knobAngle[i])   // 여러 바퀴 돌렸어도 가까운 쪽으로 돌아가게
            taps.removeCallbacks(knobReturn)
            taps.post(knobReturn)
        } else if (b.type == PadButton.TYPE_BUTTON && b.mode == PadButton.MODE_TURBO) { stopBusy(i); invalidate() }
        else if (b.mode == PadButton.MODE_HOLD) { state.release(b.keys); send() }
    }

    fun releaseAll() {
        pointers.clear(); lastAngle.clear(); lastX.clear(); lastY.clear()
        mouseParts.clear(); wheelTravel.clear()
        knobDir.fill(0); knobRem.fill(0f); knobAngle.fill(0f)
        groupOn.fill(false); groupHolds.fill(0); timedOn.fill(false)
        stickMask.fill(0); stickX.fill(0f); stickY.fill(0f)
        macroOn.fill(false); turboOn.fill(false); busyKeys.fill(null)
        holds.fill(0); toggled.fill(false); flash.fill(false)
        taps.removeCallbacksAndMessages(null)
        state.clear(); send()
        invalidate()
    }

    // 터치 위치 보정(설정 > 오프셋): 손가락이 닿는 자리와 눌렀다고 느끼는 자리의 차이를 맞춘다.
    private val offsetX = ctx.dp(Link.touchOffsetX).toFloat()
    private val offsetY = ctx.dp(Link.touchOffsetY).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (offsetX != 0f || offsetY != 0f) e.offsetLocation(offsetX, offsetY)
        trackTouches(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 움직임을 화면 갱신 주기에 묶지 말고, 터치 패널이 읽는 대로 바로 받는다(노브·트랙패드 지연 감소).
                if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)
                val idx = e.actionIndex
                val b = hit(e.getX(idx), e.getY(idx))
                pointers[e.getPointerId(idx)] = b
                if (b >= 0 && buttons[b].isMouse) {
                    mouseDown(e.getPointerId(idx), b, e.getX(idx), e.getY(idx))
                    invalidate()
                    return true
                }
                if (b >= 0 && buttons[b].isKnob) lastAngle[e.getPointerId(idx)] = pointerAngle(b, e.getX(idx), e.getY(idx))
                if (b >= 0 && buttons[b].isStick) { down(b); stickMove(b, e.getX(idx), e.getY(idx)); invalidate(); return true }
                if (b >= 0 && buttons[b].isPad) { lastX[e.getPointerId(idx)] = e.getX(idx); lastY[e.getPointerId(idx)] = e.getY(idx) }
                down(b)
            }
            MotionEvent.ACTION_MOVE -> for (idx in 0 until e.pointerCount) {
                val id = e.getPointerId(idx)
                val before = pointers[id] ?: -1
                if (before >= 0 && buttons[before].isStick) {
                    // 조이스틱을 잡은 손가락은 밖으로 나가도 계속 그 조이스틱을 움직인다.
                    stickMove(before, e.getX(idx), e.getY(idx))
                } else if (before >= 0 && buttons[before].isMouse) {
                    mouseMove(id, before, e.getX(idx), e.getY(idx))
                } else if (before >= 0 && buttons[before].isPad) {
                    val x = e.getX(idx); val y = e.getY(idx)
                    val dx = x - (lastX[id] ?: x); val dy = y - (lastY[id] ?: y)
                    lastX[id] = x; lastY[id] = y
                    if (dx != 0f || dy != 0f) padMove(before, dx, dy)
                } else if (before >= 0 && buttons[before].isKnob) {
                    // 노브를 잡은 손가락은 노브 밖으로 나가도 계속 그 노브를 돌린다.
                    val b = buttons[before]
                    val dx = e.getX(idx) - (b.x + b.w / 2) * width
                    val dy = e.getY(idx) - (b.y + b.h / 2) * height
                    // 중심에 너무 가까우면 각도가 튀므로 무시한다.
                    if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE * b.w * width * b.h * height) {
                        lastAngle.remove(id)   // 중심을 가로질러 반대편으로 나와도 크게 튀지 않게
                        continue
                    }
                    val a = Knob.angle(dx, dy)
                    val d = Knob.delta(lastAngle[id] ?: a, a)
                    lastAngle[id] = a
                    if (d != 0f) turn(before, d)
                } else if (layout.slide) {
                    var now = hit(e.getX(idx), e.getY(idx))
                    if (now >= 0 && (buttons[now].isKnob || buttons[now].isPad || buttons[now].isMouse || buttons[now].isStick)) now = -1   // 미끄러져서 노브·트랙패드를 잡지는 않는다
                    if (now != before) { up(before); pointers[id] = now; down(now) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                lastAngle.remove(id)
                val b = pointers.remove(id) ?: -1
                if (b >= 0 && buttons[b].isMouse) mouseUp(id)
                else { lastX.remove(id); lastY.remove(id); up(b) }
            }
            MotionEvent.ACTION_CANCEL -> {
                for ((id, b) in pointers.entries.toList()) if (b >= 0 && buttons[b].isMouse) mouseUp(id) else up(b)
                pointers.clear(); lastAngle.clear(); lastX.clear(); lastY.clear()
            }
        }
        invalidate()
        return true
    }

    // 눌리지 않은 상태의 버튼 전체를 미리 그려 둔 그림. 터치마다 수십 개의 버튼을 다시 그리면
    // 화면 그리기가 밀려 다음 터치 처리까지 늦어지므로, 평소에는 이 그림 한 장과 눌린 버튼만 그린다.
    private var idle: android.graphics.Bitmap? = null
    private var idleKey = -1

    /** 보이는 그룹과 한/영 상태가 같으면 미리 그린 그림을 다시 쓸 수 있다. */
    private fun idleStateKey(): Int {
        var k = if (hangul) 1 else 0
        for (g in 1..PadButton.GROUPS) if (groupVisible(g)) k = k or (1 shl g)
        return k
    }

    private fun idleLayer(): android.graphics.Bitmap {
        var bmp = idle
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            idle = bmp
            idleKey = -1
        }
        val key = idleStateKey()
        if (idleKey != key) {
            idleKey = key
            bmp.eraseColor(theme.bg)
            val c = Canvas(bmp)
            for (b in buttons) {
                if (!groupVisible(b.group)) continue
                rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
                painter.draw(c, b, rect, false, false, 0f, 0, if (hangul) 1 else 0)
            }
        }
        return bmp
    }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        c.drawBitmap(idleLayer(), 0f, 0f, if (labDimButtons) dimLayer else null)
        for (i in buttons.indices) {
            val b = buttons[i]
            if (!groupVisible(b.group)) continue
            val lit = if (b.isOption) Options.isOn(b.link)
            else if (b.isShow) groupVisible(b.target)
            else if (b.isKnob) holds[i] > 0
            else flash[i] || toggled[i] || timedOn[i] ||
                (holds[i] > 0 && (b.mode == PadButton.MODE_HOLD || b.mode == PadButton.MODE_TURBO))
            var parts = 0
            if (b.isMouse) for ((id, part) in mouseParts) if (pointers[id] == i) parts = parts or (1 shl part)
            if (b.isDisplay && DisplayKind.of(b) != DisplayKind.KEYS) {
                // 숫자 패널: 항상 보이고, 숫자가 바뀌는 동안 다시 그린다.
                val kind = DisplayKind.of(b)
                val now = SystemClock.uptimeMillis()
                if (now - lastPress > Combo.WINDOW_MS) combo = 0
                val n = if (kind == DisplayKind.KPS) kps() else 0
                rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
                val shown = when (kind) {
                    DisplayKind.STOPWATCH -> {
                        val e = timerElapsed(i, now)
                        if (timerRun[i]) postInvalidateDelayed(100)
                        (if (!timerRun[i] && e == 0L) "▶ " else "") + Timers.stopwatch(e)
                    }
                    DisplayKind.COUNTDOWN -> {
                        val total = Timers.durationMs(b)
                        val left = total - timerElapsed(i, now)
                        if (timerRun[i] && left <= 0) {
                            // 끝: 멈추고 길게 진동, 알림
                            timerRun[i] = false; timerAcc[i] = total; timerDone[i] = true
                            timerEndedAt = now
                            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                            android.widget.Toast.makeText(context, "타이머가 끝났습니다", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        if (timerRun[i]) postInvalidateDelayed(200)
                        when {
                            timerDone[i] -> "끝!"
                            !timerRun[i] && timerAcc[i] == 0L -> "▶ " + Timers.countdown(total)
                            else -> Timers.countdown(left)
                        }
                    }
                    DisplayKind.BATTERY -> battery()
                    DisplayKind.HISTORY -> history.joinToString("  ›  ").ifEmpty { "—" }
                    DisplayKind.STATUS -> (if (connected) "● " else "○ ") + status
                    else -> DisplayKind.value(kind, n, combo, total, now - startedAt)
                }
                val pausedTimer = DisplayKind.interactive(kind) && !timerRun[i] && timerElapsed(i, now) > 0 && !timerDone[i]
                // 초당 입력 수 색 바꾸기(실험실 기능): 빠를수록 초록 → 노랑 → 빨강
                val savedText = b.textColor
                if (labKpsColor && kind == DisplayKind.KPS) b.textColor = kpsColor(n, savedText)
                painter.drawDisplay(c, b, rect, holds[i] > 0, shown, pausedTimer)
                b.textColor = savedText
                when (kind) {
                    DisplayKind.KPS -> if (n > 0) postInvalidateDelayed(200)
                    DisplayKind.COMBO -> if (combo > 0) postInvalidateDelayed(250)
                    DisplayKind.TIMER, DisplayKind.BATTERY -> postInvalidateDelayed(1000)
                }
                continue
            }
            if (b.isDisplay) {
                val held = pressedAt == Long.MAX_VALUE
                if (pressedText.isEmpty() || !(held || SystemClock.uptimeMillis() - pressedAt < PRESSED_LINGER_MS)) continue
                rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
                painter.drawDisplay(c, b, rect, false, pressedText, !held)
                continue
            }
            if (b.isStick) {
                if (holds[i] == 0) continue
                rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
                painter.drawStick(c, b, rect, false, stickX[i], stickY[i], stickMask[i])
                continue
            }
            if (!lit && parts == 0 && knobAngle[i] == 0f) continue    // 미리 그린 모습 그대로
            rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
            painter.draw(c, b, rect, lit, false, knobAngle[i], parts, if (hangul) 1 else 0)
        }
        if (labCount) {
            countPaint.color = theme.text and 0x00FFFFFF or 0xAA000000.toInt()
            for (i in buttons.indices) {
                val b = buttons[i]
                if (pressCount[i] == 0 || !groupVisible(b.group)) continue
                c.drawText(pressCount[i].toString(), (b.x + b.w) * width - countPaint.textSize * 0.4f, b.y * height + countPaint.textSize * 1.2f, countPaint)
            }
        }
        if (showPressed && !hasPanel && pressedText.isNotEmpty()) {
            val held = pressedAt == Long.MAX_VALUE
            if (held || SystemClock.uptimeMillis() - pressedAt < PRESSED_LINGER_MS) {
                val w = pressedPaint.measureText(pressedText)
                val h = pressedPaint.textSize
                val cx = width / 2f
                pressedBack.color = theme.bg and 0x00FFFFFF or 0xCC000000.toInt()
                c.drawRoundRect(cx - w / 2 - h * 0.6f, h * 0.35f, cx + w / 2 + h * 0.6f, h * 2.05f, h * 0.5f, h * 0.5f, pressedBack)
                pressedPaint.color = if (held) theme.text else theme.text and 0x00FFFFFF or 0x77000000
                c.drawText(pressedText, cx, h * 1.55f, pressedPaint)
            }
        }
        statusPaint.color = if (connected) 0x9981C784.toInt() else 0xFFFFB74D.toInt()
        // 왼쪽 위 한 줄: 연결 상태와, 켜 둔 실험실 표시들
        val parts = ArrayList<String>()
        // 상태 줄 자동 숨김(실험실 기능): 열고 5초 뒤부터는 연결돼 있는 동안 감춘다.
        val autoHidden = labAutoHide && connected && SystemClock.uptimeMillis() - startedAt > 5000
        if (labAutoHide && connected && !autoHidden) postInvalidateDelayed(5100 - (SystemClock.uptimeMillis() - startedAt))
        if (!labHideStatus && !autoHidden) parts += (if (connected) "● " else "○ ") + status
        if (labFps) {
            val t = SystemClock.uptimeMillis()
            frameTimes.addLast(t)
            while (frameTimes.isNotEmpty() && t - frameTimes.first() > 1000) frameTimes.removeFirst()
            parts += "${frameTimes.size} fps"
            postInvalidateDelayed(1000)      // 아무것도 안 해도 1초마다 숫자를 새로 그린다
        }
        if (labKps) {
            val n = kps()
            parts += "${n}회/초"
            if (n > 0) postInvalidateDelayed(200)       // 손을 떼도 숫자가 0 으로 내려가게
        }
        if (labBattery) { parts += battery(); postInvalidateDelayed(1000) }
        // 상태 표시를 아래로(실험실 기능)면 왼쪽 아래에, 아니면 왼쪽 위에 쓴다.
        val line1 = if (labBottom) height - statusPaint.textSize * (if (labHistory && history.isNotEmpty()) 2.0f else 0.8f) else statusPaint.textSize * 1.6f
        val line2 = if (labBottom) height - statusPaint.textSize * 0.8f else statusPaint.textSize * 3.1f
        if (parts.isNotEmpty()) c.drawText(parts.joinToString(" · "), statusPaint.textSize, line1, statusPaint)
        if (labHistory && history.isNotEmpty()) {
            c.drawText(history.joinToString("  ›  "), statusPaint.textSize, line2, statusPaint)
        }
        drawLabOverlays(c)
    }

    /** 초당 입력 수에 따른 글자색(실험실 기능 '초당 입력 수 색 바꾸기'). 느릴 때는 원래 색. */
    private fun kpsColor(n: Int, normal: Int): Int = when {
        n >= 15 -> 0xFFEF5350.toInt()
        n >= 10 -> 0xFFFFCA28.toInt()
        n >= 5 -> 0xFF66BB6A.toInt()
        else -> normal
    }

    companion object {
        const val TAP_MS = 40L
        const val DIM_AFTER_MS = 30000L      // 이만큼 아무것도 누르지 않으면 어둡게(실험실 기능)
        const val MACRO_GAP_MS = 40L         // 매크로: 한 조합을 뗀 뒤 다음 조합을 누르기까지
        const val PRESSED_LINGER_MS = 600L   // 키를 뗀 뒤 표시를 남겨 두는 시간
        const val PAD_GAIN = 0.35f       // 트랙패드: 감도 1 일 때 1dp 당 마우스 이동량
        const val SCROLL_STEP_DP = 18f   // 트랙패드: 휠 한 칸에 해당하는 두 손가락 이동 거리
        const val TAP_SLOP_DP = 12f      // 트랙패드: 이보다 덜 움직여야 탭으로 본다
        const val TAP_TIMEOUT_MS = 250L
        const val TIMER_RESET_MS = 600L      // 타이머 패널을 이만큼 누르고 있다 떼면 처음으로
        const val KEY_STEP_DEG = 2f    // 키 방식 노브: 이만큼 돌아가야 키를 누른다
        const val KEY_HOLD_MS = 150L   // 키 방식 노브: 회전이 멈춘 뒤 키를 떼기까지의 시간
        const val DEAD_ZONE = 0.12f    // 노브 중심 주변의 무시 구간(지름 대비 비율)
    }
}
