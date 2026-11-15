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
        assertEquals(30, Themes.all.size)
        // 오프셋 기본 프리셋 10개: 이름이 겹치지 않고, 값이 조절 범위 안에 있다
        assertEquals(10, OffsetPresets.all.size)
        assertEquals(10, OffsetPresets.all.map { it.name }.toSet().size)
        assertTrue(OffsetPresets.all.all { kotlin.math.abs(it.x) <= Link.OFFSET_MAX && kotlin.math.abs(it.y) <= Link.OFFSET_MAX && it.delay in 0..Link.DELAY_MAX })
        assertEquals("기본", OffsetPresets.all[0].name)
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

    @Test
    fun knobHoldOptionRoundTrips() {
        val k = Templates.create(4, "sdvx").buttons.first { it.isKnob }
        assertEquals(PadButton.KNOB_UNTIL_STOP, k.mode)               // 기존 노브는 예전처럼 동작
        k.mode = PadButton.KNOB_UNTIL_RELEASE
        assertEquals(PadButton.KNOB_UNTIL_RELEASE, PadButton.fromJson(JSONObject(k.toJson().toString())).copy().mode)
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
    fun knobKeepsKeyHeldOnAnyFurtherTurnInTheSameDirection() {
        assertTrue(Knob.sameWay(1, 0.1f))          // 아주 조금만 더 돌아도 계속 누름
        assertTrue(Knob.sameWay(-1, -0.1f))
        assertFalse(Knob.sameWay(1, -0.1f))        // 반대로 돌면 유지하지 않는다
        assertFalse(Knob.sameWay(0, 5f))           // 아직 아무 키도 누르지 않은 상태
    }

    @Test
    fun stickDirections() {
        val U = Stick.UP; val D = Stick.DOWN; val L = Stick.LEFT; val R = Stick.RIGHT
        assertEquals(0, Stick.directions(0.1f, -0.1f, true))                 // 가운데는 입력 없음
        assertEquals(R, Stick.directions(0.9f, 0f, true))
        assertEquals(U, Stick.directions(0f, -0.9f, true))                   // 화면 위쪽은 dy 가 음수
        assertEquals(D, Stick.directions(0.1f, 0.9f, true))
        assertEquals(L, Stick.directions(-0.9f, 0.1f, true))
        assertEquals(U or R, Stick.directions(0.6f, -0.6f, true))
        assertEquals(D or L, Stick.directions(-0.6f, 0.6f, true))
        // 4방향 전용이면 대각선에서도 한 방향만
        for ((x, y) in listOf(0.6f to -0.5f, -0.5f to 0.6f, 0.7f to 0.69f, -0.6f to -0.61f)) {
            assertEquals(1, Integer.bitCount(Stick.directions(x, y, false)))
        }
        assertEquals(R, Stick.directions(0.6f, -0.5f, false))
        assertEquals(D, Stick.directions(-0.5f, 0.6f, false))

        assertEquals(listOf(0x52, 0x4F), Stick.keysFor(U or R, Stick.ARROWS))
        assertEquals(listOf(0x16, 0x04), Stick.keysFor(D or L, Stick.WASD))
        assertEquals(listOf(0x4F), Stick.keysFor(U or R, listOf(0, 0, 0, 0x4F)))   // 키가 없는 방향은 건너뜀
        val s = PadButton(0f, 0f, 0.2f, 0.2f, keys = Stick.WASD.toMutableList(), type = PadButton.TYPE_STICK, mode = PadButton.STICK_4WAY)
        val back = PadButton.fromJson(JSONObject(s.toJson().toString()))
        assertTrue(back.isStick && back.mode == PadButton.STICK_4WAY && back.keys == Stick.WASD)
        assertFalse(back.contains(0.005f, 0.005f))                                 // 원형
    }

    @Test
    fun displayPanelRoundTrips() {
        val p = PadButton(0.3f, 0f, 0.3f, 0.1f, type = PadButton.TYPE_DISPLAY, textColor = 0xFF00FF00.toInt(), group = 2)
        val back = PadButton.fromJson(JSONObject(p.toJson().toString())).copy()
        assertTrue(back.isDisplay)
        assertEquals(0xFF00FF00.toInt(), back.textColor)
        assertEquals(2, back.group)
        assertFalse(PadButton(0f, 0f, 0.1f, 0.1f).isDisplay)
    }

    @Test
    fun imagesTravelInsideLayoutFilesAndBadNamesAreDropped() {
        val l = PadLayout(name = "사진")
        l.buttons += PadButton(0f, 0f, 1f, 1f, type = PadButton.TYPE_IMAGE, image = "bg.jpg")
        l.buttons += PadButton(0.1f, 0.1f, 0.2f, 0.2f, keys = mutableListOf(4), image = "face.png")
        val files = mapOf("bg.jpg" to byteArrayOf(1, 2, 3), "face.png" to byteArrayOf(9))
        val text = LayoutFile.export(l) { files[it] }

        val saved = HashMap<String, ByteArray>()
        val back = LayoutFile.import(text) { name, bytes -> saved[name] = bytes }
        assertTrue(back.buttons[0].isImage)
        assertEquals("face.png", back.buttons[1].image)
        assertArrayEquals(byteArrayOf(1, 2, 3), saved["bg.jpg"])
        assertArrayEquals(byteArrayOf(9), saved["face.png"])

        // 다른 폴더를 가리키는 이름은 저장하지도, 버튼에 남기지도 않는다.
        val evil = text.replace("bg.jpg", "../../evil.jpg")
        saved.clear()
        val safe = LayoutFile.import(evil) { name, bytes -> saved[name] = bytes }
        assertEquals("", safe.buttons[0].image)
        assertEquals(setOf("face.png"), saved.keys)
        assertFalse(LayoutFile.validImageName(".hidden"))
        assertFalse(LayoutFile.validImageName("a/b.png"))
        assertTrue(LayoutFile.validImageName("3f2a-11.png"))
    }

    @Test
    fun geometryFitMovesAndScalesTogether() {
        val starts = listOf(floatArrayOf(0.2f, 0.2f, 0.1f, 0.1f), floatArrayOf(0.4f, 0.3f, 0.2f, 0.1f))
        // 전체 영역: x 0.2~0.6, y 0.2~0.4 → 너비를 두 배로, 왼쪽으로 0.1 이동
        val out = Geometry.fit(starts, floatArrayOf(0.1f, 0.2f, 0.8f, 0.2f))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.2f, 0.1f), out[0], 1e-5f)
        assertArrayEquals(floatArrayOf(0.5f, 0.3f, 0.4f, 0.1f), out[1], 1e-5f)
        // 화면 밖으로 나가는 값은 안쪽으로 맞춘다
        val clamped = Geometry.fit(listOf(floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f)), floatArrayOf(0.95f, -1f, 0.2f, 5f))[0]
        assertArrayEquals(floatArrayOf(0.8f, 0f, 0.2f, 1f), clamped, 1e-5f)
    }

    @Test
    fun userThemesSurviveJsonAndPickLightOrDark() {
        val t = Themes.make("바다", 0xFF06202A.toInt(), 0xFFDDF6FF.toInt(), 0.8f, 2.5f, true, 0xFF031218.toInt(), 0xFF0C303D.toInt())
        val back = Themes.fromJson(JSONObject(Themes.toJson(t).toString()))
        assertEquals("바다", back.name)
        assertEquals(t.bg, back.bg); assertEquals(t.text, back.text); assertEquals(t.base, back.base); assertEquals(t.card, back.card)
        assertEquals(0.8f, back.strength, 1e-4f); assertEquals(2.5f, back.stroke, 1e-4f)
        assertTrue(back.colorText)
        assertFalse(back.light)
        // 밝은 배경이면 밝은 테마로, 바탕색·목록 배경을 주지 않으면 알아서 정한다
        val day = Themes.fromJson(JSONObject("""{"bg":-1,"text":-16777216}"""))
        assertTrue(day.light)
        assertEquals(0xFFFFFFFF.toInt(), day.base)
        assertEquals("내 테마", day.name)
    }

    @Test
    fun sketchBoundsWrapAllStrokesAndStayOnScreen() {
        val strokes = listOf(floatArrayOf(100f, 50f, 300f, 200f), floatArrayOf(20f, 180f, 60f, 400f))
        assertArrayEquals(floatArrayOf(10f, 40f, 310f, 410f), Sketch.bounds(strokes, 10f, 1000f, 600f), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 20f, 320f, 400f), Sketch.bounds(strokes, 30f, 320f, 400f), 1e-4f)   // 화면 밖으로는 안 나감
        assertEquals(null, Sketch.bounds(emptyList(), 5f, 100f, 100f))
        val free = PadButton(0f, 0f, 0.2f, 0.2f, shape = PadButton.SHAPE_FREE, image = "a.png")
        assertEquals(PadButton.SHAPE_FREE, PadButton.fromJson(JSONObject(free.toJson().toString())).copy().shape)
        assertTrue(free.contains(0.001f, 0.001f))          // 누르는 범위는 그림을 감싸는 사각형
    }

    @Test
    fun changelogStartsWithThisBuildAndHasNoDuplicates() {
        val gradle = java.io.File("build.gradle.kts").readText()
        // 새 버전을 내면서 업데이트 내역을 빠뜨리지 않게 한다.
        assertTrue(gradle.contains("versionName = \"${Changelog.entries.first().version}\""))
        assertEquals(Changelog.entries.size, Changelog.entries.map { it.version }.toSet().size)
        assertTrue(Changelog.entries.all { it.notes.isNotEmpty() })
        assertEquals("BETA 1.0", Changelog.entries.last().version)
        assertTrue(Changelog.text(latestOnly = true).startsWith(Changelog.entries.first().version + "\n  · "))
        assertTrue(Changelog.text().contains("BETA 1.9"))
    }

    @Test
    fun macroTurboAndSwitchRoundTrip() {
        val m = PadButton(0f, 0f, 0.1f, 0.1f, mode = PadButton.MODE_MACRO,
            macro = mutableListOf(mutableListOf(0xE0, 0x06), mutableListOf(0xE2, 0x2B), mutableListOf(0xE0, 0x19)))
        val back = PadButton.fromJson(JSONObject(m.toJson().toString()))
        assertEquals(m.macro, back.macro)
        assertEquals("Ctrl + C → Alt + Tab → Ctrl + V", Macro.describe(back.macro))
        val copy = back.copy()
        copy.macro[0].clear()
        assertEquals(2, back.macro[0].size)                       // 복제본을 고쳐도 원본은 그대로
        assertEquals("(단계 없음)", Macro.describe(emptyList()))

        val t = PadButton(0f, 0f, 0.1f, 0.1f, mode = PadButton.MODE_TURBO, rate = 20)
        assertEquals(20, PadButton.fromJson(JSONObject(t.toJson().toString())).rate)
        assertEquals(PadButton.MAX_RATE, PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1,"rate":999}""")).rate)
        assertEquals(10, PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1}""")).rate)

        val s = PadButton(0f, 0f, 0.1f, 0.1f, type = PadButton.TYPE_SWITCH, link = "abc")
        val sb = PadButton.fromJson(JSONObject(s.toJson().toString()))
        assertTrue(sb.isSwitch)
        assertEquals("abc", sb.copy().link)
    }

    @Test
    fun turboTimingMatchesRate() {
        for (rate in listOf(1, 5, 10, 20, 30)) {
            val (down, up) = Turbo.timing(rate)
            assertTrue(down >= 10 && up >= 5)
            assertEquals(1000L / rate, down + up)                 // 한 번 누르고 떼는 데 걸리는 시간
        }
        assertEquals(Turbo.timing(PadButton.MAX_RATE), Turbo.timing(999))
    }

    @Test
    fun labsMatchThePublishedListAndRequestsAreWellFormed() {
        // 평가단 자동화(scripts/panel.py)는 release/labs.json 에 있는 기능만 정식으로 올린다.
        val published = JSONObject(java.io.File("../release/labs.json").readText())
        val names = published.getJSONArray("features").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(Labs.features.map { it.id }, names)
        assertTrue(Labs.features.all { Regex("^[a-z0-9][a-z0-9-]{0,39}$").matches(it.id) })

        assertEquals(setOf("press-count"), Labs.parseGraduated("""{"graduated":["press-count","unknown-feature"]}"""))
        assertEquals(emptySet<String>(), Labs.parseGraduated("{}"))
        assertEquals("[정식 요청] 빌드 1.21", Labs.buildTitle("1.21"))
        assertEquals("[정식 요청] 실험실 kps", Labs.labTitle("kps"))
        val url = Labs.requestUrl(Labs.buildTitle("1.21"), "본문 & 줄\n바꿈")
        assertTrue(url.startsWith("https://github.com/jkyou1127-ship-it/control/issues/new?title=%5B"))
        assertFalse(url.contains(" ") || url.contains("\n") || url.substringAfter("body=").contains("&"))
    }

    @Test
    fun relayAddressAndAnswersAreHandledSafely() {
        assertEquals("https://relay.example", Labs.parseRelay("""{"relay":" https://relay.example "}"""))
        assertEquals("", Labs.parseRelay("""{"relay":"http://insecure.example"}"""))      // https 가 아니면 쓰지 않는다
        assertEquals("", Labs.parseRelay("{}"))
        assertEquals("", Labs.parseRelay(java.io.File("../release/labs.json").readText()).takeIf { !it.startsWith("https://") } ?: "")

        val body = JSONObject(Labs.relayBody("코드\"1", "build", "1.22"))
        assertEquals("코드\"1", body.getString("code"))
        assertEquals("build", body.getString("kind"))
        assertEquals("1.22", body.getString("target"))

        assertEquals(null, Labs.relayProblem(200, """{"ok":true}"""))
        assertEquals("평가단 코드가 맞지 않습니다", Labs.relayProblem(403, """{"ok":false,"error":"bad_code"}"""))
        assertTrue(Labs.relayProblem(502, """{"ok":false,"error":"github_401"}""")!!.contains("401"))
        assertTrue(Labs.relayProblem(200, "<html>not json</html>") != null)     // 엉뚱한 답을 성공으로 보지 않는다
        assertTrue(Labs.relayProblem(500, "")!!.contains("500"))
    }

    @Test
    fun arrangeAlignsDistributesAndSkipsLocked() {
        fun three() = listOf(PadButton(0.1f, 0.1f, 0.1f, 0.2f), PadButton(0.3f, 0.3f, 0.2f, 0.1f), PadButton(0.7f, 0.2f, 0.1f, 0.1f))
        three().also { Arrange.apply(0, it) }.forEach { assertEquals(0.1f, it.x, 1e-5f) }                 // 왼쪽
        three().also { Arrange.apply(1, it) }.forEach { assertEquals(0.8f, it.x + it.w, 1e-5f) }          // 오른쪽
        three().also { Arrange.apply(3, it) }.forEach { assertEquals(0.4f, it.y + it.h, 1e-5f) }          // 아래
        three().also { Arrange.apply(4, it) }.forEach { assertEquals(0.45f, it.x + it.w / 2, 1e-5f) }     // 가로 가운데

        val spread = three().also { Arrange.apply(6, it) }                                                 // 가로 간격 같게
        assertEquals(0.1f, spread[0].x, 1e-5f)
        assertEquals(0.7f, spread[2].x, 1e-5f)                                                             // 양 끝은 그대로
        assertEquals(spread[1].x - (spread[0].x + spread[0].w), spread[2].x - (spread[1].x + spread[1].w), 1e-5f)

        three().also { Arrange.apply(8, it) }.forEach { assertEquals(0.1f, it.w, 1e-5f) }                 // 같은 너비
        val locked = three().also { it[1].locked = true; Arrange.apply(0, it) }
        assertEquals(0.3f, locked[1].x, 1e-5f)                                                             // 잠긴 것은 그대로
        assertEquals(0.1f, locked[2].x, 1e-5f)
        val unlocked = three().also { it[1].locked = true; Arrange.apply(0, it, respectLock = false) }
        assertEquals(0.1f, unlocked[1].x, 1e-5f)                                                           // 잠금 기능이 꺼져 있으면 함께 맞춘다
        assertEquals(6, Arrange.ALIGN_COUNT)
        assertTrue(Arrange.names.take(Arrange.ALIGN_COUNT).all { it.endsWith("맞춤") })
        val one = listOf(PadButton(0.5f, 0.5f, 0.1f, 0.1f)).also { Arrange.apply(0, it) }
        assertEquals(0.5f, one[0].x, 1e-5f)                                                                // 하나뿐이면 아무것도 안 함
    }

    @Test
    fun newButtonAndLayoutFieldsRoundTripWithSafeDefaults() {
        val b = PadButton(0f, 0f, 0.1f, 0.1f, locked = true, opacity = 40, textScale = 150)
        val back = PadButton.fromJson(JSONObject(b.toJson().toString())).copy()
        assertTrue(back.locked); assertEquals(40, back.opacity); assertEquals(150, back.textScale)
        val old = PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1}"""))
        assertFalse(old.locked); assertEquals(100, old.opacity); assertEquals(100, old.textScale)
        val wild = PadButton.fromJson(JSONObject("""{"x":0,"y":0,"w":0.1,"h":0.1,"opacity":0,"textScale":9999}"""))
        assertEquals(PadButton.MIN_OPACITY, wild.opacity)            // 완전히 안 보이는 버튼은 만들지 않는다
        assertEquals(PadButton.MAX_TEXT_SCALE, wild.textScale)

        val l = PadLayout(name = "즐겨찾기", pinned = true)
        assertTrue(PadLayout.fromJson(JSONObject(l.toJson().toString())).pinned)
        assertFalse(PadLayout.fromJson(JSONObject("""{"id":"a","name":"b"}""")).pinned)
    }

    @Test
    fun layoutListSearchesAndPutsPinnedFirst() {
        val all = listOf(PadLayout(name = "리듬 4키"), PadLayout(name = "작업용 단축키", pinned = true), PadLayout(name = "리듬 7키"))
        assertEquals(listOf("작업용 단축키", "리듬 4키", "리듬 7키"), LayoutList.arrange(all, "").map { it.name })
        assertEquals(listOf("리듬 4키", "리듬 7키"), LayoutList.arrange(all, " 리듬 ").map { it.name })
        assertEquals(emptyList<String>(), LayoutList.arrange(all, "없는 이름").map { it.name })
        // 고정(실험실 기능)이 꺼져 있으면 원래 순서 그대로
        assertEquals(listOf("리듬 4키", "작업용 단축키", "리듬 7키"), LayoutList.arrange(all, "", pinFirst = false).map { it.name })
    }

    @Test
    fun comboCountsOnlyQuickFollowUps() {
        assertEquals(1, Combo.next(0, 0, 5000))                 // 첫 입력
        assertEquals(2, Combo.next(1, 5000, 5600))              // 1초 안에 이어서
        assertEquals(1, Combo.next(7, 5000, 6500))              // 끊기면 다시 1부터
        assertEquals("0:05", Combo.format(5999))
        assertEquals("12:34", Combo.format(754000))
    }

    @Test
    fun historyKeepsTheLastFiveAndGeometryIsReadable() {
        val h = ArrayDeque<String>()
        for (k in listOf("A", "", "S", "D", "F", "J", "K")) Combo.remember(h, k)
        assertEquals(listOf("S", "D", "F", "J", "K"), h.toList())          // 빈 것은 넣지 않고, 오래된 것부터 버린다
        assertEquals("가로 12.5% · 세로 30.0% · 너비 20.0% · 높이 15.0%", Geometry.describe(0.125f, 0.3f, 0.2f, 0.15f))
        assertEquals(Labs.allFeatures.size, Labs.allFeatures.map { it.id }.toSet().size)
    }

    @Test
    fun hiddenLabsFeaturesAreParsedAndRequestTitlesMatchThePanelScript() {
        assertEquals(setOf("press-count"), Labs.parseHidden("""{"hidden":["press-count","unknown"]}"""))
        assertEquals(emptySet<String>(), Labs.parseHidden("""{"graduated":["press-count"]}"""))
        assertTrue(JSONObject(java.io.File("../release/labs.json").readText()).has("hidden"))
        assertEquals("[숨김 요청] 실험실 kps", Labs.hideTitle("kps"))
        assertEquals("[복귀 요청] 실험실 kps", Labs.restoreTitle("kps"))
    }

    @Test
    fun displayPanelKinds() {
        val kps = PadButton(0f, 0f, 0.2f, 0.1f, type = PadButton.TYPE_DISPLAY, mode = DisplayKind.KPS)
        assertEquals(DisplayKind.KPS, DisplayKind.of(PadButton.fromJson(JSONObject(kps.toJson().toString()))))
        assertEquals(DisplayKind.KEYS, DisplayKind.of(PadButton(0f, 0f, 0.2f, 0.1f, type = PadButton.TYPE_DISPLAY)))   // 예전 패널
        assertEquals(DisplayKind.KEYS, DisplayKind.of(PadButton(0f, 0f, 0.2f, 0.1f, type = PadButton.TYPE_DISPLAY, mode = 99)))
        assertEquals("초당 입력 수 표시", DisplayKind.editorLabel(kps))
        assertEquals("12 회/초", DisplayKind.value(DisplayKind.KPS, 12, 0, 0, 0))
        assertEquals("5 콤보", DisplayKind.value(DisplayKind.COMBO, 0, 5, 0, 0))
        assertEquals("총 340", DisplayKind.value(DisplayKind.TOTAL, 0, 0, 340, 0))
        assertEquals("1:05", DisplayKind.value(DisplayKind.TIMER, 0, 0, 0, 65000))
    }

    @Test
    fun sketchCanBecomeEveryFunctionalElement() {
        val made = SketchKind.names.indices.map { SketchKind.create(it, 0.1f, 0.2f, 0.3f, 0.3f, "s.png", 0xFF112233.toInt(), 4) }
        assertTrue(made.all { it.shape == PadButton.SHAPE_FREE && it.image == "s.png" && it.x == 0.1f && it.w == 0.3f })
        assertEquals(PadButton.TYPE_BUTTON, made[SketchKind.BUTTON].type)
        assertTrue(made[SketchKind.KNOB].isKnob)
        assertTrue(made[SketchKind.STICK].isStick && made[SketchKind.STICK].keys == Stick.ARROWS)
        assertTrue(made[SketchKind.TRACKPAD].isPad)
        assertTrue(made[SketchKind.MOUSE].isMouse)
        assertTrue(made[SketchKind.SHOW].isShow && made[SketchKind.SHOW].target == 4)
        assertTrue(made[SketchKind.SWITCH].isSwitch)
        assertTrue(made[SketchKind.DISPLAY].isDisplay)
        // 그린 노브도 그대로 저장·복원된다
        val back = PadButton.fromJson(JSONObject(made[SketchKind.KNOB].toJson().toString()))
        assertTrue(back.isKnob && back.shape == PadButton.SHAPE_FREE)
    }

    @Test
    fun buildRequestsUseTheNameWithoutPrefix() {
        assertEquals("1.00.a", Labs.shortName("NCP#1.00.a"))
        assertEquals("1.41", Labs.shortName("1.41"))
        assertTrue(Regex("^[0-9A-Za-z][0-9A-Za-z. ]{0,19}$").matches(Labs.shortName("NCP#1.00.a")))   // 중계 서버가 받는 모양
    }

    @Test
    fun alphaWideStageIsParsedAndPlansMatchThePanelSchedule() {
        assertEquals(setOf("press-count"), Labs.parseAlpha("""{"alpha":["press-count","unknown"]}"""))
        assertEquals(emptySet<String>(), Labs.parseAlpha("{}"))
        assertTrue(Labs.plan("lab").contains("1~2분 안에"))                  // 정식 추가 건의는 바로 반영
        assertTrue(Labs.plan("build").contains("1~2분 안에"))
        assertTrue(Labs.plan("hide").contains("숨겼습니다") && Labs.plan("restore").contains("되돌렸습니다"))
    }

    @Test
    fun optionButtonsCoverSettingsAndRealLabsFeatures() {
        val ids = Labs.allFeatures.map { it.id }
        assertTrue(Options.labIds.all { it in ids })                       // 없는 실험실 기능을 가리키지 않는다
        val all = Options.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(listOf("haptic", "show-pressed", "key-names", Options.RELEASE_ALL), all.take(4).map { it.id })
        assertEquals(4 + Options.labIds.size, all.size)
        assertEquals("초당 입력 수 표시", Options.title("kps"))
        assertEquals("옵션", Options.title("unknown"))
        val b = PadButton(0f, 0f, 0.1f, 0.1f, type = PadButton.TYPE_OPTION, link = "combo")
        val back = PadButton.fromJson(JSONObject(b.toJson().toString()))
        assertTrue(back.isOption && back.link == "combo")
    }

    @Test
    fun labsFeaturesSplitIntoButtonsAndSettings() {
        val buttons = Labs.allFeatures.filter { it.button }.map { it.id }.toSet()
        assertEquals(
            setOf("list-search", "list-pin", "home-shortcut", "select-all", "align", "distribute", "lock", "opacity", "text-scale", "option-buttons", "nudge"),
            buttons,
        )
        // 옵션 버튼으로 켜고 끄는 것은 모두 설정형이어야 한다(버튼형은 끌 수 없다).
        assertTrue(Options.labIds.none { it in buttons })
        assertEquals(null, Labs.feature("unknown"))
        assertEquals("초당 입력 수 표시", Labs.feature("kps")?.title)
    }

    @Test
    fun everyStatusDisplayHasAPanel() {
        assertEquals(8, DisplayKind.names.size)
        for (k in listOf(DisplayKind.BATTERY, DisplayKind.HISTORY, DisplayKind.STATUS)) {
            val p = PadButton(0f, 0f, 0.2f, 0.1f, type = PadButton.TYPE_DISPLAY, mode = k)
            assertEquals(k, DisplayKind.of(PadButton.fromJson(JSONObject(p.toJson().toString()))))
        }
        assertEquals("최근 입력 기록 표시", DisplayKind.editorLabel(PadButton(0f, 0f, 0.1f, 0.1f, type = PadButton.TYPE_DISPLAY, mode = DisplayKind.HISTORY)))
    }

    @Test
    fun convertKeepsPlacementAndSetsSensibleDefaults() {
        val b = PadButton(0.1f, 0.2f, 0.3f, 0.4f, "점프", mutableListOf(0x2C), color = 0xFF123456.toInt(), group = 2, mode = PadButton.MODE_TAP)
        SketchKind.convert(b, SketchKind.STICK, 5)
        assertTrue(b.isStick); assertEquals(Stick.ARROWS, b.keys)                   // 방향 키 4개가 아니면 방향키로
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), listOf(b.x, b.y, b.w, b.h))
        assertEquals("점프", b.label); assertEquals(2, b.group); assertEquals(0xFF123456.toInt(), b.color)
        SketchKind.convert(b, SketchKind.KNOB, 5)
        assertTrue(b.isKnob); assertEquals(PadButton.OUT_KEYS, b.knobOut); assertEquals(PadButton.KNOB_UNTIL_STOP, b.mode)
        SketchKind.convert(b, SketchKind.SHOW, 2)
        assertTrue(b.isShow); assertEquals(2, b.target); assertEquals(0, b.group)   // 자기가 여는 그룹에 들어가지 않게
        SketchKind.convert(b, SketchKind.OPTION, 1)
        assertTrue(b.isOption); assertEquals("haptic", b.link)
        SketchKind.convert(b, SketchKind.BUTTON, 1)
        assertEquals(PadButton.TYPE_BUTTON, b.type); assertEquals(PadButton.MODE_HOLD, b.mode)
        assertEquals(-1, SketchKind.of(PadButton(0f, 0f, 0.1f, 0.1f, type = PadButton.TYPE_IMAGE)))
    }

    @Test
    fun shapesHitWhereTheyAreDrawn() {
        fun at(shape: Int, px: Float, py: Float) = PadButton(0f, 0f, 1f, 1f, shape = shape).contains(px, py)
        assertTrue(at(PadButton.SHAPE_DIAMOND, 0.5f, 0.5f)); assertFalse(at(PadButton.SHAPE_DIAMOND, 0.1f, 0.1f))
        assertTrue(at(PadButton.SHAPE_HEX, 0.05f, 0.5f));  assertFalse(at(PadButton.SHAPE_HEX, 0.05f, 0.05f))
        assertTrue(at(PadButton.SHAPE_HEX, 0.3f, 0.02f))                             // 위 변은 가로 가운데 절반
        assertTrue(at(PadButton.SHAPE_SQUARE, 0.01f, 0.01f))
        assertFalse(at(PadButton.SHAPE_OVAL, 0.05f, 0.05f))
        assertEquals(Shapes.ids.size, Shapes.names.size)
        assertFalse(Shapes.ids.contains(PadButton.SHAPE_FREE))
        assertTrue(Shapes.applies(PadButton(0f, 0f, 1f, 1f)))
        assertFalse(Shapes.applies(PadButton(0f, 0f, 1f, 1f, type = PadButton.TYPE_KNOB)))
    }

    @Test
    fun listSortGridAndReleaseAll() {
        val all = listOf(PadLayout(name = "하나"), PadLayout(name = "가나다"), PadLayout(name = "Beta"))
        assertEquals(listOf("하나", "가나다", "Beta"), ListSort.apply(all, 0).map { it.name })
        assertEquals(listOf("Beta", "가나다", "하나"), ListSort.apply(all, 2).map { it.name })
        val byName = ListSort.apply(all, 1).map { it.name }
        assertEquals(setOf("하나", "가나다", "Beta"), byName.toSet())
        assertTrue(byName.indexOf("가나다") < byName.indexOf("하나"))      // 한글은 가나다순
        assertEquals(GridSize.divisions.size, GridSize.names.size)
        assertEquals(ScreenOrientation.values.size, ScreenOrientation.names.size)
        assertEquals("모든 키 떼기", Options.title(Options.RELEASE_ALL))
        assertEquals(Options.all.size, Options.all.map { it.id }.toSet().size)
    }

    @Test
    fun clickSoundIsAValidShortWav() {
        val wav = Wav.click()
        val b = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals("data", String(wav, 36, 4))
        assertEquals(wav.size - 8, b.getInt(4))
        assertEquals(1, b.getShort(20).toInt()); assertEquals(1, b.getShort(22).toInt())   // PCM, 모노
        assertEquals(44100, b.getInt(24))
        assertEquals(44100 * 25 / 1000 * 2, b.getInt(40))                                   // 25ms 분량
        assertTrue((44 until wav.size step 2).any { b.getShort(it).toInt() != 0 })          // 무음이 아님
    }

    @Test
    fun graduatedFeaturesLeaveLabsButStayAvailable() {
        val labIds = Labs.features.map { it.id }.toSet()
        val permanentIds = Labs.permanent.map { it.id }.toSet()
        assertTrue(labIds.intersect(permanentIds).isEmpty())                 // 실험실 목록과 겹치지 않는다
        assertTrue("align" in permanentIds && "auto-dim" in permanentIds)
        assertEquals(null, Labs.feature("touch-dots"))                         // 평가단이 숨긴 기능은 지움
        assertEquals(null, Labs.feature("hold-time"))
        assertEquals(null, Labs.feature("hide-labels"))
        for (gone in listOf("grid-dots", "kps-peak", "list-compact", "pressed-outline", "random-color", "status-large"))
            assertEquals(null, Labs.feature(gone))                            // 평가단이 숨긴 기능은 다음 업데이트에서 지움
        assertTrue(listOf("dim-buttons", "list-kinds", "nudge", "open-last", "selection-count", "show-hidden-outline", "snap-edges")
            .all { it in permanentIds })                                     // 정식으로 간 기능은 늘 있는 기능으로
        assertEquals(6, listOf("press-count", "release-haptic", "release-sound", "dim-buttons", "pressed-outline",
            "kps-peak", "clock-seconds", "session-summary", "status-large", "snap-edges", "selection-count", "grid-dots",
            "show-hidden-outline", "nudge", "random-color", "list-count", "list-compact", "open-last", "list-kinds").count { it in labIds })
        assertEquals(null, Labs.feature("confirm-delete"))                     // 숨겼던 기능은 지움
        assertEquals("맞춤", Labs.feature("align")?.title)
    }

    @Test
    fun layoutKindsDescribeTheMix() {
        val l = PadLayout(name = "t")
        assertEquals("비어 있음", LayoutKinds.describe(l))
        l.buttons += PadButton(0f, 0f, 0.1f, 0.1f); l.buttons += PadButton(0f, 0f, 0.1f, 0.1f)
        l.buttons += PadButton(0f, 0f, 0.1f, 0.1f, type = PadButton.TYPE_KNOB)
        assertEquals("버튼 2 · 노브 1", LayoutKinds.describe(l))
    }
}
