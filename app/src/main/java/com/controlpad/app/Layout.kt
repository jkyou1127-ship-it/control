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
) {
    val isKnob: Boolean get() = type == TYPE_KNOB
    val isPad: Boolean get() = type == TYPE_TRACKPAD
    val isMouse: Boolean get() = type == TYPE_MOUSE

    fun copy() = PadButton(
        x, y, w, h, label, keys.toMutableList(), mode, shape, color,
        type, keys2.toMutableList(), knobOut, sens, invert,
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

    companion object {
        const val MODE_HOLD = 0    // 누르고 있는 동안 입력 (게임)
        const val MODE_TAP = 1     // 누를 때 한 번 입력 (단축키)
        const val MODE_TOGGLE = 2  // 누를 때마다 켜짐/꺼짐
        const val SHAPE_RECT = 0
        const val SHAPE_OVAL = 1
        const val TYPE_BUTTON = 0
        const val TYPE_KNOB = 1
        const val TYPE_TRACKPAD = 2  // 마우스를 움직이는 영역. mode 가 PAD_NO_TAP 이면 탭 클릭을 끈다
        const val TYPE_MOUSE = 3     // 마우스 모양: 좌·우 버튼, 휠, 몸통(이동)
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

object Templates {
    val names = listOf(
        "빈 레이아웃", "리듬게임 4키 (D F J K)", "리듬게임 7키 (S D F Space J K L)", "작업용 단축키",
        "사운드 볼텍스 · KSM (노브 2개)", "트랙패드 + 마우스 버튼",
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
