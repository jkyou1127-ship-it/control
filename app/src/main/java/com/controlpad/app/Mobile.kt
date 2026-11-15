package com.controlpad.app

/**
 * 모바일 기능 10개(NES3): 태블릿 쪽 편의 기능. 기능 · 기타 탭의 '모바일'에서 켜고 끈다.
 */
object Mobile {
    class Toggle(val key: String, val title: String, val note: String, val default: Boolean)

    val toggles = listOf(
        Toggle("mMaxBright", "실행 화면 최대 밝기", "실행 화면에서는 화면 밝기를 가장 밝게 합니다", false),
        Toggle("mBlockVolume", "볼륨 버튼 막기", "실행 중 볼륨 버튼을 눌러도 소리가 바뀌지 않습니다", false),
        Toggle("mTimer", "실행 시간 표시", "실행 화면 상태 줄에 실행한 지 얼마나 됐는지 보여 줍니다", false),
        Toggle("mSecure", "화면 캡처 · 녹화 막기", "실행 화면이 캡처나 녹화에 찍히지 않게 합니다", false),
        Toggle("mPausedBanner", "PC 일시정지 크게 알림", "PC 수신 프로그램에서 입력을 일시정지하면 실행 화면 위쪽에 크게 알립니다", true),
        Toggle("mAutoTheme", "시스템 다크 모드 따라가기", "기기가 다크 모드면 '다크', 아니면 '라이트' 테마를 씁니다", false),
        Toggle("mReconnect", "다시 연결되면 알림", "실행 중에 끊겼던 PC 연결이 돌아오면 짧은 진동과 알림으로 알려 줍니다", true),
        Toggle("mLowBattery", "배터리 부족 알림", "실행 중 배터리가 15% 이하로 내려가면 한 번 알려 줍니다", true),
        Toggle("mScreenOff", "화면 자동 꺼짐 허용", "켜면 실행 중에도 기기 설정대로 화면이 꺼집니다(배터리 절약)", false),
    )

    fun isOn(key: String): Boolean {
        val t = toggles.first { it.key == key }
        return Link.prefs.getBoolean(key, t.default)
    }

    fun set(key: String, on: Boolean) {
        Link.prefs.edit().putBoolean(key, on).apply()
        if (key == "mAutoTheme") Themes.touch()
    }

    /** 10번째 기능: 목록 카드 크기. */
    val cardSizes = listOf("작게", "보통", "크게")
    var cardSize: Int
        get() = Link.prefs.getInt("mCardSize", 1).coerceIn(0, 2)
        set(v) = Link.prefs.edit().putInt("mCardSize", v.coerceIn(0, 2)).apply()

    /** 카드 크기에 따른 카드 최소 너비와 미리보기 높이(dp). */
    fun cardWidthDp(): Int = intArrayOf(220, 280, 360)[cardSize]
    fun thumbHeightDp(): Int = intArrayOf(90, 120, 160)[cardSize]

    val maxBright get() = isOn("mMaxBright")
    val blockVolume get() = isOn("mBlockVolume")
    val timer get() = isOn("mTimer")
    val secure get() = isOn("mSecure")
    val pausedBanner get() = isOn("mPausedBanner")
    val autoTheme get() = isOn("mAutoTheme")
    val reconnect get() = isOn("mReconnect")
    val lowBattery get() = isOn("mLowBattery")
    val screenOff get() = isOn("mScreenOff")

    /** 실행 시간 글자: 59:59 까지는 분:초, 그 뒤로는 시:분:초. */
    fun elapsed(ms: Long): String {
        val s = ms / 1000
        return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }
}
