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
        for (i in 1..6) for (b in Templates.create(i, "t").buttons) {
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
    fun publishedVersionFileMatchesThisBuild() {
        // release/alpha/version.json 이 빌드 버전과 어긋나면 인앱 업데이트가 새 버전을 알아채지 못한다.
        val info = Updater.parse(java.io.File("../release/alpha/version.json").readText())
        // 정식 채널은 알파보다 앞설 수 없다.
        assertTrue(Updater.parse(java.io.File("../release/version.json").readText()).code <= info.code)
        val gradle = java.io.File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = ${info.code}\n"))
        assertTrue(gradle.contains("versionName = \"${info.name}\""))
        assertTrue(info.notes.isNotEmpty())
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
}
