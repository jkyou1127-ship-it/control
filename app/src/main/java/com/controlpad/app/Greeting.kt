package com.controlpad.app

/** 목록 인사말(실험실 기능): 시간대에 맞는 한 줄. */
object Greeting {
    fun now(hour: Int): String = when (hour) {
        in 5..10 -> "좋은 아침이에요 ☀ 오늘도 가볍게 손 풀고 시작해요"
        in 11..13 -> "점심 시간이에요 🍚 한 판 하고 밥 먹어요"
        in 14..17 -> "좋은 오후예요 🌤 집중력 올라가는 시간"
        in 18..22 -> "좋은 저녁이에요 🌙 오늘의 최고 기록을 노려 봐요"
        else -> "늦은 밤이에요 🌌 손목 무리하지 마세요"
    }
}
