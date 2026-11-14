package com.controlpad.app

/** 키 하나. usage 는 USB HID 키보드 usage 코드(수정키는 0xE0~0xE7). */
data class KeyDef(val usage: Int, val name: String)

object Keys {
    val groups: List<Pair<String, List<KeyDef>>> = listOf(
        "수정키" to listOf(
            KeyDef(0xE0, "Ctrl"), KeyDef(0xE1, "Shift"), KeyDef(0xE2, "Alt"), KeyDef(0xE3, "Win"),
            KeyDef(0xE4, "R Ctrl"), KeyDef(0xE5, "R Shift"), KeyDef(0xE6, "R Alt"),
        ),
        "문자" to ('A'..'Z').mapIndexed { i, c -> KeyDef(0x04 + i, c.toString()) },
        "숫자" to (1..9).map { KeyDef(0x1D + it, it.toString()) } + KeyDef(0x27, "0"),
        "기능키" to (1..12).map { KeyDef(0x39 + it, "F$it") },
        "편집 · 이동" to listOf(
            KeyDef(0x2C, "Space"), KeyDef(0x28, "Enter"), KeyDef(0x29, "Esc"), KeyDef(0x2A, "Bksp"),
            KeyDef(0x2B, "Tab"), KeyDef(0x39, "Caps"), KeyDef(0x4C, "Del"), KeyDef(0x49, "Ins"),
            KeyDef(0x4A, "Home"), KeyDef(0x4D, "End"), KeyDef(0x4B, "PgUp"), KeyDef(0x4E, "PgDn"),
            KeyDef(0x50, "←"), KeyDef(0x4F, "→"), KeyDef(0x52, "↑"), KeyDef(0x51, "↓"),
            KeyDef(0x46, "PrtSc"),
        ),
        "기호" to listOf(
            KeyDef(0x2D, "-"), KeyDef(0x2E, "="), KeyDef(0x2F, "["), KeyDef(0x30, "]"),
            KeyDef(0x31, "\\"), KeyDef(0x33, ";"), KeyDef(0x34, "'"), KeyDef(0x35, "`"),
            KeyDef(0x36, ","), KeyDef(0x37, "."), KeyDef(0x38, "/"),
        ),
        "숫자패드" to listOf(
            KeyDef(0x53, "NumLk"), KeyDef(0x54, "Num /"), KeyDef(0x55, "Num *"), KeyDef(0x56, "Num -"),
            KeyDef(0x57, "Num +"), KeyDef(0x58, "Num Ent"),
        ) + (1..9).map { KeyDef(0x58 + it, "Num $it") } + listOf(KeyDef(0x62, "Num 0"), KeyDef(0x63, "Num .")),
        "한글" to listOf(KeyDef(HANGUL, "한/영"), KeyDef(HANJA, "한자")),
        "마우스 버튼" to listOf(KeyDef(MOUSE_LEFT, "좌클릭"), KeyDef(MOUSE_RIGHT, "우클릭"), KeyDef(MOUSE_MIDDLE, "휠클릭")),
    )

    const val HANGUL = 0x90   // HID LANG1
    const val HANJA = 0x91    // HID LANG2

    // 키보드 usage 가 아닌, 앱 안에서만 쓰는 마우스 버튼 번호
    const val MOUSE_LEFT = 0x100
    const val MOUSE_RIGHT = 0x101
    const val MOUSE_MIDDLE = 0x102

    fun isMouse(usage: Int): Boolean = usage in MOUSE_LEFT..MOUSE_MIDDLE

    private val names: Map<Int, String> = groups.flatMap { it.second }.associate { it.usage to it.name }

    fun name(usage: Int): String = names[usage] ?: "0x%02X".format(usage)

    fun isModifier(usage: Int): Boolean = usage in 0xE0..0xE7

    /** 수정키가 앞에 오도록 정렬한다(나머지는 원래 순서 유지). */
    fun ordered(usages: Collection<Int>): List<Int> =
        usages.filter { isModifier(it) }.sorted() + usages.filter { !isModifier(it) }

    fun combo(usages: List<Int>): String = usages.joinToString(" + ") { name(it) }
}
