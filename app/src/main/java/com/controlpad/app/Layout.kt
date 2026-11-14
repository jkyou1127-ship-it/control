package com.controlpad.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 좌표와 크기는 화면 대비 비율(0~1)이라 해상도가 달라도 같은 배치가 된다. */
class PadButton(
    var x: Float, var y: Float, var w: Float, var h: Float,
    var label: String = "",
    var keys: MutableList<Int> = mutableListOf(),
    var mode: Int = MODE_HOLD,
    var shape: Int = SHAPE_RECT,
    var color: Int = PALETTE[0],
    /** TYPE_KNOB 이면 돌리는 노브다. 이때 keys 는 왼쪽(반시계) 회전 키, keys2 는 오른쪽(시계) 회전 키. */
    var type: Int = TYPE_BUTTON,
    var keys2: MutableList<Int> = mutableListOf(),
    var knobOut: Int = OUT_MOUSE_X,
    /** 마우스 출력일 때 1도 돌릴 때마다 보내는 이동량. */
    var sens: Float = 4f,
    var invert: Boolean = false,
    /** 0 이면 항상 보인다. 1 이상이면 그 그룹의 표시 버튼을 눌러야 나타난다. */
    var group: Int = 0,
    /** TYPE_SHOW 일 때, 나타나게 할 그룹 번호. */
    var target: Int = 1,
    /** 0 이면 테마를 따른다. */
    var textColor: Int = 0,
    /** 눌렸을 때 색. 0 이면 버튼 색과 같다. */
    var pressColor: Int = 0,
    /** MODE_TIMED 일 때 키를 누르고 있을 시간(밀리초). */
    var holdMs: Int = 1000,
    /** PC 가 한글 입력 상태일 때 보여 줄 이름. 비어 있으면 label 을 그대로 쓴다. */
    var label2: String = "",
) {
    val isShow: Boolean get() = type == TYPE_SHOW
    val isKnob: Boolean get() = type == TYPE_KNOB
    val isPad: Boolean get() = type == TYPE_TRACKPAD
    val isMouse: Boolean get() = type == TYPE_MOUSE

    fun copy() = PadButton(
        x, y, w, h, label, keys.toMutableList(), mode, shape, color,
        type, keys2.toMutableList(), knobOut, sens, invert, group, target, textColor, pressColor, holdMs, label2,
    )

    fun contains(px: Float, py: Float): Boolean {
        if (px < x || py < y || px > x + w || py > y + h) return false
        if (shape != SHAPE_OVAL && !isKnob) return true
        val dx = (px - (x + w / 2)) / (w / 2)
        val dy = (py - (y + h / 2)) / (h / 2)
        return dx * dx + dy * dy <= 1f
    }

    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("label", label).put("keys", JSONArray(keys))
        .put("mode", mode).put("shape", shape).put("color", color)
        .put("type", type).put("keys2", JSONArray(keys2)).put("knobOut", knobOut)
        .put("sens", sens.toDouble()).put("invert", invert)
        .put("group", group).put("target", target)
        .put("textColor", textColor).put("pressColor", pressColor).put("holdMs", holdMs)
        .put("label2", label2)

    companion object {
        const val MODE_HOLD = 0    // 누르고 있는 동안 입력 (게임)
        const val MODE_TAP = 1     // 누를 때 한 번 입력 (단축키)
        const val MODE_TOGGLE = 2  // 누를 때마다 켜짐/꺼짐
        const val MODE_TIMED = 3   // 누르면 holdMs 동안 입력하고 스스로 뗀다
        const val MIN_HOLD_MS = 50
        const val MAX_HOLD_MS = 60000

        /** 사용자가 적은 초 단위 값을 밀리초로 바꾼다. 못 읽으면 1초. */
        fun parseHold(seconds: String): Int =
            ((seconds.trim().replace(',', '.').toFloatOrNull() ?: 1f) * 1000).toInt().coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        const val SHAPE_RECT = 0
        const val SHAPE_OVAL = 1
        const val TYPE_BUTTON = 0
        const val TYPE_KNOB = 1
        const val TYPE_TRACKPAD = 2  // 마우스를 움직이는 영역. mode 가 PAD_NO_TAP 이면 탭 클릭을 끈다
        const val TYPE_MOUSE = 3     // 마우스 모양: 좌·우 버튼, 휠, 몸통(이동)
        const val TYPE_SHOW = 4      // 누르면 target 그룹의 버튼들이 나타나는 버튼
        const val GROUPS = 8
        const val PAD_TAP = 0
        const val PAD_NO_TAP = 1
        const val OUT_MOUSE_X = 0  // 마우스 좌우 이동 (아날로그)
        const val OUT_MOUSE_Y = 1  // 마우스 상하 이동 (아날로그)
        const val OUT_KEYS = 2     // 돌리는 동안 키를 누름

        val PALETTE = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFF81C784.toInt(), 0xFFE57373.toInt(),
            0xFFBA68C8.toInt(), 0xFFFFF176.toInt(), 0xFF4DB6AC.toInt(), 0xFFB0BEC5.toInt(),
        )

        fun fromJson(o: JSONObject): PadButton {
            val ks = o.optJSONArray("keys") ?: JSONArray()
            val ks2 = o.optJSONArray("keys2") ?: JSONArray()
            return PadButton(
                o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                o.optString("label"),
                MutableList(ks.length()) { ks.getInt(it) },
                o.optInt("mode"), o.optInt("shape"), o.optInt("color", PALETTE[0]),
                o.optInt("type"), MutableList(ks2.length()) { ks2.getInt(it) },
                o.optInt("knobOut"), o.optDouble("sens", 4.0).toFloat(), o.optBoolean("invert"),
                o.optInt("group").coerceIn(0, GROUPS), o.optInt("target", 1).coerceIn(1, GROUPS),
                o.optInt("textColor"), o.optInt("pressColor"),
                o.optInt("holdMs", 1000).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS),
                o.optString("label2"),
            )
        }
    }
}

