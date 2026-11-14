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
) {
    fun copy() = PadButton(x, y, w, h, label, keys.toMutableList(), mode, shape, color)

    fun contains(px: Float, py: Float): Boolean {
        if (px < x || py < y || px > x + w || py > y + h) return false
        if (shape != SHAPE_OVAL) return true
        val dx = (px - (x + w / 2)) / (w / 2)
        val dy = (py - (y + h / 2)) / (h / 2)
        return dx * dx + dy * dy <= 1f
    }

    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("label", label).put("keys", JSONArray(keys))
        .put("mode", mode).put("shape", shape).put("color", color)

    companion object {
        const val MODE_HOLD = 0    // 누르고 있는 동안 입력 (게임)
        const val MODE_TAP = 1     // 누를 때 한 번 입력 (단축키)
        const val MODE_TOGGLE = 2  // 누를 때마다 켜짐/꺼짐
        const val SHAPE_RECT = 0
        const val SHAPE_OVAL = 1

        val PALETTE = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFF81C784.toInt(), 0xFFE57373.toInt(),
            0xFFBA68C8.toInt(), 0xFFFFF176.toInt(), 0xFF4DB6AC.toInt(), 0xFFB0BEC5.toInt(),
        )

        fun fromJson(o: JSONObject): PadButton {
            val ks = o.optJSONArray("keys") ?: JSONArray()
            return PadButton(
                o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                o.optString("label"),
                MutableList(ks.length()) { ks.getInt(it) },
                o.optInt("mode"), o.optInt("shape"), o.optInt("color", PALETTE[0]),
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
    val names = listOf("빈 레이아웃", "리듬게임 4키 (D F J K)", "리듬게임 7키 (S D F Space J K L)", "작업용 단축키")

    fun create(index: Int, name: String): PadLayout = when (index) {
        1 -> row(name, listOf("D" to 0x07, "F" to 0x09, "J" to 0x0D, "K" to 0x0E))
        2 -> row(
            name,
            listOf("S" to 0x16, "D" to 0x07, "F" to 0x09, "Space" to 0x2C, "J" to 0x0D, "K" to 0x0E, "L" to 0x0F),
        )
        3 -> shortcuts(name)
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
