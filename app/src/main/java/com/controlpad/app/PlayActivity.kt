package com.controlpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** 전체 화면 컨트롤러. 뒤로 가기로 나간다. */
class PlayActivity : BaseActivity() {
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
        view = PlayView(this, layout).also { setContentView(it) }
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
    private var connected = false
    private var status = ""
    private val taps = Handler(Looper.getMainLooper())  // '한 번 입력'의 키 떼기 예약
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(12).toFloat() }

    fun setStatus(ok: Boolean, text: String) {
        if (ok == connected && text == status) return
        connected = ok; status = text
        invalidate()
    }

    private fun hit(x: Float, y: Float): Int {
        if (width == 0 || height == 0) return -1
        val nx = x / width
        val ny = y / height
        for (i in buttons.indices.reversed()) if (buttons[i].contains(nx, ny)) return i
        return -1
    }

    private fun send() = Link.send(state.report())

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
            if (knobRem[i] * d < 0) knobRem[i] = 0f           // 방향이 바뀌면 누적을 버린다
            knobRem[i] += d
            if (kotlin.math.abs(knobRem[i]) >= KEY_STEP_DEG) {
                knobKey(i, if (knobRem[i] > 0) 1 else -1)
                knobRem[i] = 0f
                taps.removeCallbacks(knobStop[i])
                taps.postDelayed(knobStop[i], KEY_HOLD_MS)
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
        if (++holds[i] != 1) return
        val b = buttons[i]
        if (b.isKnob) return
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (b.mode) {
            PadButton.MODE_TAP -> {
                state.press(b.keys); send(); flash[i] = true
                taps.postDelayed({ state.release(b.keys); send(); flash[i] = false; invalidate() }, TAP_MS)
            }
            PadButton.MODE_TOGGLE -> {
                toggled[i] = !toggled[i]
                if (toggled[i]) state.press(b.keys) else state.release(b.keys)
                send()
            }
            else -> { state.press(b.keys); send() }
        }
    }

    private fun up(i: Int) {
        if (i < 0 || holds[i] == 0) return
        if (--holds[i] != 0) return
        val b = buttons[i]
        if (b.isKnob) {
            taps.removeCallbacks(knobStop[i])
            knobKey(i, 0)
            knobRem[i] = 0f
        } else if (b.mode == PadButton.MODE_HOLD) { state.release(b.keys); send() }
    }

    fun releaseAll() {
        pointers.clear(); lastAngle.clear()
        knobDir.fill(0); knobRem.fill(0f)
        holds.fill(0); toggled.fill(false); flash.fill(false)
        taps.removeCallbacksAndMessages(null)
        state.clear(); send()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val b = hit(e.getX(idx), e.getY(idx))
                pointers[e.getPointerId(idx)] = b
                if (b >= 0 && buttons[b].isKnob) lastAngle[e.getPointerId(idx)] = pointerAngle(b, e.getX(idx), e.getY(idx))
                down(b)
            }
            MotionEvent.ACTION_MOVE -> for (idx in 0 until e.pointerCount) {
                val id = e.getPointerId(idx)
                val before = pointers[id] ?: -1
                if (before >= 0 && buttons[before].isKnob) {
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
                    if (now >= 0 && buttons[now].isKnob) now = -1   // 미끄러져서 노브를 잡지는 않는다
                    if (now != before) { up(before); pointers[id] = now; down(now) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                lastAngle.remove(id)
                up(pointers.remove(id) ?: -1)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (b in pointers.values) up(b)
                pointers.clear(); lastAngle.clear()
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        for (i in buttons.indices) {
            val b = buttons[i]
            rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
            val lit = if (b.isKnob) holds[i] > 0
            else flash[i] || toggled[i] || (holds[i] > 0 && b.mode == PadButton.MODE_HOLD)
            painter.draw(c, b, rect, lit, false, knobAngle[i])
        }
        statusPaint.color = if (connected) 0x9981C784.toInt() else 0xFFFFB74D.toInt()
        c.drawText((if (connected) "● " else "○ ") + status, statusPaint.textSize, statusPaint.textSize * 1.6f, statusPaint)
    }

    companion object {
        const val TAP_MS = 40L
        const val KEY_STEP_DEG = 2f    // 키 방식 노브: 이만큼 돌아가야 키를 누른다
        const val KEY_HOLD_MS = 70L    // 키 방식 노브: 회전이 멈춘 뒤 키를 떼기까지의 시간
        const val DEAD_ZONE = 0.12f    // 노브 중심 주변의 무시 구간(지름 대비 비율)
    }
}
