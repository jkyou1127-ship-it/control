package com.controlpad.app

/**
 * 사진 · 그림 속 도형 찾기(사진으로 컨트롤러 만들기의 3단계).
 *
 * 밝기 배열(0~255)만 받으므로 안드로이드 없이도 시험할 수 있다.
 *  1. 오츠(Otsu) 방법으로 밝음/어두움을 나누는 기준을 찾고, 감도만큼 옮긴다.
 *  2. 테두리에 많은 쪽을 바탕으로 보고, 나머지를 도형(전경)으로 본다.
 *  3. 한 칸 닫기(팽창 → 침식)로 끊긴 선을 잇는다.
 *  4. 바깥에서 닿지 않는 바탕(선으로 그린 동그라미의 안쪽 등)은 도형으로 채운다.
 *  5. 이어진 덩어리마다 번호를 붙이고, 너무 작거나 화면 대부분을 덮는 것은 뺀다.
 *  6. 위에서 아래, 왼쪽에서 오른쪽 순서로 번호를 다시 매긴다.
 */
object ShapeFinder {
    class Shape(val id: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val area: Int) {
        val cx get() = (left + right) / 2f
        val cy get() = (top + bottom) / 2f
    }

    /** labels[i] = 그 칸이 속한 도형 번호(1부터), 0 이면 도형 아님. */
    class Result(val width: Int, val height: Int, val labels: IntArray, val shapes: List<Shape>)

    fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        val total = lum.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /**
     * sensitivity: 기준을 얼마나 옮길지(-60~60, + 이면 더 많은 것을 도형으로 본다).
     * minAreaFrac: 이보다 작은 덩어리는 버린다(전체 넓이 대비, 예: 0.004 = 0.4%).
     */
    fun find(lum: IntArray, w: Int, h: Int, sensitivity: Int = 0, minAreaFrac: Float = 0.004f): Result {
        val n = w * h
        val base = otsu(lum)
        // 테두리에서 어두운 칸이 적으면 바탕이 밝은 것 → 어두운 쪽이 도형
        var borderDark = 0
        var border = 0
        fun edge(i: Int) { border++; if (lum[i] <= base) borderDark++ }
        for (x in 0 until w) { edge(x); edge((h - 1) * w + x) }
        for (y in 1 until h - 1) { edge(y * w); edge(y * w + w - 1) }
        val darkShapes = borderDark * 2 < border
        val t = if (darkShapes) base + sensitivity else base - sensitivity
        var fg = BooleanArray(n) { i -> if (darkShapes) lum[i] <= t else lum[i] > t }

        // 닫기: 팽창 한 번 → 침식 한 번
        fun dilate(src: BooleanArray) = BooleanArray(n) { i ->
            val x = i % w; val y = i / w
            var on = false
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && src[yy * w + xx]) { on = true }
            }
            on
        }
        fun erode(src: BooleanArray) = BooleanArray(n) { i ->
            val x = i % w; val y = i / w
            var on = true
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && !src[yy * w + xx]) { on = false }
            }
            on
        }
        fg = erode(dilate(fg))

        // 바깥 바탕: 테두리에서 시작해 도형이 아닌 칸을 따라 퍼진다
        val outside = BooleanArray(n)
        val queue = IntArray(n)
        var qh = 0
        var qt = 0
        fun seed(i: Int) { if (!fg[i] && !outside[i]) { outside[i] = true; queue[qt++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % w; val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        val filled = BooleanArray(n) { fg[it] || !outside[it] }

        // 이어진 덩어리 찾기
        val raw = IntArray(n)
        val found = ArrayList<IntArray>()          // [번호, left, top, right, bottom, area]
        var next = 0
        for (start in 0 until n) {
            if (!filled[start] || raw[start] != 0) continue
            next++
            var l = w; var tp = h; var r = -1; var b = -1; var area = 0
            qh = 0; qt = 0
            raw[start] = next; queue[qt++] = start
            while (qh < qt) {
                val i = queue[qh++]
                val x = i % w; val y = i / w
                area++
                if (x < l) l = x; if (x > r) r = x; if (y < tp) tp = y; if (y > b) b = y
                fun go(j: Int) { if (filled[j] && raw[j] == 0) { raw[j] = next; queue[qt++] = j } }
                if (x > 0) go(i - 1)
                if (x < w - 1) go(i + 1)
                if (y > 0) go(i - w)
                if (y < h - 1) go(i + w)
            }
            found += intArrayOf(next, l, tp, r, b, area)
        }
        val minArea = (n * minAreaFrac).toInt().coerceAtLeast(9)
        val kept = found.filter { it[5] >= minArea && it[5] <= n * 0.6 && it[3] - it[1] >= 2 && it[4] - it[2] >= 2 }
        // 위 → 아래(높이의 1/6 단위 줄), 왼쪽 → 오른쪽
        val band = (h / 6).coerceAtLeast(1)
        val ordered = kept.sortedWith(compareBy({ ((it[2] + it[4]) / 2) / band }, { (it[1] + it[3]) / 2 }))
        val remap = HashMap<Int, Int>()
        val shapes = ordered.mapIndexed { k, f -> remap[f[0]] = k + 1; Shape(k + 1, f[1], f[2], f[3], f[4], f[5]) }
        val labels = IntArray(n) { remap[raw[it]] ?: 0 }
        return Result(w, h, labels, shapes)
    }

    /** 만든 버튼에 차례로 줄 기본 키(HID usage): A S D F J K L ; Q W E R U I O P Z X C V 1~0 */
    val defaultKeys: List<Int> = listOf(0x04, 0x16, 0x07, 0x09, 0x0D, 0x0E, 0x0F, 0x33, 0x14, 0x1A, 0x08, 0x15, 0x18, 0x0C, 0x12, 0x13,
        0x1D, 0x1B, 0x06, 0x19) + (0x1E..0x27).toList()

    fun keyFor(index: Int): Int = defaultKeys[index % defaultKeys.size]
}
