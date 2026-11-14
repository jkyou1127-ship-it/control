package com.controlpad.app

/**
 * 현재 눌린 키 집합. 같은 키를 여러 버튼이 동시에 누를 수 있으므로 참조 횟수로 센다.
 *
 * 리포트 형식(16바이트, 블루투스 HID 와 UDP 가 같이 쓴다):
 *   [0]      수정키 비트(usage 0xE0~0xE7 → bit 0~7)
 *   [1..15]  usage 0x00~0x77 비트맵 (usage u → 바이트 1+u/8 의 bit u%8)
 */
class KeyState {
    private val counts = IntArray(256)

    fun press(usages: List<Int>) {
        for (u in usages) if (u in 0..255) counts[u]++
    }

    fun release(usages: List<Int>) {
        for (u in usages) if (u in 0..255 && counts[u] > 0) counts[u]--
    }

    fun clear() = counts.fill(0)

    fun report(): ByteArray {
        val r = ByteArray(REPORT_SIZE)
        for (i in 0..7) if (counts[0xE0 + i] > 0) r[0] = (r[0].toInt() or (1 shl i)).toByte()
        for (u in 0..MAX_USAGE) if (counts[u] > 0) {
            val idx = 1 + u / 8
            r[idx] = (r[idx].toInt() or (1 shl (u % 8))).toByte()
        }
        return r
    }

    companion object {
        const val REPORT_SIZE = 16
        const val MAX_USAGE = 0x77
    }
}
