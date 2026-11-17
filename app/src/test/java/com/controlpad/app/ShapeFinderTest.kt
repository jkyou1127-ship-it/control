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

    @Test
    fun communityIndexAndUploadBody() {
        val idx = """{"version":1,"layouts":[
            {"id":"20261117T083000-abcdef","name":"리듬 4키","author":"가나","created":"2026-11-17T08:30:00.000Z","buttons":4,"bytes":2048},
            {"id":"../evil","name":"x"},
            {"id":"b","name":"","author":""}]}"""
        val list = Community.parseIndex(idx)
        assertEquals(listOf("20261117T083000-abcdef", "b"), list.map { it.id })     // 주소를 벗어나는 id 는 버린다
        assertEquals("익명", list[1].author)
        assertEquals("2026.11.17", Community.date(list[0].created))
        val body = JSONObject(Community.uploadBody("이름", "나", "{\"format\":\"controlpad-layout\"}"))
        assertEquals("이름", body.getString("name"))
        assertEquals("controlpad-layout", JSONObject(body.getString("layout")).getString("format"))
        val file = """{"id":"x","name":"n","layout":{"format":"controlpad-layout","buttons":[]}}"""
        assertEquals("controlpad-layout", JSONObject(Community.layoutText(file)).getString("format"))
        assertEquals(null, Community.problem(200, "{}"))
        assertTrue(Community.problem(400, "{\"error\":\"too_big\"}")!!.contains("너무 큽니다"))
        assertTrue(Community.problem(403, "{\"error\":\"bad_key\"}")!!.contains("이 기기에서 올린"))
        val del = JSONObject(Community.deleteBody("id1", "k"))
        assertEquals("id1", del.getString("id")); assertEquals("k", del.getString("key"))
    }

    @Test
    fun builtInLayoutsCannotBeUploadedAndTiersMatchTheRelay() {
        // 예시 레이아웃 그대로(이름만 바꿔도)는 기본 레이아웃, 고치면 내 것
        val t = Templates.create(1, "리듬게임 4키").apply { name = "내 4키" }
        assertTrue(Community.isBuiltIn(t))
        assertTrue(Community.isBuiltIn(PadLayout(name = "빈 것")))
        val edited = Templates.create(1, "x").apply { buttons[0].keys = mutableListOf(0x04) }
        assertFalse(Community.isBuiltIn(edited))
        assertEquals(Community.fingerprint(Templates.create(13, "a")), Community.fingerprint(Templates.create(13, "b")))
        // 등급(중계 서버 tierOf 와 같은 기준)
        assertEquals("🌱 새싹", Community.tierOf(0))
        assertEquals("🌿 일반", Community.tierOf(3))
        assertEquals("🌳 활동가", Community.tierOf(10))
        assertEquals("🏆 장인", Community.tierOf(30))
        assertEquals("👑 관리자", Community.tierOf(0, true))
        assertEquals(2, Community.toNextTier(1))
        assertEquals(0, Community.toNextTier(40))
        assertTrue(Community.isAdminName(" CupomPu "))
        val body = JSONObject(Community.uploadBody("n", "cupompu", "{}", 5, "pw"))
        assertEquals(5, body.getInt("uploads")); assertEquals("pw", body.getString("adminPassword"))
        assertFalse(JSONObject(Community.uploadBody("n", "a", "{}")).has("adminPassword"))
        assertEquals("🏆 장인", Community.parseIndex("""{"layouts":[{"id":"a","tier":"🏆 장인"}]}""")[0].tier)
        val posts = Community.parsePosts("""{"layouts":[],"posts":[{"id":"p1","title":"안녕","text":"본문\n둘째 줄","author":"","tier":"🌱 새싹","created":"2026-11-17T00:00:00Z"},{"id":"a/b","title":"x"}]}""")
        assertEquals(1, posts.size)
        assertEquals("익명", posts[0].author)
        assertEquals("본문\n둘째 줄", posts[0].text)
        val pb = JSONObject(Community.postBody("제목", "내용", "나", 2))
        assertEquals("제목", pb.getString("title")); assertEquals(2, pb.getInt("uploads"))
        assertEquals("post", JSONObject(Community.deleteBody("p1", "k", kind = "post")).getString("kind"))
    }
}
