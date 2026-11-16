package com.controlpad.app

/** 실험실 기능 '지연에 따라 상태 줄 색': 왕복 지연(ms)에 따른 색. */
object RttColor {
    fun of(ms: Int): Int = when {
        ms < 15 -> 0xFF66BB6A.toInt()      // 초록: 빠름
        ms < 40 -> 0xFFFFCA28.toInt()      // 노랑
        else -> 0xFFEF5350.toInt()         // 빨강: 느림
    }
}

/** 실험실 기능 '목록에 마지막 실행 시각': '방금 실행' · '3분 전 실행' 같은 글자. */
object LastPlayed {
    fun describe(at: Long, now: Long): String {
        if (at <= 0L) return "아직 실행 안 함"
        val s = (now - at).coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "방금 실행"
            s < 3600 -> "${s / 60}분 전 실행"
            s < 86400 -> "${s / 3600}시간 전 실행"
            else -> "${s / 86400}일 전 실행"
        }
    }
}
