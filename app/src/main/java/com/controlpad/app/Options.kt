package com.controlpad.app

/**
 * 실행 화면에서 '옵션 버튼'으로 켜고 끌 수 있는 설정들(실험실 기능 '옵션 버튼').
 * 설정 화면의 체크박스와 실험실 스위치를 그대로 쓰므로, 버튼으로 바꾸면 그쪽에도 똑같이 반영된다.
 */
object Options {
    /** 누르면 눌려 있는 키와 켜 둔 버튼을 모두 푸는 버튼(설정을 바꾸지는 않는다). */
    const val RELEASE_ALL = "release-all"

    class Option(val id: String, val title: String)

    /** 설정 화면에 있는 실행 관련 옵션. 실험실 기능은 그 아래에 이어 붙는다. */
    private val settings = listOf(
        Option("haptic", "진동"),
        Option("show-pressed", "눌리고 있는 키 표시"),
        Option("key-names", "버튼에 키 이름 표시"),
        Option(RELEASE_ALL, "모든 키 떼기"),
    )

    /** 실행 중에 바꿔서 의미가 있는 실험실 기능들. */
    val labIds = listOf(
        "kps", "battery", "exit-guard", "hide-status", "press-sound", "key-history",
        "touch-dots", "hold-time", "auto-dim", "edge-flash", "status-bottom",
    )

    val all: List<Option>
        get() = settings + labIds.mapNotNull { id -> Labs.features.firstOrNull { it.id == id }?.let { Option(id, it.title) } }

    fun title(id: String): String = all.firstOrNull { it.id == id }?.title ?: "옵션"

    fun isOn(id: String): Boolean = when (id) {
        "haptic" -> Link.haptic
        "show-pressed" -> Link.showPressed
        "key-names" -> Link.showKeyNames
        RELEASE_ALL -> false
        in labIds -> Labs.isOn(id)
        else -> false
    }

    /** 켜져 있으면 끄고, 꺼져 있으면 켠다. 바뀐 뒤의 상태를 돌려준다. 정식 기능이 된 실험실 기능은 끌 수 없다. */
    fun toggle(id: String): Boolean {
        val on = !isOn(id)
        when (id) {
            "haptic" -> Link.prefs.edit().putBoolean("haptic", on).apply()
            "show-pressed" -> Link.showPressed = on
            "key-names" -> Link.showKeyNames = on
            in labIds -> Labs.set(id, on)
        }
        return isOn(id)
    }
}
