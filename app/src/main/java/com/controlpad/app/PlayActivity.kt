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

    private fun down(i: Int) {
        if (i < 0) return
        if (++holds[i] != 1) return
        val b = buttons[i]
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
        if (b.mode == PadButton.MODE_HOLD) { state.release(b.keys); send() }
    }

    fun releaseAll() {
        pointers.clear()
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
                down(b)
            }
            MotionEvent.ACTION_MOVE -> if (layout.slide) {
                for (idx in 0 until e.pointerCount) {
                    val id = e.getPointerId(idx)
                    val now = hit(e.getX(idx), e.getY(idx))
                    val before = pointers[id] ?: -1
                    if (now != before) { up(before); pointers[id] = now; down(now) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                up(pointers.remove(e.getPointerId(e.actionIndex)) ?: -1)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (b in pointers.values) up(b)
                pointers.clear()
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        for (i in buttons.indices) {
            val b = buttons[i]
            rect.set(b.x * width, b.y * height, (b.x + b.w) * width, (b.y + b.h) * height)
            val lit = flash[i] || toggled[i] || (holds[i] > 0 && b.mode == PadButton.MODE_HOLD)
            painter.draw(c, b, rect, lit, false)
        }
        statusPaint.color = if (connected) 0x9981C784.toInt() else 0xFFFFB74D.toInt()
        c.drawText((if (connected) "● " else "○ ") + status, statusPaint.textSize, statusPaint.textSize * 1.6f, statusPaint)
    }

    companion object {
        const val TAP_MS = 40L
    }
}
