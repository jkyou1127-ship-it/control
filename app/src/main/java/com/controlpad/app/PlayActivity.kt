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
    private val tick = object : Runnable {
        override fun run() {
            view?.setStatus(Link.connected, Link.status())
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LayoutStore.load(this, intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun show(layout: PadLayout) {
        view = PlayView(this, layout).also {
            it.onSwitch = { id -> switchTo(id) }
            setContentView(it)
        }
    }

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
        if (Labs.isOn("max-brightness")) window.attributes = window.attributes.apply { screenBrightness = 1f }
        Link.setActive(true)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
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
    private val labPop = Labs.isOn("press-pop")
    private val pressTimes = ArrayDeque<Long>()         // 최근에 버튼을 누른 시각들(초당 입력 수 계산용)
    private val labCombo = Labs.isOn("combo")
    private val labTotal = Labs.isOn("total")
    private val labTimer = Labs.isOn("timer")
    private val labBattery = Labs.isOn("battery")
    private val hapticKind =
        if (Labs.isOn("strong-haptic")) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
    private var combo = 0
    private var lastPress = 0L
    private var total = 0
    private val startedAt = SystemClock.uptimeMillis()
    private var batteryText = ""
    private var batteryAt = 0L

    /** 버튼을 한 번 눌렀다고 기록한다(초당 입력 수, 콤보, 총 입력 수). */
    private val labHideStatus = Labs.isOn("hide-status")
    private val labSound = Labs.isOn("press-sound")
    private val labHistory = Labs.isOn("key-history")
    private val history = ArrayDeque<String>()          // 최근에 누른 버튼의 키들
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    private fun notePress(b: PadButton) {
        val now = SystemClock.uptimeMillis()
        if (labKps) pressTimes.addLast(now)
        combo = Combo.next(combo, lastPress, now)
        lastPress = now
        total++
        if (labSound) audio?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 0.6f)
        if (labHistory) {
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
            val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
            batteryText = (if (level in 0..100) "배터리 $level% · " else "") + clock
        }
        return batteryText
    }

    /** 최근 1초 동안 누른 횟수. */
    private fun kps(): Int {
        val now = SystemClock.uptimeMillis()
        while (pressTimes.isNotEmpty() && now - pressTimes.first() > 1000) pressTimes.removeFirst()
        return pressTimes.size
    }

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
            if (!buttons[i].isDisplay && !buttons[i].isImage && groupVisible(buttons[i].group) && buttons[i].contains(nx, ny)) return i
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
    private val hasPanel = buttons.any { it.isDisplay }
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
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
        c.drawBitmap(idleLayer(), 0f, 0f, null)
        for (i in buttons.indices) {
            val b = buttons[i]
            if (!groupVisible(b.group)) continue
            val lit = if (b.isShow) groupVisible(b.target)
            else if (b.isKnob) holds[i] > 0
            else flash[i] || toggled[i] || timedOn[i] ||
                (holds[i] > 0 && (b.mode == PadButton.MODE_HOLD || b.mode == PadButton.MODE_TURBO))
            var parts = 0
            if (b.isMouse) for ((id, part) in mouseParts) if (pointers[id] == i) parts = parts or (1 shl part)
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
            if (labPop && lit && b.type == PadButton.TYPE_BUTTON) rect.inset(-rect.width() * 0.04f, -rect.height() * 0.04f)
            painter.draw(c, b, rect, lit, false, knobAngle[i], parts, if (hangul) 1 else 0)
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
        if (!labHideStatus) parts += (if (connected) "● " else "○ ") + status
        if (labKps) {
            val n = kps()
            parts += "${n}회/초"
            if (n > 0) postInvalidateDelayed(200)       // 손을 떼도 숫자가 0 으로 내려가게
        }
        val now = SystemClock.uptimeMillis()
        if (labCombo) {
            if (now - lastPress > Combo.WINDOW_MS) combo = 0
            if (combo >= 2) { parts += "${combo}콤보"; postInvalidateDelayed(250) }
        }
        if (labTotal) parts += "총 ${total}회"
        if (labTimer) parts += Combo.format(now - startedAt)
        if (labBattery) parts += battery()
        if (labTimer || labBattery) postInvalidateDelayed(1000)
        if (parts.isNotEmpty()) c.drawText(parts.joinToString(" · "), statusPaint.textSize, statusPaint.textSize * 1.6f, statusPaint)
        if (labHistory && history.isNotEmpty()) {
            c.drawText(history.joinToString("  ›  "), statusPaint.textSize, statusPaint.textSize * 3.1f, statusPaint)
        }
    }

    companion object {
        const val TAP_MS = 40L
        const val MACRO_GAP_MS = 40L         // 매크로: 한 조합을 뗀 뒤 다음 조합을 누르기까지
        const val PRESSED_LINGER_MS = 600L   // 키를 뗀 뒤 표시를 남겨 두는 시간
        const val PAD_GAIN = 0.35f       // 트랙패드: 감도 1 일 때 1dp 당 마우스 이동량
        const val SCROLL_STEP_DP = 18f   // 트랙패드: 휠 한 칸에 해당하는 두 손가락 이동 거리
        const val TAP_SLOP_DP = 12f      // 트랙패드: 이보다 덜 움직여야 탭으로 본다
        const val TAP_TIMEOUT_MS = 250L
        const val KEY_STEP_DEG = 2f    // 키 방식 노브: 이만큼 돌아가야 키를 누른다
        const val KEY_HOLD_MS = 150L   // 키 방식 노브: 회전이 멈춘 뒤 키를 떼기까지의 시간
        const val DEAD_ZONE = 0.12f    // 노브 중심 주변의 무시 구간(지름 대비 비율)
    }
}