class PadLayout(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    /** 손가락을 떼지 않고 옆 버튼으로 미끄러져도 입력되게 할지. */
    var slide: Boolean = false,
    val buttons: MutableList<PadButton> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("slide", slide)
        .put("buttons", JSONArray().also { a -> buttons.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): PadLayout {
            val bs = o.optJSONArray("buttons") ?: JSONArray()
            return PadLayout(
                o.getString("id"), o.optString("name"), o.optBoolean("slide"),
                MutableList(bs.length()) { PadButton.fromJson(bs.getJSONObject(it)) },
            )
        }
    }
}

object LayoutStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "layouts").apply { mkdirs() }

    fun list(ctx: Context): List<PadLayout> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { PadLayout.fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun load(ctx: Context, id: String): PadLayout? {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return null
        return runCatching { PadLayout.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(ctx: Context, layout: PadLayout) {
        val f = File(dir(ctx), "${layout.id}.json")
        val tmp = File(dir(ctx), "${layout.id}.tmp")
        tmp.writeText(layout.toJson().toString())
        tmp.renameTo(f)
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }
}

/** 게임별 퀵 리스타트 키. 누르고 있는 동안 입력되므로, 길게 눌러야 하는 게임에서도 쓸 수 있다. */
object Restart {
    val names = listOf("사운드 볼텍스 (Enter + Space + R Alt)", "DJMAX · EZ2ON (F5)")
    val SDVX = listOf(0xE6, 0x28, 0x2C)
    val F5 = listOf(0x3E)

    fun button(index: Int, x: Float, y: Float, w: Float, h: Float) = PadButton(
        x, y, w, h, "RESTART", (if (index == 0) SDVX else F5).toMutableList(), color = PadButton.PALETTE[3],
    )
}

/** 숫자 키패드 한 벌을 버튼들로 만든다. 만든 뒤에는 보통 버튼처럼 하나씩 고칠 수 있다. */
object Keypad {
    val names = listOf("숫자패드 키 (Num 0~9, PC의 Num Lock이 켜져 있어야 숫자 입력)", "일반 숫자 키 (키보드 윗줄 0~9)")

    // (이름, 숫자패드 키, 일반 키, 열, 행, 가로 칸 수, 세로 칸 수)
    private class Cell(val label: String, val num: Int, val plain: List<Int>, val col: Int, val row: Int, val cw: Int = 1, val rh: Int = 1)

    private val shift = 0xE1
    private val cells = listOf(
        Cell("⌫", 0x2A, listOf(0x2A), 0, 0), Cell("/", 0x54, listOf(0x38), 1, 0),
        Cell("*", 0x55, listOf(shift, 0x25), 2, 0), Cell("-", 0x56, listOf(0x2D), 3, 0),
        Cell("7", 0x5F, listOf(0x24), 0, 1), Cell("8", 0x60, listOf(0x25), 1, 1), Cell("9", 0x61, listOf(0x26), 2, 1),
        Cell("+", 0x57, listOf(shift, 0x2E), 3, 1, rh = 2),
        Cell("4", 0x5C, listOf(0x21), 0, 2), Cell("5", 0x5D, listOf(0x22), 1, 2), Cell("6", 0x5E, listOf(0x23), 2, 2),
        Cell("1", 0x59, listOf(0x1E), 0, 3), Cell("2", 0x5A, listOf(0x1F), 1, 3), Cell("3", 0x5B, listOf(0x20), 2, 3),
        Cell("Enter", 0x58, listOf(0x28), 3, 3, rh = 2),
        Cell("0", 0x62, listOf(0x27), 0, 4, cw = 2), Cell(".", 0x63, listOf(0x37), 2, 4),
    )

    /** (x, y, w, h) 영역 안에 4열 5행으로 놓는다. numpad 가 참이면 숫자패드 키, 아니면 일반 키를 쓴다. */
    fun create(numpad: Boolean, x: Float, y: Float, w: Float, h: Float): List<PadButton> {
        val gap = 0.012f
        val cw = (w - gap * 3) / 4
        val rh = (h - gap * 4) / 5
        return cells.map { c ->
            val digit = c.label.length == 1 && c.label[0].isDigit()
            PadButton(
                x + c.col * (cw + gap), y + c.row * (rh + gap),
                cw * c.cw + gap * (c.cw - 1), rh * c.rh + gap * (c.rh - 1),
                c.label, if (numpad) mutableListOf(c.num) else c.plain.toMutableList(),
                color = PadButton.PALETTE[if (digit || c.label == ".") 7 else if (c.label == "Enter") 2 else if (c.label == "⌫") 3 else 1],
            )
        }
    }
}

/** PC 키보드 전체 배열(기능키 줄 포함 6줄). 한 줄은 15칸이다. */
object Keyboard {
    val names = listOf("영문 키보드", "한글 키보드")

    // 한 줄 = (이름, 키, 칸 수) 목록
    private fun k(label: String, usage: Int, units: Float = 1f) = Triple(label, usage, units)

    private const val JAMO = "ㅁㅠㅊㅇㄷㄹㅎㅗㅑㅓㅏㅣㅡㅜㅐㅔㅂㄱㄴㅅㅕㅍㅈㅌㅛㅋ"   // A~Z 자리의 두벌식 자모

    private fun letters(s: String) = s.map { k(it.toString(), 0x04 + (it - 'A')) }

    private fun rows(korean: Boolean): List<List<Triple<String, Int, Float>>> = listOf(
        listOf(k("Esc", 0x29)) + (1..12).map { k("F$it", 0x39 + it) } + k("Del", 0x4C, 2f),
        listOf(k("`", 0x35)) + (1..9).map { k(it.toString(), 0x1D + it) } +
            listOf(k("0", 0x27), k("-", 0x2D), k("=", 0x2E), k("⌫", 0x2A, 2f)),
        listOf(k("Tab", 0x2B, 1.5f)) + letters("QWERTYUIOP") +
            listOf(k("[", 0x2F), k("]", 0x30), k(if (korean) "₩" else "\\", 0x31, 1.5f)),
        listOf(k("Caps", 0x39, 1.75f)) + letters("ASDFGHJKL") +
            listOf(k(";", 0x33), k("'", 0x34), k("Enter", 0x28, 2.25f)),
        listOf(k("Shift", 0xE1, 2.25f)) + letters("ZXCVBNM") +
            listOf(k(",", 0x36), k(".", 0x37), k("/", 0x38), k("Shift", 0xE5, 2.75f)),
        listOf(k("Ctrl", 0xE0, 1.5f), k("Win", 0xE3, 1.25f), k("Alt", 0xE2, 1.25f)) +
            (if (korean) listOf(k("한자", Keys.HANJA), k("Space", 0x2C, 5f), k("한/영", Keys.HANGUL, 1.25f))
            else listOf(k("Space", 0x2C, 6f), k("R Alt", 0xE6, 1.25f))) +
            listOf(k("←", 0x50, 0.9375f), k("↓", 0x51, 0.9375f), k("↑", 0x52, 0.9375f), k("→", 0x4F, 0.9375f)),
    )

    /**
     * korean 이 참이면 한/영·한자 키를 넣고, 글자 키에 한글 상태용 이름(자모)을 붙인다.
     * 실행 화면에서 한/영을 누를 때마다 글자 키의 표시가 영문 ↔ 자모로 바뀐다. 입력되는 키는 영문 배열과 같다.
     */
    fun create(korean: Boolean = false): List<PadButton> {
        val margin = 0.01f
        val gap = 0.006f
        val rows = rows(korean)
        val unit = (1f - margin * 2) / 15f
        val rowH = (1f - margin * 2) / rows.size
        val p = PadButton.PALETTE
        val out = ArrayList<PadButton>()
        rows.forEachIndexed { r, row ->
            var x = margin
            for ((label, usage, units) in row) {
                val plain = label.length == 1 && label != "⌫"      // 글자·숫자·기호 키
                val lang = usage == Keys.HANGUL || usage == Keys.HANJA
                out += PadButton(
                    x + gap / 2, margin + r * rowH + gap / 2, unit * units - gap, rowH - gap,
                    label, mutableListOf(usage),
                    label2 = if (korean && usage in 0x04..0x1D) JAMO[usage - 0x04].toString() else "",
                    color = if (plain) p[7] else if (lang) p[4] else if (Keys.isModifier(usage)) p[0]
                    else if (label == "Enter") p[2] else p[1],
                )
                x += unit * units
            }
        }
        return out
    }
}

object Templates {
    val names = listOf(
        "빈 레이아웃", "리듬게임 4키 (D F J K)", "리듬게임 7키 (S D F Space J K L)", "작업용 단축키",
        "사운드 볼텍스 · KSM (노브 2개)", "트랙패드 + 마우스 버튼", "숫자 키패드", "영문 키보드", "한글 키보드",
    )

    fun create(index: Int, name: String): PadLayout = when (index) {
        1 -> row(name, listOf("D" to 0x07, "F" to 0x09, "J" to 0x0D, "K" to 0x0E))
        2 -> row(
            name,
            listOf("S" to 0x16, "D" to 0x07, "F" to 0x09, "Space" to 0x2C, "J" to 0x0D, "K" to 0x0E, "L" to 0x0F),
        )
        3 -> shortcuts(name)
        4 -> voltex(name)
        5 -> trackpad(name)
        7 -> PadLayout(name = name, buttons = Keyboard.create(false).toMutableList())
        8 -> PadLayout(name = name, buttons = Keyboard.create(true).toMutableList())
        6 -> PadLayout(name = name, buttons = Keypad.create(true, 0.3f, 0.03f, 0.4f, 0.94f).toMutableList())
        else -> PadLayout(name = name)
    }

    private fun row(name: String, keys: List<Pair<String, Int>>): PadLayout {
        val l = PadLayout(name = name)
        val gap = 0.015f
        val w = (1f - gap * (keys.size + 1)) / keys.size
        keys.forEachIndexed { i, (label, usage) ->
            l.buttons += PadButton(
                gap + i * (w + gap), 0.3f, w, 0.66f, label, mutableListOf(usage),
                color = PadButton.PALETTE[if (label == "Space") 1 else if (i % 2 == 0) 0 else 2],
            )
        }
        l.buttons += Restart.button(1, 0.845f, 0.04f, 0.14f, 0.2f)
        return l
    }

    /** K-Shoot MANIA 기본 키 배치: BT = S D K L, FX = C M, 노브 = Q/W, O/P, 스타트 = Enter. */
    private fun voltex(name: String): PadLayout {
        val l = PadLayout(name = name)
        val p = PadButton.PALETTE
        fun knob(x: Float, label: String, left: Int, right: Int, color: Int) = PadButton(
            x, 0.03f, 0.2f, 0.36f, label, mutableListOf(left), color = color,
            type = PadButton.TYPE_KNOB, keys2 = mutableListOf(right), knobOut = PadButton.OUT_KEYS,
        )
        l.buttons += knob(0.02f, "VOL-L", 0x14, 0x1A, p[0])
        l.buttons += knob(0.78f, "VOL-R", 0x12, 0x13, p[3])
        l.buttons += PadButton(0.42f, 0.05f, 0.16f, 0.16f, "START", mutableListOf(0x28), color = p[4])
        l.buttons += Restart.button(0, 0.42f, 0.23f, 0.16f, 0.14f)
        listOf("BT-A" to 0x16, "BT-B" to 0x07, "BT-C" to 0x0E, "BT-D" to 0x0F).forEachIndexed { i, (label, key) ->
            l.buttons += PadButton(0.1f + i * 0.205f, 0.42f, 0.185f, 0.3f, label, mutableListOf(key), color = p[7])
        }
        l.buttons += PadButton(0.2f, 0.76f, 0.29f, 0.2f, "FX-L", mutableListOf(0x06), color = p[1])
        l.buttons += PadButton(0.51f, 0.76f, 0.29f, 0.2f, "FX-R", mutableListOf(0x10), color = p[1])
        return l
    }

    private fun trackpad(name: String): PadLayout {
        val l = PadLayout(name = name)
        val p = PadButton.PALETTE
        l.buttons += PadButton(0.02f, 0.03f, 0.66f, 0.94f, type = PadButton.TYPE_TRACKPAD, color = p[7])
        l.buttons += PadButton(0.7f, 0.03f, 0.135f, 0.3f, "좌클릭", mutableListOf(Keys.MOUSE_LEFT), color = p[0])
        l.buttons += PadButton(0.845f, 0.03f, 0.135f, 0.3f, "우클릭", mutableListOf(Keys.MOUSE_RIGHT), color = p[3])
        val keys = listOf(
            Triple("복사", listOf(0xE0, 0x06), 2), Triple("붙여넣기", listOf(0xE0, 0x19), 2),
            Triple("실행 취소", listOf(0xE0, 0x1D), 1), Triple("Enter", listOf(0x28), 4),
        )
        keys.forEachIndexed { i, (label, ks, color) ->
            l.buttons += PadButton(
                0.7f + (i % 2) * 0.145f, 0.36f + (i / 2) * 0.31f, 0.135f, 0.29f,
                label, ks.toMutableList(), PadButton.MODE_TAP, color = p[color],
            )
        }
        return l
    }

    private fun shortcuts(name: String): PadLayout {
        val ctrl = 0xE0
        val items = listOf(
            Triple("복사", listOf(ctrl, 0x06), 0), Triple("붙여넣기", listOf(ctrl, 0x19), 0),
            Triple("잘라내기", listOf(ctrl, 0x1B), 0), Triple("전체 선택", listOf(ctrl, 0x04), 6),
            Triple("실행 취소", listOf(ctrl, 0x1D), 1), Triple("다시 실행", listOf(ctrl, 0x1C), 1),
            Triple("저장", listOf(ctrl, 0x16), 2), Triple("창 전환", listOf(0xE2, 0x2B), 4),
        )
        val l = PadLayout(name = name)
        val cols = 4
        val gap = 0.02f
        val w = (1f - gap * (cols + 1)) / cols
        val h = (1f - gap * 3) / 2
        items.forEachIndexed { i, (label, keys, color) ->
            l.buttons += PadButton(
                gap + (i % cols) * (w + gap), gap + (i / cols) * (h + gap), w, h,
                label, keys.toMutableList(), PadButton.MODE_TAP, color = PadButton.PALETTE[color],
            )
        }
        return l
    }
}

object Knob {
    /** 화면 좌표에서 중심 기준 각도(도). y 가 아래로 커지므로 값이 커질수록 시계 방향이다. */
    fun angle(dx: Float, dy: Float): Float = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

    /** 이전 각도에서 현재 각도까지의 회전량. 180도 경계를 넘어도 짧은 쪽으로 계산한다. */
    fun delta(prev: Float, now: Float): Float {
        var d = (now - prev) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** 원점으로 돌아가는 애니메이션의 한 단계: 남은 각도를 줄이고, 거의 다 왔으면 0 으로 맞춘다. */
    fun settle(angle: Float): Float {
        val next = angle * 0.7f
        return if (kotlin.math.abs(next) < 1f) 0f else next
    }

    fun describe(b: PadButton): String = when (b.knobOut) {
        PadButton.OUT_MOUSE_X -> "마우스 X축"
        PadButton.OUT_MOUSE_Y -> "마우스 Y축"
        else -> Keys.combo(b.keys).ifEmpty { "?" } + " ↺ ↻ " + Keys.combo(b.keys2).ifEmpty { "?" }
    }
}

/** 가상 마우스의 부분. 위쪽 40%가 버튼 줄(왼쪽 40% 좌클릭, 가운데 20% 휠, 오른쪽 40% 우클릭), 아래는 몸통. */
object MousePart {
    const val LEFT = 0
    const val RIGHT = 1
    const val WHEEL = 2
    const val BODY = 3
    const val TOP = 0.4f
    const val WHEEL_FROM = 0.4f
    const val WHEEL_TO = 0.6f

    /** u, v 는 마우스 영역 안에서의 위치 비율(0~1). */
    fun of(u: Float, v: Float): Int = when {
        v >= TOP -> BODY
        u < WHEEL_FROM -> LEFT
        u > WHEEL_TO -> RIGHT
        else -> WHEEL
    }
}

object Resize {
    /**
     * 시작점 origin, 길이 size 인 영역의 끝을 target 까지 끌었을 때의 배율.
     * 가장 작은 버튼(smallest)이 min 보다 작아지지 않고, 영역이 화면(1.0) 밖으로 나가지 않게 제한한다.
     */
    fun scale(origin: Float, size: Float, target: Float, smallest: Float, min: Float): Float {
        if (size <= 0f || smallest <= 0f) return 1f
        val lowest = minOf(min / smallest, 1f)        // 이미 min 보다 작은 버튼은 더 줄이지 않는다
        val highest = (1f - origin) / size
        return ((target - origin) / size).coerceIn(lowest, maxOf(lowest, highest))
    }
}

object History {
    /** 실행취소로 되돌릴 수 있는 횟수. */
    const val LIMIT = 50
}
