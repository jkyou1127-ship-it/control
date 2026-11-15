package com.controlpad.app

/**
 * 오프셋 기본 프리셋: 자주 쓰는 보정값 묶음. 고르면 터치 가로·세로 보정과 입력 지연을 한 번에 바꾼다.
 * x, y 는 dp(오른쪽·아래가 +), delay 는 ms.
 */
object OffsetPresets {
    class Preset(val name: String, val note: String, val x: Int, val y: Int, val delay: Int)

    val all = listOf(
        Preset("기본", "보정 없음", 0, 0, 0),
        Preset("손가락이 조금 아래에 닿을 때", "눌리는 자리를 위로 6dp", 0, -6, 0),
        Preset("손가락이 많이 아래에 닿을 때", "눌리는 자리를 위로 14dp", 0, -14, 0),
        Preset("손가락이 위에 닿을 때", "눌리는 자리를 아래로 8dp", 0, 8, 0),
        Preset("오른손 엄지로 칠 때", "왼쪽 위로 6dp", -6, -6, 0),
        Preset("왼손 엄지로 칠 때", "오른쪽 위로 6dp", 6, -6, 0),
        Preset("태블릿을 세워 두고 칠 때", "위로 10dp", 0, -10, 0),
        Preset("모니터 화면이 늦을 때", "입력을 20ms 늦게", 0, 0, 20),
        Preset("TV · 프로젝터", "입력을 60ms 늦게", 0, 0, 60),
        Preset("무선 이어폰으로 소리를 들을 때", "입력을 150ms 늦게 (소리와 박자 맞추기)", 0, 0, 150),
    )

    fun apply(p: Preset) {
        Link.touchOffsetX = p.x
        Link.touchOffsetY = p.y
        Link.inputDelay = p.delay
    }

    /** 지금 값과 똑같은 프리셋(없으면 null). */
    fun current(): Preset? = all.firstOrNull { it.x == Link.touchOffsetX && it.y == Link.touchOffsetY && it.delay == Link.inputDelay }
}
