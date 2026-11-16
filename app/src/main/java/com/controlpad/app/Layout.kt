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
    /** 버튼 면에 그릴 사진(앱 저장소 안의 파일 이름). TYPE_IMAGE 에서는 보여 줄 사진. */
    var image: String = "",
    /** MODE_MACRO 일 때 차례로 입력할 키 조합들. */
    var macro: MutableList<MutableList<Int>> = mutableListOf(),
    /** MODE_TURBO 일 때 1초에 누르는 횟수. */
    var rate: Int = 10,
    /** TYPE_SWITCH 일 때 넘어갈 레이아웃의 id. */
    var link: String = "",
    /** 편집 화면에서 실수로 옮기거나 크기를 바꾸지 않게 잠근다. */
    var locked: Boolean = false,
    /** 불투명도(%). 100 이면 완전히 보이고, 낮을수록 비친다. */
    var opacity: Int = 100,
    /** 글자 크기 배율(%). */
    var textScale: Int = 100,
) {
    val isSwitch: Boolean get() = type == TYPE_SWITCH
    val isOption: Boolean get() = type == TYPE_OPTION
    val isImage: Boolean get() = type == TYPE_IMAGE
    val isShow: Boolean get() = type == TYPE_SHOW
    val isStick: Boolean get() = type == TYPE_STICK
    val isDisplay: Boolean get() = type == TYPE_DISPLAY
    val isKnob: Boolean get() = type == TYPE_KNOB
    val isPad: Boolean get() = type == TYPE_TRACKPAD
    val isMouse: Boolean get() = type == TYPE_MOUSE

    fun copy() = PadButton(
        x, y, w, h, label, keys.toMutableList(), mode, shape, color,
        type, keys2.toMutableList(), knobOut, sens, invert, group, target, textColor, pressColor, holdMs, label2, image, macro.map { it.toMutableList() }.toMutableList(), rate, link, locked, opacity, textScale,
    )

    fun contains(px: Float, py: Float): Boolean {
        if (px < x || py < y || px > x + w || py > y + h) return false
        val dx = (px - (x + w / 2)) / (w / 2)
        val dy = (py - (y + h / 2)) / (h / 2)
        return when {
            isKnob || isStick || shape == SHAPE_OVAL -> dx * dx + dy * dy <= 1f
            shape == SHAPE_DIAMOND -> kotlin.math.abs(dx) + kotlin.math.abs(dy) <= 1f
            // 좌우 끝이 뾰족한 육각형: 위아래 변은 가로 가운데 절반
            shape == SHAPE_HEX -> kotlin.math.abs(dx) <= 1f - 0.5f * kotlin.math.abs(dy)
            else -> true
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("label", label).put("keys", JSONArray(keys))
        .put("mode", mode).put("shape", shape).put("color", color)
        .put("type", type).put("keys2", JSONArray(keys2)).put("knobOut", knobOut)
        .put("sens", sens.toDouble()).put("invert", invert)
        .put("group", group).put("target", target)
        .put("textColor", textColor).put("pressColor", pressColor).put("holdMs", holdMs)
        .put("label2", label2).put("image", image)
        .put("macro", JSONArray().also { a -> macro.forEach { a.put(JSONArray(it)) } }).put("rate", rate).put("link", link)
        .put("locked", locked).put("opacity", opacity).put("textScale", textScale)

    companion object {
        const val MODE_HOLD = 0    // 누르고 있는 동안 입력 (게임)
        const val MODE_TAP = 1     // 누를 때 한 번 입력 (단축키)
        const val MODE_TOGGLE = 2  // 누를 때마다 켜짐/꺼짐
        const val MODE_TIMED = 3   // 누르면 holdMs 동안 입력하고 스스로 뗀다
        const val MODE_MACRO = 4   // 누르면 macro 의 키 조합들을 차례로 입력한다
        const val MODE_TURBO = 5   // 누르고 있는 동안 1초에 rate 번씩 연타한다
        const val MIN_OPACITY = 10
        const val MIN_TEXT_SCALE = 50
        const val MAX_TEXT_SCALE = 200
        const val MIN_RATE = 1
        const val MAX_RATE = 30
        const val MIN_HOLD_MS = 50
        const val MAX_HOLD_MS = 60000

        /** 사용자가 적은 초 단위 값을 밀리초로 바꾼다. 못 읽으면 1초. */
        fun parseHold(seconds: String): Int =
            ((seconds.trim().replace(',', '.').toFloatOrNull() ?: 1f) * 1000).toInt().coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        const val SHAPE_RECT = 0
        const val SHAPE_OVAL = 1
        const val SHAPE_FREE = 2     // 그림 에디터로 그린 모양 그대로(그림은 image 에 있다)
        const val SHAPE_DIAMOND = 3
        const val SHAPE_HEX = 4
        const val SHAPE_SQUARE = 5   // 모서리가 각진 사각형
        const val TYPE_BUTTON = 0
        const val TYPE_KNOB = 1
        const val TYPE_TRACKPAD = 2  // 마우스를 움직이는 영역. mode 가 PAD_NO_TAP 이면 탭 클릭을 끈다
        const val TYPE_MOUSE = 3     // 마우스 모양: 좌·우 버튼, 휠, 몸통(이동)
        const val TYPE_SHOW = 4      // 누르면 target 그룹의 버튼들이 나타나는 버튼
        const val TYPE_STICK = 5     // 조이스틱. keys 는 위, 아래, 왼쪽, 오른쪽 키 순서(없는 방향은 0)
        const val STICK_8WAY = 0     // 대각선으로 기울이면 두 방향 키를 함께 누른다 (mode 값)
        const val STICK_4WAY = 1     // 항상 한 방향만
        const val TYPE_DISPLAY = 6   // 지금 눌리고 있는 키를 보여 주는 패널(누를 수 없음)
        const val TYPE_IMAGE = 7     // 꾸미기용 사진(누를 수 없음)
        const val TYPE_SWITCH = 8    // 누르면 link 의 레이아웃으로 넘어가는 버튼
        const val TYPE_OPTION = 9    // 누르면 link 의 설정(옵션)을 켜고 끄는 버튼
        const val GROUPS = 8
        const val KNOB_UNTIL_STOP = 0    // 키 방식 노브: 돌리는 동안만 누르고 멈추면 뗀다 (mode 값)
        const val KNOB_UNTIL_RELEASE = 1 // 키 방식 노브: 돌리기 시작하면 손을 뗄 때까지 계속 누른다
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
                o.optString("label2"), o.optString("image"),
                (o.optJSONArray("macro") ?: JSONArray()).let { a ->
                    MutableList(a.length()) { i -> a.getJSONArray(i).let { s -> MutableList(s.length()) { s.getInt(it) } } }
                },
                o.optInt("rate", 10).coerceIn(MIN_RATE, MAX_RATE), o.optString("link"),
                o.optBoolean("locked"), o.optInt("opacity", 100).coerceIn(MIN_OPACITY, 100),
                o.optInt("textScale", 100).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
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
    /** 목록 맨 위에 고정할지. */
    var pinned: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("slide", slide).put("pinned", pinned)
        .put("buttons", JSONArray().also { a -> buttons.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): PadLayout {
            val bs = o.optJSONArray("buttons") ?: JSONArray()
            return PadLayout(
                o.getString("id"), o.optString("name"), o.optBoolean("slide"),
                MutableList(bs.length()) { PadButton.fromJson(bs.getJSONObject(it)) },
                o.optBoolean("pinned"),
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
        "리듬게임 5키 (D F Space J K)", "리듬게임 6키 (S D F J K L)", "리듬게임 8키 (A S D F J K L ;)",
        "방향키 + 액션 (Z X C V)", "방송 · 녹화 (F9~F12, 장면 1~4)",
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
        9 -> row(name, listOf("D" to 0x07, "F" to 0x09, "Space" to 0x2C, "J" to 0x0D, "K" to 0x0E))
        10 -> row(name, listOf("S" to 0x16, "D" to 0x07, "F" to 0x09, "J" to 0x0D, "K" to 0x0E, "L" to 0x0F))
        11 -> row(name, listOf("A" to 0x04, "S" to 0x16, "D" to 0x07, "F" to 0x09, "J" to 0x0D, "K" to 0x0E, "L" to 0x0F, ";" to 0x33))
        12 -> arrowsAndActions(name)
        13 -> streaming(name)
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

    /** 왼쪽에 방향키 십자, 오른쪽에 액션 키 네 개(Z X C V). */
    private fun arrowsAndActions(name: String): PadLayout {
        val l = PadLayout(name = name)
        val p = PadButton.PALETTE
        val s = 0.2f
        fun key(x: Float, y: Float, label: String, usage: Int, color: Int) =
            PadButton(x, y, s, 0.29f, label, mutableListOf(usage), color = color)
        l.buttons += key(0.04f + s, 0.04f, "↑", 0x52, p[7])
        l.buttons += key(0.04f, 0.355f, "←", 0x50, p[7])
        l.buttons += key(0.04f + s * 2, 0.355f, "→", 0x4F, p[7])
        l.buttons += key(0.04f + s, 0.67f, "↓", 0x51, p[7])
        listOf("Z" to 0x1D, "X" to 0x1B, "C" to 0x06, "V" to 0x19).forEachIndexed { i, (label, usage) ->
            l.buttons += PadButton(0.7f + (i % 2) * 0.145f, 0.2f + (i / 2) * 0.32f, 0.135f, 0.3f, label, mutableListOf(usage), color = p[i % 4])
        }
        return l
    }

    /** 방송 · 녹화 프로그램에 단축키로 지정해 쓰는 버튼들: F9~F12 와 Ctrl+Shift+1~4(장면 전환). */
    private fun streaming(name: String): PadLayout {
        val l = PadLayout(name = name)
        val p = PadButton.PALETTE
        val top = listOf("방송 시작/끝 (F9)" to 0x42, "녹화 시작/끝 (F10)" to 0x43, "마이크 끄기 (F11)" to 0x44, "리플레이 저장 (F12)" to 0x45)
        top.forEachIndexed { i, (label, usage) ->
            l.buttons += PadButton(0.02f + i * 0.245f, 0.05f, 0.225f, 0.4f, label, mutableListOf(usage), PadButton.MODE_TAP, color = p[if (i < 2) 3 else 1])
        }
        for (i in 0 until 4) {
            l.buttons += PadButton(0.02f + i * 0.245f, 0.53f, 0.225f, 0.4f, "장면 ${i + 1}", mutableListOf(0xE0, 0xE1, 0x1E + i), PadButton.MODE_TAP, color = p[7])
        }
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

    /** 지금 누르고 있는 방향(held: -1, 0, 1)과 같은 쪽으로 돌았는지. */
    fun sameWay(held: Int, degrees: Float): Boolean = (held > 0 && degrees > 0f) || (held < 0 && degrees < 0f)

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

/** 그림 에디터: 손으로 그린 선들을 감싸는 영역을 구한다. */
object Sketch {
    /**
     * 점들(x0, y0, x1, y1, …)을 모두 감싸고 선 굵기의 절반만큼 넉넉한 영역을 [왼쪽, 위, 오른쪽, 아래]로 돌려준다.
     * 화면(가로 width, 세로 height) 밖으로는 나가지 않는다. 점이 없으면 null.
     */
    fun bounds(strokes: List<FloatArray>, margin: Float, width: Float, height: Float): FloatArray? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in strokes) for (i in 0 until s.size / 2) {
            l = minOf(l, s[i * 2]); r = maxOf(r, s[i * 2]); t = minOf(t, s[i * 2 + 1]); b = maxOf(b, s[i * 2 + 1])
        }
        if (l > r) return null
        return floatArrayOf(
            (l - margin).coerceAtLeast(0f), (t - margin).coerceAtLeast(0f),
            (r + margin).coerceAtMost(width), (b + margin).coerceAtMost(height),
        )
    }
}

object History {
    /** 실행취소로 되돌릴 수 있는 횟수. */
    const val LIMIT = 50
}

/** 레이아웃을 파일로 내보내고 불러온다(백업, 다른 기기로 옮기기, 공유). */
object LayoutFile {
    const val MAX_BYTES = 24 * 1024 * 1024      // 사진이 함께 들어가므로 넉넉히
    private const val FORMAT = "controlpad-layout"

    fun fileName(layoutName: String): String =
        layoutName.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().ifEmpty { "layout" } + ".controlpad.json"

    /** 파일 이름으로 쓸 수 있는 사진 이름인지(경로를 벗어나는 이름을 막는다). */
    fun validImageName(name: String): Boolean = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}$").matches(name)

    /** readImage 로 읽을 수 있는 사진은 파일 안에 함께 넣는다(다른 기기에서도 보이게). */
    fun export(l: PadLayout, readImage: (String) -> ByteArray? = { null }): String {
        val images = JSONObject()
        for (name in l.buttons.map { it.image }.filter { it.isNotEmpty() }.toSet()) {
            val bytes = readImage(name) ?: continue
            images.put(name, java.util.Base64.getEncoder().encodeToString(bytes))
        }
        return l.toJson().put("format", FORMAT).put("version", 1).put("images", images).toString(2)
    }

    /**
     * 파일 내용을 레이아웃으로 바꾼다. 형식이 다르면 예외를 던진다.
     * 이미 있는 레이아웃을 덮어쓰지 않도록 항상 새 id 를 준다.
     */
    fun import(text: String, saveImage: (String, ByteArray) -> Unit = { _, _ -> }): PadLayout {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT && o.has("buttons"))
        val l = PadLayout.fromJson(o.put("id", UUID.randomUUID().toString()))
        val images = o.optJSONObject("images")
        for (b in l.buttons) if (!validImageName(b.image)) b.image = ""
        if (images != null) for (name in images.keys()) {
            if (!validImageName(name)) continue
            runCatching { saveImage(name, java.util.Base64.getDecoder().decode(images.getString(name))) }
        }
        // 손으로 고친 파일의 엉뚱한 좌표가 화면 밖 버튼을 만들지 않게 한다.
        for (b in l.buttons) {
            b.w = b.w.coerceIn(0.01f, 1f); b.h = b.h.coerceIn(0.01f, 1f)
            b.x = b.x.coerceIn(0f, 1f - b.w); b.y = b.y.coerceIn(0f, 1f - b.h)
        }
        if (l.name.isBlank()) l.name = "불러온 레이아웃"
        return l
    }
}

/** 조이스틱: 기울인 위치를 방향 키로 바꾼다. */
object Stick {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 4
    const val RIGHT = 8
    const val DEAD_ZONE = 0.28f     // 반지름 대비 이만큼 안쪽은 입력 없음

    val ARROWS = listOf(0x52, 0x51, 0x50, 0x4F)    // ↑ ↓ ← →
    val WASD = listOf(0x1A, 0x16, 0x04, 0x07)      // W S A D
    val BITS = listOf(UP, DOWN, LEFT, RIGHT)       // keys 의 순서와 같다

    /**
     * 중심에서 (dx, dy) 만큼 기울였을 때 눌러야 할 방향들(UP/DOWN/LEFT/RIGHT 의 조합).
     * dx, dy 는 반지름을 1 로 본 값이고 dy 는 아래쪽이 양수다. diagonal 이 거짓이면 항상 한 방향만 고른다.
     */
    fun directions(dx: Float, dy: Float, diagonal: Boolean): Int {
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) return 0
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))   // 0 = 오른쪽, 90 = 아래
        val step = if (diagonal) 45.0 else 90.0
        val sector = Math.floorMod(Math.round(angle / step).toInt(), (360 / step).toInt())
        val eight = if (diagonal) sector else sector * 2
        return when (eight) {
            0 -> RIGHT; 1 -> RIGHT or DOWN; 2 -> DOWN; 3 -> DOWN or LEFT
            4 -> LEFT; 5 -> LEFT or UP; 6 -> UP; else -> UP or RIGHT
        }
    }

    /** 방향 조합에 해당하는 키들. keys 는 위, 아래, 왼쪽, 오른쪽 순서다. */
    fun keysFor(mask: Int, keys: List<Int>): List<Int> =
        BITS.indices.filter { mask and BITS[it] != 0 }.mapNotNull { keys.getOrNull(it) }.filter { it > 0 }
}

/** 여러 버튼을 감싸는 영역을 새 위치·크기로 옮길 때, 각 버튼이 따라가는 계산. */
object Geometry {
    /**
     * starts: 버튼들의 원래 x, y, w, h (화면 대비 비율). box: 옮긴 뒤 전체 영역의 x, y, w, h.
     * 화면 밖으로 나가지 않게 맞춘 뒤, 각 버튼의 새 x, y, w, h 를 돌려준다.
     */
    /** 편집 화면에 보여 줄 위치·크기 글자(화면 대비 %). */
    fun describe(x: Float, y: Float, w: Float, h: Float): String =
        "가로 %.1f%% · 세로 %.1f%% · 너비 %.1f%% · 높이 %.1f%%".format(x * 100, y * 100, w * 100, h * 100)

    fun fit(starts: List<FloatArray>, box: FloatArray): List<FloatArray> {
        val ox = starts.minOf { it[0] }
        val oy = starts.minOf { it[1] }
        val ow = starts.maxOf { it[0] + it[2] } - ox
        val oh = starts.maxOf { it[1] + it[3] } - oy
        val w = box[2].coerceIn(0.01f, 1f)
        val h = box[3].coerceIn(0.01f, 1f)
        val x = box[0].coerceIn(0f, 1f - w)
        val y = box[1].coerceIn(0f, 1f - h)
        val sx = if (ow > 0f) w / ow else 1f
        val sy = if (oh > 0f) h / oh else 1f
        return starts.map { floatArrayOf(x + (it[0] - ox) * sx, y + (it[1] - oy) * sy, it[2] * sx, it[3] * sy) }
    }
}

/** 연타(터보) 버튼의 시간 계산. */
object Turbo {
    /** 1초에 rate 번 누를 때, 한 번 누르고 있는 시간과 떼고 있는 시간(ms). */
    fun timing(rate: Int): Pair<Long, Long> {
        val period = 1000L / rate.coerceIn(PadButton.MIN_RATE, PadButton.MAX_RATE)
        val down = (period / 2).coerceIn(10L, 40L)
        return down to (period - down).coerceAtLeast(5L)
    }
}

object Macro {
    /** 화면에 보여 줄 매크로 설명: "Ctrl + C → Alt + Tab → Ctrl + V". */
    fun describe(steps: List<List<Int>>): String =
        if (steps.isEmpty()) "(단계 없음)" else steps.joinToString(" → ") { Keys.combo(it) }
}

/** 편집 화면의 맞춤·균등 배치. 잠긴 버튼은 건드리지 않는다. */
object Arrange {
    val names = listOf(
        "왼쪽 맞춤", "오른쪽 맞춤", "위 맞춤", "아래 맞춤", "가로 가운데 맞춤", "세로 가운데 맞춤",
        "가로 간격 같게", "세로 간격 같게", "같은 너비", "같은 높이",
    )

    /** 맞춤 종류(앞의 여섯 개)와 균등 배치·같은 크기(나머지)를 가르는 자리. */
    const val ALIGN_COUNT = 6

    fun apply(index: Int, all: List<PadButton>, respectLock: Boolean = true) {
        val bs = all.filter { !respectLock || !it.locked }
        if (bs.size < 2) return
        when (index) {
            0 -> bs.minOf { it.x }.let { v -> bs.forEach { it.x = v } }
            1 -> bs.maxOf { it.x + it.w }.let { v -> bs.forEach { it.x = v - it.w } }
            2 -> bs.minOf { it.y }.let { v -> bs.forEach { it.y = v } }
            3 -> bs.maxOf { it.y + it.h }.let { v -> bs.forEach { it.y = v - it.h } }
            4 -> ((bs.minOf { it.x } + bs.maxOf { it.x + it.w }) / 2).let { c -> bs.forEach { it.x = c - it.w / 2 } }
            5 -> ((bs.minOf { it.y } + bs.maxOf { it.y + it.h }) / 2).let { c -> bs.forEach { it.y = c - it.h / 2 } }
            6 -> spread(bs.sortedBy { it.x }, { it.x }, { it.w }) { b, v -> b.x = v }
            7 -> spread(bs.sortedBy { it.y }, { it.y }, { it.h }) { b, v -> b.y = v }
            // 같은 크기: 맨 처음 선택한 것에 맞추되, 화면 밖으로 나가지 않게 한다.
            8 -> bs.first().w.let { w -> bs.forEach { it.w = w; it.x = it.x.coerceIn(0f, 1f - w) } }
            9 -> bs.first().h.let { h -> bs.forEach { it.h = h; it.y = it.y.coerceIn(0f, 1f - h) } }
        }
    }

    /** 양 끝의 것은 그대로 두고, 그 사이의 간격이 모두 같아지게 놓는다. */
    private fun spread(sorted: List<PadButton>, pos: (PadButton) -> Float, size: (PadButton) -> Float, set: (PadButton, Float) -> Unit) {
        val start = pos(sorted.first())
        val end = pos(sorted.last()) + size(sorted.last())
        val gap = (end - start - sorted.sumOf { size(it).toDouble() }.toFloat()) / (sorted.size - 1)
        var at = start
        for (b in sorted) { set(b, at); at += size(b) + gap }
    }
}

/** 목록 화면: 고정한 것을 위로, 검색어가 있으면 이름에 그 말이 들어간 것만. */
object LayoutList {
    fun arrange(all: List<PadLayout>, query: String, pinFirst: Boolean = true): List<PadLayout> {
        val q = query.trim()
        val found = all.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        return if (pinFirst) found.sortedByDescending { it.pinned } else found
    }
}

/** 실행 화면의 연속 입력(콤보) 세기: 1초 안에 이어서 누르면 올라가고, 끊기면 다시 1부터. */
object Combo {
    const val WINDOW_MS = 1000L
    fun next(current: Int, lastPress: Long, now: Long): Int = if (lastPress != 0L && now - lastPress <= WINDOW_MS) current + 1 else 1
    fun format(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    /** 최근 입력 기록에 하나를 더하고, limit 개를 넘으면 오래된 것부터 버린다. */
    fun remember(history: ArrayDeque<String>, entry: String, limit: Int = 5) {
        if (entry.isEmpty()) return
        history.addLast(entry)
        while (history.size > limit) history.removeFirst()
    }
}

/** 표시 패널에 무엇을 보여 줄지(TYPE_DISPLAY 버튼의 mode 값). */
object DisplayKind {
    const val KEYS = 0       // 지금 눌리고 있는 키
    const val KPS = 1        // 초당 입력 수
    const val COMBO = 2      // 콤보
    const val TOTAL = 3      // 총 입력 수
    const val TIMER = 4      // 실행 시간
    const val BATTERY = 5    // 배터리 · 시각
    const val HISTORY = 6    // 최근 입력 기록
    const val STATUS = 7     // 연결 상태
    const val STOPWATCH = 8  // 스톱워치(눌러서 시작 · 멈춤, 길게 눌러 처음으로)
    const val COUNTDOWN = 9  // 카운트다운 타이머(정한 시간에서 거꾸로 세고, 끝나면 진동)

    val names = listOf("눌리고 있는 키", "초당 입력 수", "콤보", "총 입력 수", "실행 시간", "배터리 · 시각", "최근 입력 기록", "연결 상태",
        "스톱워치", "카운트다운 타이머")

    /** 누를 수 있는 패널(타이머들). */
    fun interactive(kind: Int) = kind == STOPWATCH || kind == COUNTDOWN

    /** 예전 파일이나 엉뚱한 값은 '눌리고 있는 키'로 본다. */
    fun of(b: PadButton): Int = if (b.mode in names.indices) b.mode else KEYS

    /** 편집 화면에서 패널에 적어 둘 안내 글자. */
    fun editorLabel(b: PadButton): String = names[of(b)] + " 표시"

    /** 실행 화면의 숫자 패널 글자. */
    fun value(kind: Int, kps: Int, combo: Int, total: Int, elapsedMs: Long): String = when (kind) {
        KPS -> "$kps 회/초"
        COMBO -> "$combo 콤보"
        TOTAL -> "총 $total"
        TIMER -> Combo.format(elapsedMs)
        else -> ""
    }
}

/**
 * 타이머 패널(스톱워치 · 카운트다운). 카운트다운 시간은 버튼의 rate 값(1~10)이 durations 의 몇 번째인지로 저장한다.
 */
object Timers {
    val durations = listOf(10, 30, 60, 120, 180, 300, 600, 900, 1200, 1800)   // 초
    val names = durations.map { s -> if (s < 60) "${s}초" else "${s / 60}분" }

    /** 패널에 저장된 카운트다운 시간(ms). */
    fun durationMs(b: PadButton): Long = durations[(b.rate - 1).coerceIn(0, durations.lastIndex)] * 1000L

    /** 고른 시간(목록 번호)을 패널에 저장할 값으로. */
    fun rateFor(index: Int): Int = index.coerceIn(0, durations.lastIndex) + 1

    /** 스톱워치 글자: 분:초.1/10초 (한 시간이 넘으면 시:분:초). */
    fun stopwatch(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val s = t / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d.%d".format(s / 60, s % 60, t % 1000 / 100)
    }

    /** 카운트다운 글자: 남은 시간을 분:초로(올림해서 0 이 되는 순간이 끝). */
    fun countdown(remainingMs: Long): String {
        val s = (remainingMs.coerceAtLeast(0) + 999) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** 그림 에디터에서 그린 그림으로 만들 수 있는 요소들. 그림이 그 요소의 면이 된다. */
object SketchKind {
    const val BUTTON = 0
    const val KNOB = 1
    const val STICK = 2
    const val TRACKPAD = 3
    const val MOUSE = 4
    const val SHOW = 5
    const val SWITCH = 6
    const val DISPLAY = 7
    const val OPTION = 8

    val names = listOf(
        "일반 버튼", "노브", "조이스틱", "트랙패드", "가상 마우스", "표시 버튼 (그룹)", "레이아웃 전환 버튼", "표시 패널", "옵션 버튼",
    )

    fun create(kind: Int, x: Float, y: Float, w: Float, h: Float, image: String, color: Int, freeGroup: Int): PadButton {
        val b = PadButton(x, y, w, h, shape = PadButton.SHAPE_FREE, image = image, color = color)
        convert(b, kind, freeGroup)
        if (kind == KNOB) b.knobOut = PadButton.OUT_MOUSE_X
        return b
    }

    /** 지금 종류(위 번호). 사진은 바꿀 대상이 아니라 -1. */
    fun of(b: PadButton): Int = when (b.type) {
        PadButton.TYPE_BUTTON -> BUTTON
        PadButton.TYPE_KNOB -> KNOB
        PadButton.TYPE_STICK -> STICK
        PadButton.TYPE_TRACKPAD -> TRACKPAD
        PadButton.TYPE_MOUSE -> MOUSE
        PadButton.TYPE_SHOW -> SHOW
        PadButton.TYPE_SWITCH -> SWITCH
        PadButton.TYPE_DISPLAY -> DISPLAY
        PadButton.TYPE_OPTION -> OPTION
        else -> -1
    }

    /**
     * 형변환: 위치·크기·색·이름·그룹·모양·사진은 그대로 두고 종류만 바꾼다.
     * 새 종류에서 뜻이 달라지는 값(동작 방식, 방향 키 등)은 그 종류의 기본값으로 맞춘다.
     */
    fun convert(b: PadButton, kind: Int, freeGroup: Int) {
        if (kind == of(b)) return
        when (kind) {
            BUTTON -> { b.type = PadButton.TYPE_BUTTON; b.mode = PadButton.MODE_HOLD }
            KNOB -> { b.type = PadButton.TYPE_KNOB; b.mode = PadButton.KNOB_UNTIL_STOP; if (b.keys.isEmpty()) b.knobOut = PadButton.OUT_MOUSE_X else b.knobOut = PadButton.OUT_KEYS }
            STICK -> { b.type = PadButton.TYPE_STICK; b.mode = PadButton.STICK_8WAY; if (b.keys.size != 4) b.keys = Stick.ARROWS.toMutableList() }
            TRACKPAD -> { b.type = PadButton.TYPE_TRACKPAD; b.mode = PadButton.PAD_TAP }
            MOUSE -> b.type = PadButton.TYPE_MOUSE
            SHOW -> { b.type = PadButton.TYPE_SHOW; b.mode = PadButton.MODE_TOGGLE; b.target = freeGroup; if (b.group == freeGroup) b.group = 0 }
            SWITCH -> b.type = PadButton.TYPE_SWITCH
            DISPLAY -> { b.type = PadButton.TYPE_DISPLAY; b.mode = DisplayKind.KEYS }
            OPTION -> { b.type = PadButton.TYPE_OPTION; if (Options.all.none { it.id == b.link }) b.link = "haptic" }
        }
    }
}

/** 버튼 모양 고르기. 그린 모양(SHAPE_FREE)은 그림 에디터로만 만든다. */
object Shapes {
    val ids = listOf(PadButton.SHAPE_RECT, PadButton.SHAPE_OVAL, PadButton.SHAPE_DIAMOND, PadButton.SHAPE_HEX, PadButton.SHAPE_SQUARE)
    val names = listOf("둥근 사각형", "원형", "다이아몬드", "육각형", "각진 사각형")

    /** 모양을 바꿀 수 있는 종류인지. 노브·조이스틱은 늘 원형이고, 마우스·표시 패널·사진은 제 모양이 있다. */
    fun applies(b: PadButton): Boolean =
        b.type == PadButton.TYPE_BUTTON || b.isShow || b.isSwitch || b.isOption || b.isPad
}

/** 목록 정렬. 기본은 저장된 순서(LayoutStore.list 가 돌려주는 순서). */
object ListSort {
    val names = listOf("저장 순", "이름순", "최근 수정순")

    fun apply(all: List<PadLayout>, mode: Int): List<PadLayout> = when (mode) {
        1 -> all.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.KOREAN)) { it.name })
        2 -> all.reversed()       // 저장 순은 오래된 것부터라, 뒤집으면 최근에 고친 것부터
        else -> all
    }
}

/** 편집 화면 격자 크기: 화면 가로를 몇 칸으로 나눌지. */
object GridSize {
    val divisions = listOf(24, 48, 96)
    val names = listOf("넓게", "보통", "촘촘")
}

/** 화면 방향 설정(편집·실행 화면). */
object ScreenOrientation {
    val names = listOf("가로 (자동 회전)", "세로 (자동 회전)", "자동")
    val values = listOf(
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
    )
}

/** 레이아웃 구성 표시(실험실 기능): 종류별 개수. */
object LayoutKinds {
    fun describe(l: PadLayout): String {
        val names = linkedMapOf(
            "버튼" to l.buttons.count { it.type == PadButton.TYPE_BUTTON },
            "노브" to l.buttons.count { it.isKnob }, "조이스틱" to l.buttons.count { it.isStick },
            "트랙패드" to l.buttons.count { it.isPad }, "마우스" to l.buttons.count { it.isMouse },
            "패널" to l.buttons.count { it.isDisplay }, "사진" to l.buttons.count { it.isImage },
        )
        return names.filter { it.value > 0 }.entries.joinToString(" · ") { "${it.key} ${it.value}" }.ifEmpty { "비어 있음" }
    }
}
