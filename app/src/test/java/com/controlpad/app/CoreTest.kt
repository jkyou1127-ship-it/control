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
        s.press(listOf(KeyState.MAX_USAGE))         // 비트맵의 마지막 키
        assertEquals(0x80, s.report()[KeyState.REPORT_SIZE - 1].toInt() and 0xFF)
        s.press(listOf(Keys.HANGUL))                // 한/영: 0x90 → 바이트 19 의 bit 0
        assertEquals(0x81, s.report()[19].toInt() and 0xFF)
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
        // 수정키 8비트 + 비트맵 (MAX_USAGE+1)비트 = 리포트 전체
        assertEquals(KeyState.REPORT_SIZE * 8, 8 + KeyState.MAX_USAGE + 1)
        assertTrue(Keys.groups.flatMap { it.second }.all { it.usage <= KeyState.MAX_USAGE || Keys.isModifier(it.usage) || Keys.isMouse(it.usage) })
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
        for (i in 1 until Templates.names.size) for (b in Templates.create(i, "t").buttons) {
            assertTrue(b.x >= 0 && b.y >= 0 && b.x + b.w <= 1.0001f && b.y + b.h <= 1.0001f)
            assertTrue(b.isPad || b.keys.isNotEmpty())
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

    @Test
    fun knobDeltaTakesShortWayAcrossWrap() {
        assertEquals(20f, Knob.delta(170f, -170f), 1e-4f)    // 180도 경계를 시계 방향으로 넘음
        assertEquals(-20f, Knob.delta(-170f, 170f), 1e-4f)
        assertEquals(5f, Knob.delta(10f, 15f), 1e-4f)
        // 화면에서 오른쪽(0도) → 아래(90도)는 시계 방향
        assertEquals(90f, Knob.delta(Knob.angle(1f, 0f), Knob.angle(0f, 1f)), 1e-3f)
    }

    @Test
    fun knobSurvivesJsonRoundTripAndOldFilesLoadAsButtons() {
        val k = Templates.create(4, "sdvx").buttons.first { it.isKnob }.apply { sens = 7f; invert = true }
        val back = PadButton.fromJson(JSONObject(k.toJson().toString()))
        assertTrue(back.isKnob)
        assertEquals(PadButton.OUT_KEYS, back.knobOut)
        assertEquals(listOf(0x14), back.keys)
        assertEquals(listOf(0x1A), back.keys2)
        assertEquals(7f, back.sens, 0f)
        assertTrue(back.invert)
        assertFalse(back.contains(back.x + 0.001f, back.y + 0.001f))   // 노브는 항상 원형

        val old = PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1,"keys":[4]}"""))
        assertFalse(old.isKnob)
        assertEquals(4f, old.sens, 0f)
    }

    @Test
    fun mouseButtonsAreSeparateFromKeyboardReport() {
        val s = KeyState()
        s.press(listOf(Keys.MOUSE_LEFT, 0x04))
        s.press(listOf(Keys.MOUSE_RIGHT))
        assertEquals(0b011, s.mouseButtons())
        assertEquals(1 shl 4, s.report()[1].toInt())          // 키보드 리포트에는 A 만
        assertEquals(0, s.report().drop(2).sumOf { it.toInt() })
        s.release(listOf(Keys.MOUSE_LEFT, 0x04))
        assertEquals(0b010, s.mouseButtons())
        s.clear()
        assertEquals(0, s.mouseButtons())
    }

    @Test
    fun trackpadRoundTripsAndIsRectangular() {
        val t = Templates.create(5, "pad").buttons.first { it.isPad }.apply { mode = PadButton.PAD_NO_TAP }
        val back = PadButton.fromJson(JSONObject(t.toJson().toString()))
        assertTrue(back.isPad)
        assertEquals(PadButton.PAD_NO_TAP, back.mode)
        assertTrue(back.contains(back.x + 0.001f, back.y + 0.001f))
    }

    @Test
    fun mousePartsCoverButtonsWheelAndBody() {
        assertEquals(MousePart.LEFT, MousePart.of(0.1f, 0.1f))
        assertEquals(MousePart.WHEEL, MousePart.of(0.5f, 0.2f))
        assertEquals(MousePart.RIGHT, MousePart.of(0.9f, 0.39f))
        assertEquals(MousePart.BODY, MousePart.of(0.5f, 0.4f))
        assertEquals(MousePart.BODY, MousePart.of(0.05f, 0.95f))
        val m = PadButton(0f, 0f, 0.2f, 0.5f, type = PadButton.TYPE_MOUSE)
        assertTrue(PadButton.fromJson(JSONObject(m.toJson().toString())).isMouse)
    }

    @Test
    fun restartButtonsUseTheGameKeys() {
        assertEquals("R Alt + Enter + Space", Keys.combo(Restart.button(0, 0f, 0f, 0.1f, 0.1f).keys))
        assertEquals("F5", Keys.combo(Restart.button(1, 0f, 0f, 0.1f, 0.1f).keys))
        assertEquals(Restart.SDVX, Templates.create(4, "t").buttons.first { it.label == "RESTART" }.keys)
        for (i in 1..2) assertEquals(Restart.F5, Templates.create(i, "t").buttons.first { it.label == "RESTART" }.keys)
    }

    @Test
    fun builtInThemesAreDistinctAndReadable() {
        assertEquals(14, Themes.all.size)
        assertEquals(Themes.all.size, Themes.all.map { it.name }.toSet().size)
        for (t in Themes.all) {
            // 글자가 배경과 충분히 구분되어야 하고, 밝은 테마 표시는 실제 배경 밝기와 맞아야 한다.
            assertTrue(t.name, kotlin.math.abs(Themes.luminance(t.text) - Themes.luminance(t.bg)) > 0.45f)
            assertEquals(t.name, t.light, Themes.luminance(t.bg) > 0.5f)
        }
    }

    @Test
    fun customThemePicksLightOrDarkFromBackground() {
        val dark = Themes.custom(0xFF101020.toInt(), 0xFFFFFFFF.toInt(), 1f, 2f, false)
        assertFalse(dark.light)
        assertEquals(0xFF000000.toInt(), dark.base)
        val light = Themes.custom(0xFFF0F0F0.toInt(), 0xFF000000.toInt(), 1f, 2f, true)
        assertTrue(light.light)
        assertEquals(0xFFFFFFFF.toInt(), light.base)
        assertTrue(light.colorText)
        // 목록 항목 배경은 배경색에서 글자색 쪽으로 조금 이동한 색
        assertTrue(Themes.luminance(dark.card) > Themes.luminance(dark.bg))
        assertTrue(Themes.luminance(light.card) < Themes.luminance(light.bg))
        assertEquals(0xFF7F7F7F.toInt(), Themes.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun pressedListsModifiersThenKeysThenMouse() {
        val s = KeyState()
        s.press(listOf(Keys.MOUSE_LEFT, 0x16, 0xE1, 0xE0))
        assertEquals("Ctrl + Shift + S + 좌클릭", Keys.combo(s.pressed()))
        s.clear()
        assertTrue(s.pressed().isEmpty())
    }

    @Test
    fun knobIndicatorSettlesToOriginTheShortWay() {
        // 두 바퀴 반 돌린 뒤에는 반 바퀴만 되돌아가면 된다.
        assertEquals(180f, kotlin.math.abs(Knob.delta(0f, 900f)), 1e-3f)
        assertEquals(-90f, Knob.delta(0f, 270f), 1e-3f)
        var a = 170f
        var steps = 0
        while (a != 0f) { a = Knob.settle(a); steps++ }
        assertTrue(steps in 5..30)        // 16ms 간격으로 약 0.1~0.5초
        assertEquals(0f, Knob.settle(-1.2f), 0f)
    }

    @Test
    fun groupsRoundTripAndOldFilesAreAlwaysVisible() {
        val show = PadButton(0f, 0f, 0.1f, 0.1f, type = PadButton.TYPE_SHOW, target = 3, group = 2)
        val back = PadButton.fromJson(JSONObject(show.toJson().toString()))
        assertTrue(back.isShow)
        assertEquals(3, back.target)
        assertEquals(2, back.group)
        assertEquals(2, back.copy().group)

        val old = PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1,"keys":[4]}"""))
        assertEquals(0, old.group)
        assertFalse(old.isShow)
        val bad = PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1,"group":99,"target":0}"""))
        assertEquals(PadButton.GROUPS, bad.group)     // 범위를 벗어난 값은 배열 밖을 가리키지 않게 맞춘다
        assertEquals(1, bad.target)
    }

    @Test
    fun perButtonColoursRoundTripAndDefaultToZero() {
        val b = PadButton(0f, 0f, 0.1f, 0.1f, textColor = 0xFF112233.toInt(), pressColor = 0xFFFFEE00.toInt())
        val back = PadButton.fromJson(JSONObject(b.toJson().toString()))
        assertEquals(0xFF112233.toInt(), back.textColor)
        assertEquals(0xFFFFEE00.toInt(), back.copy().pressColor)
        val old = PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1}"""))
        assertEquals(0, old.textColor)
        assertEquals(0, old.pressColor)
    }

    @Test
    fun timedHoldDurationParsesAndStaysInRange() {
        assertEquals(1500, PadButton.parseHold("1.5"))
        assertEquals(2500, PadButton.parseHold(" 2,5 "))
        assertEquals(1000, PadButton.parseHold(""))
        assertEquals(PadButton.MIN_HOLD_MS, PadButton.parseHold("0"))
        assertEquals(PadButton.MAX_HOLD_MS, PadButton.parseHold("9999"))
        val b = PadButton(0f, 0f, 0.1f, 0.1f, mode = PadButton.MODE_TIMED, holdMs = 3000)
        assertEquals(3000, PadButton.fromJson(JSONObject(b.toJson().toString())).copy().holdMs)
        assertEquals(1000, PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1}""")).holdMs)
    }

    @Test
    fun keypadHasAllKeysWithoutOverlap() {
        for (numpad in listOf(true, false)) {
            val keys = Keypad.create(numpad, 0.3f, 0.05f, 0.4f, 0.9f)
            assertEquals(17, keys.size)
            assertEquals(('0'..'9').map { it.toString() }, keys.map { it.label }.filter { it[0].isDigit() }.sorted())
            for (b in keys) {
                assertTrue(b.label, b.x >= 0.3f - 1e-4f && b.x + b.w <= 0.7f + 1e-4f && b.y >= 0.05f - 1e-4f && b.y + b.h <= 0.95f + 1e-4f)
                assertTrue(b.keys.all { it <= KeyState.MAX_USAGE || Keys.isModifier(it) })
            }
            for (a in keys) for (b in keys) if (a !== b) {
                assertFalse(a.label + "/" + b.label, a.x < b.x + b.w - 1e-4f && b.x < a.x + a.w - 1e-4f && a.y < b.y + b.h - 1e-4f && b.y < a.y + a.h - 1e-4f)
            }
        }
        assertEquals("Num 5", Keys.combo(Keypad.create(true, 0f, 0f, 1f, 1f).first { it.label == "5" }.keys))
        assertEquals("5", Keys.combo(Keypad.create(false, 0f, 0f, 1f, 1f).first { it.label == "5" }.keys))
        assertEquals("Shift + =", Keys.combo(Keypad.create(false, 0f, 0f, 1f, 1f).first { it.label == "+" }.keys))
    }

    @Test
    fun keyboardHasEveryLetterAndRowsFillTheWidth() {
        val keys = Keyboard.create(false)
        assertEquals(('A'..'Z').map { it.toString() }, keys.map { it.label }.filter { it.length == 1 && it[0] in 'A'..'Z' }.sorted())
        assertEquals("Q", keys.first { it.keys == listOf(0x14) }.label)
        assertEquals(listOf(0xE1, 0xE5), keys.filter { it.label == "Shift" }.map { it.keys[0] })
        // 여섯 줄 모두 오른쪽 끝이 같은 곳에서 끝난다.
        val ends = keys.groupBy { it.y }.values.map { row -> row.maxOf { it.x + it.w } }
        assertEquals(6, ends.size)
        for (e in ends) assertEquals(ends[0], e, 1e-3f)
        for (a in keys) for (b in keys) if (a !== b) {
            assertFalse(a.label + "/" + b.label, a.x < b.x + b.w - 1e-4f && b.x < a.x + a.w - 1e-4f && a.y < b.y + b.h - 1e-4f && b.y < a.y + a.h - 1e-4f)
        }
        assertEquals(keys.size, keys.map { it.keys[0] }.toSet().size)     // 같은 키가 두 번 들어가지 않는다
        assertEquals(keys.size, Templates.create(7, "kb").buttons.size)

        // 한글 키보드: 같은 자리의 키는 같은 입력이고, 한글 상태용 이름(자모)이 따로 붙는다.
        val ko = Keyboard.create(true)
        assertEquals("Q" to "ㅂ", ko.first { it.keys == listOf(0x14) }.let { it.label to it.label2 })
        assertEquals("ㅁ", ko.first { it.keys == listOf(0x04) }.label2)
        assertEquals("ㅡ", ko.first { it.keys == listOf(0x10) }.label2)
        assertEquals(26, ko.count { it.label2.length == 1 && it.label2[0] in 'ㄱ'..'ㅣ' })
        assertTrue(keys.all { it.label2.isEmpty() })                       // 영문 키보드는 바뀌지 않는다
        assertEquals("ㅂ", PadButton.fromJson(JSONObject(ko.first { it.label == "Q" }.toJson().toString())).copy().label2)
        assertEquals(listOf(Keys.HANGUL), ko.first { it.label == "한/영" }.keys)
        assertEquals(listOf(Keys.HANJA), ko.first { it.label == "한자" }.keys)
        assertTrue(keys.none { it.keys[0] == Keys.HANGUL })
        val koEnds = ko.groupBy { it.y }.values.map { row -> row.maxOf { it.x + it.w } }
        for (e in koEnds) assertEquals(ends[0], e, 1e-3f)
        assertEquals(ko.size, Templates.create(8, "kb").buttons.size)
    }

    @Test
    fun discoveryReplyCarriesPcName() {
        val reply = "CR".toByteArray() + byteArrayOf(1) + "거실-PC".toByteArray()
        assertEquals("거실-PC", Discovery.parseReply(reply, reply.size))
        assertEquals("", Discovery.parseReply(reply, 3))
        assertEquals(null, Discovery.parseReply("CA".toByteArray() + ByteArray(4), 6))     // 키 응답은 아님
        assertEquals(null, Discovery.parseReply(reply, 2))
        assertArrayEquals(byteArrayOf(0x43, 0x44, 1), Discovery.QUERY)
    }

    @Test
    fun groupResizeScaleStaysWithinLimits() {
        // 0.2 에서 시작해 폭 0.4 인 영역의 끝을 0.8 까지 끌면 1.5배
        assertEquals(1.5f, Resize.scale(0.2f, 0.4f, 0.8f, 0.1f, 0.05f), 1e-4f)
        // 화면 밖으로는 나가지 않는다: (1 - 0.2) / 0.4 = 2배까지
        assertEquals(2f, Resize.scale(0.2f, 0.4f, 5f, 0.1f, 0.05f), 1e-4f)
        // 가장 작은 버튼이 최소 크기보다 작아지지 않는다: 0.05 / 0.1 = 0.5배까지
        assertEquals(0.5f, Resize.scale(0.2f, 0.4f, 0f, 0.1f, 0.05f), 1e-4f)
        // 이미 최소보다 작은 버튼이 있으면 줄이지 않는다
        assertEquals(1f, Resize.scale(0.2f, 0.4f, 0f, 0.02f, 0.05f), 1e-4f)
    }

    @Test
    fun layoutFileRoundTripsWithNewIdAndRejectsOtherFiles() {
        val l = Templates.create(4, "내 볼텍스").apply { slide = true }
        val back = LayoutFile.import(LayoutFile.export(l))
        assertEquals("내 볼텍스", back.name)
        assertTrue(back.slide)
        assertEquals(l.buttons.size, back.buttons.size)
        assertEquals(l.buttons.map { it.keys }, back.buttons.map { it.keys })
        assertTrue(back.id != l.id)                     // 원래 것을 덮어쓰지 않는다

        for (bad in listOf("hello", "{}", """{"buttons":[]}""", """{"format":"other","buttons":[]}""")) {
            assertTrue(bad, runCatching { LayoutFile.import(bad) }.isFailure)
        }
        val wild = LayoutFile.import("""{"format":"controlpad-layout","buttons":[{"x":5,"y":-3,"w":9,"h":0}]}""")
        val b = wild.buttons[0]
        assertTrue(b.x >= 0f && b.y >= 0f && b.x + b.w <= 1f && b.y + b.h <= 1f && b.h > 0f)
        assertEquals("불러온 레이아웃", wild.name)
        assertEquals("a_b_c.controlpad.json", LayoutFile.fileName("a/b:c"))
        assertEquals("layout.controlpad.json", LayoutFile.fileName("  "))
    }
}
