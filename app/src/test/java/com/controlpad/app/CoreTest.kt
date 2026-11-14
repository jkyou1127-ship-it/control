package com.controlpad.app

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun reportHasModifierBitsAndKeyBitmap() {
        val s = KeyState()
        s.press(listOf(0xE0, 0xE1, 0x04))          // Ctrl + Shift + A
        val r = s.report()
        assertEquals(KeyState.REPORT_SIZE, r.size)
        assertEquals(0b11, r[0].toInt())
        assertEquals(1 shl 4, r[1].toInt())
        s.press(listOf(0x77))                       // 비트맵의 마지막 키
        assertEquals(0x80, s.report()[15].toInt() and 0xFF)
    }

    @Test
    fun sharedKeyStaysDownUntilAllButtonsRelease() {
        val s = KeyState()
        s.press(listOf(0x07)); s.press(listOf(0x07))
        s.release(listOf(0x07))
        assertEquals(1 shl 7, s.report()[1].toInt() and 0xFF)
        s.release(listOf(0x07)); s.release(listOf(0x07))   // 여분의 release 는 무시
        assertArrayEquals(ByteArray(KeyState.REPORT_SIZE), s.report())
    }

    @Test
    fun descriptorMatchesReportSize() {
        // 수정키 8비트 + 비트맵 (MAX_USAGE+1)비트 = 16바이트
        assertEquals(KeyState.REPORT_SIZE * 8, 8 + KeyState.MAX_USAGE + 1)
        assertTrue(Keys.groups.flatMap { it.second }.all { it.usage <= KeyState.MAX_USAGE || Keys.isModifier(it.usage) })
    }

    @Test
    fun keyCatalogHasNoDuplicates() {
        val all = Keys.groups.flatMap { it.second }
        assertEquals(all.size, all.map { it.usage }.toSet().size)
        assertEquals("Ctrl + Shift + S", Keys.combo(Keys.ordered(listOf(0x16, 0xE1, 0xE0))))
    }

    @Test
    fun layoutSurvivesJsonRoundTrip() {
        val l = Templates.create(3, "단축키").apply { slide = true }
        val back = PadLayout.fromJson(JSONObject(l.toJson().toString()))
        assertEquals(l.id, back.id)
        assertEquals("단축키", back.name)
        assertTrue(back.slide)
        assertEquals(8, back.buttons.size)
        assertEquals(listOf(0xE0, 0x06), back.buttons[0].keys)
        assertEquals(PadButton.MODE_TAP, back.buttons[0].mode)
        assertEquals(l.buttons[5].x, back.buttons[5].x, 1e-6f)
    }

    @Test
    fun templatesStayOnScreen() {
        for (i in 1..3) for (b in Templates.create(i, "t").buttons) {
            assertTrue(b.x >= 0 && b.y >= 0 && b.x + b.w <= 1.0001f && b.y + b.h <= 1.0001f)
            assertTrue(b.keys.isNotEmpty())
        }
    }

    @Test
    fun ovalHitTestExcludesCorners() {
        val b = PadButton(0f, 0f, 0.2f, 0.2f, shape = PadButton.SHAPE_OVAL)
        assertTrue(b.contains(0.1f, 0.1f))
        assertFalse(b.contains(0.01f, 0.01f))
        b.shape = PadButton.SHAPE_RECT
        assertTrue(b.contains(0.01f, 0.01f))
    }
}
