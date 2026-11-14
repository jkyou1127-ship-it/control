import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 스토어 업로드용 서명 정보. 저장소에 넣지 않는 play-signing.properties 에서 읽는다(scripts/build-play.sh 참고).
val playSigning = Properties().apply {
    val f = rootProject.file("play-signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.controlpad.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.controlpad.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 35
        versionName = "2.13"
    }

    // 저장소에 포함된 고정 키. 비밀이 아니며, 어디서 빌드하든 같은 서명이 되어
    // 기존 설치 위에 업데이트할 수 있게 하는 용도다.
    signingConfigs {
        create("shared") {
            storeFile = file("controlpad.keystore")
            storePassword = "controlpad"
            keyAlias = "controlpad"
            keyPassword = "controlpad"
        }
    }

    if (playSigning.containsKey("storeFile")) {
        signingConfigs.create("play") {
            storeFile = rootProject.file(playSigning.getProperty("storeFile"))
            storePassword = playSigning.getProperty("storePassword")
            keyAlias = playSigning.getProperty("keyAlias")
            keyPassword = playSigning.getProperty("keyPassword")
        }
    }

    // direct: 저장소에서 APK 를 직접 받아 설치하고 앱 안에서 업데이트하는 빌드.
    // play:   구글 플레이 스토어용. 자체 업데이트가 없고, 스토어가 요구하는 최신 Android 를 대상으로 한다.
    flavorDimensions += "dist"
    productFlavors {
        create("direct") {
            dimension = "dist"
            signingConfig = signingConfigs.getByName("shared")
        }
        create("play") {
            dimension = "dist"
            // 스토어에 한 번 올리면 바꿀 수 없다. 처음 올리기 전에 원하는 이름으로 정한다.
            applicationId = "io.github.jkyou1127shipit.controlpad"
            targetSdk = 36
            signingConfig = signingConfigs.findByName("play")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
