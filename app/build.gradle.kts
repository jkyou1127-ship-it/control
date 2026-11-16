plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.controlpad.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.controlpad.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 89
        versionName = "ENN BACK"
        // 앱 아이콘 밑 이름 = 버전명(코드명). 앱 정보 화면 등의 앱 이름은 'Control Pad' 그대로.
        resValue("string", "launcher_label", versionName ?: "Control Pad")
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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
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
