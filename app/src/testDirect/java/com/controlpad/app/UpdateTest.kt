package com.controlpad.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** 직접 배포용 빌드(앱 안에서 업데이트)에만 해당하는 검사. */
class UpdateTest {
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
}
