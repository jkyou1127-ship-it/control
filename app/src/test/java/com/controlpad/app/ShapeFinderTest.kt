package com.controlpad.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeFinderTest {
    /** 흰 바탕(240)에 검은 칠한 네모 두 개와, 선으로만 그린 동그라미 하나. */
    private fun sample(w: Int = 120, h: Int = 80): IntArray {
        val lum = IntArray(w * h) { 240 }
        fun rect(l: Int, t: Int, r: Int, b: Int) { for (y in t..b) for (x in l..r) lum[y * w + x] = 20 }
        rect(10, 10, 30, 30)
        rect(50, 12, 75, 28)
        // 동그라미 테두리(두께 2)
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot((x - 95).toDouble(), (y - 55).toDouble())
            if (d in 13.0..15.0) lum[y * w + x] = 30
        }
        return lum
    }

    @Test
    fun findsFilledShapesAndFillsOutlinedOnes() {
        val r = ShapeFinder.find(sample(), 120, 80)
        assertEquals(3, r.shapes.size)
        // 위 줄 왼쪽부터 번호: 1 = 왼쪽 네모, 2 = 오른쪽 네모, 3 = 아래 동그라미
        assertEquals(1, r.labels[20 * 120 + 20])
        assertEquals(2, r.labels[20 * 120 + 60])
        // 선으로 그린 동그라미의 안쪽도 버튼(채워짐)
        assertEquals(3, r.labels[55 * 120 + 95])
        assertEquals(0, r.labels[70 * 120 + 5])          // 바탕
    }

    @Test
    fun darkBackgroundWithLightShapesAlsoWorks() {
        val lum = sample().map { 255 - it }.toIntArray()
        assertEquals(3, ShapeFinder.find(lum, 120, 80).shapes.size)
    }

    @Test
    fun tinySpecksAreIgnoredAndKeysAreAssigned() {
        val lum = sample()
        lum[75 * 120 + 3] = 0                             // 점 하나
        assertEquals(3, ShapeFinder.find(lum, 120, 80, minAreaFrac = 0.004f).shapes.size)
        assertEquals(0x04, ShapeFinder.keyFor(0))         // A
        assertEquals(0x16, ShapeFinder.keyFor(1))         // S
        assertEquals(ShapeFinder.keyFor(0), ShapeFinder.keyFor(ShapeFinder.defaultKeys.size))
    }

    @Test
    fun watermarkLastsThreeBuildsAndSurvivesSaving() {
        val l = PadLayout(name = "p", watermarkUntil = 100 + Watermark.BUILDS)
        assertTrue(Watermark.shown(l, 100))
        assertTrue(Watermark.shown(l, 102))
        assertFalse(Watermark.shown(l, 103))              // 만든 빌드 + 3 부터는 사라진다
        assertFalse(Watermark.shown(PadLayout(name = "plain"), 1))
        assertEquals(103, PadLayout.fromJson(JSONObject(l.toJson().toString())).watermarkUntil)
        assertEquals(103, Mirror.flip(l).watermarkUntil)
        assertEquals("make by IMCP", Watermark.TEXT)
    }
}
