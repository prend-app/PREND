import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 출시 키(11/07 사용자 "추천대로"): 키 파일과 비밀번호는 저장소 바깥(../.secrets)에만 둔다. 없으면(다른 PC) 디버그 키로 서명한다
val keyProps = Properties().also { p ->
    rootProject.file("../.secrets/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
}

android {
    namespace = "app.prend"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.prend"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // 앱 이름은 여기 한 곳: 런처·설정의 이름(@string/app_name)과 코드의 Prend.NAME·Prend.CALL(알림·배너·버튼·토스트)이 이것을 따른다.
        // 이름을 바꾸면 따로 볼 것: 위 막대의 기어 글자 P R E N D(ui/Parts.kt Gear), 런처 아이콘(res/drawable/ic_launcher_*.xml)
        val appName = "PREND"
        val callName = "프렌드"
        resValue("string", "app_name", appName)
        resValue("string", "music_watch_name", "$appName 곡 정보")
        buildConfigField("String", "APP_NAME", "\"$appName\"")
        buildConfigField("String", "CALL_NAME", "\"$callName\"")
        // 중계 서버(prend-relay)를 부르는 앱 토큰. 네이버·공공데이터 키는 앱에 넣지 않고 서버에만 둔다.
        // 토큰은 소스·GitHub에 없고 빌드할 때 .secrets에서 읽는다(없으면 빈 값: 중계 서버 기능이 꺼진다)
        val token = rootProject.file("../.secrets/relay-token.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""
        buildConfigField("String", "RELAY_TOKEN", "\"$token\"")
        // 담을 기기(ABI)는 빌드 종류마다 따로 정한다(아래 buildTypes). 여기에 두면 빌드 종류의 값과 합쳐져 출시판에서 x86_64를 뺄 수 없다
        // 시연 패널(이름 다섯 번 누르기)·시험 명령·PT 단추를 여는가. 디버그와 PT 빌드만 켠다(제출 출시판은 끈다, 11/05)
        buildConfigField("Boolean", "DEMO", "false")
    }

    signingConfigs {
        if (!keyProps.isEmpty) create("release") {
            storeFile = rootProject.file("../.secrets/" + keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // 시험용 APK: 차(arm64)와 에뮬레이터(x86_64)만 담는다. 말소리 판정기(ONNX Runtime)의 다른 기기용 파일을 빼서 APK를 줄인다
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            buildConfigField("Boolean", "DEMO", "true")
        }
        // 제출·GitHub용 APK: R8로 쓰지 않는 코드·아이콘을 덜어 낸다. 출시 키로 서명한다(없으면 디버그 키)
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keyProps.isEmpty) signingConfigs.getByName("debug") else signingConfigs.getByName("release")
            // 실차(arm64)와 에뮬레이터(x86_64)를 함께 담는다(11/07: 심사는 Pleos 에뮬레이터에서 하므로 제출 APK 하나가 둘 다에서 돌아야 한다.
            // 전에는 arm64만 담아 에뮬레이터에 깔리지 않았다)
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        // PT·시연 영상용 APK(11/05): 출시판과 같은 최적화(R8)로 빠르게 돌고, 에뮬레이터(x86_64)에서도 돌며, 시험 명령·PT 단추를 받는다.
        // 디버그 판은 화면이 몇 배 느려(시험 27) 발표에 쓰지 않는다. gradle assemblePt → app/build/outputs/apk/pt/
        create("pt") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            ndk { abiFilters += "x86_64" }
            buildConfigField("Boolean", "DEMO", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // 빗소리 결은 원음 PCM(WAV)으로 읽는다. 압축하지 않는다
        noCompress += "wav"
        noCompress += "onnx"
        // 목소리는 OGG(Vorbis)로 넣고 openFd로 바로 연다(압축하면 openFd가 열지 못한다)
        noCompress += "ogg"
        // 글꼴(Pretendard 6굵기)도 압축하지 않는다: 압축돼 있으면 처음 쓸 때 메인 스레드에서 풀어 굵기마다 1.6MB를 메모리에 올린다
        noCompress += "otf"
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 시험 46(경유지), 시험 9(짧은 대답 알아듣기)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // 이야기에 비켜 서기: 말소리 여부만 판정하는 Silero VAD(MIT)를 기기 안에서 돌린다(별첨 1)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // 화면(Jetpack Compose)
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // 화면 질감: 읽는 판과 캡슐은 스모크 유리(Haze, 11/09부터 굴절 유리 backdrop은 쓰지 않는다),
    // G2 연속 모서리 capsule, 아이콘 Phosphor(MIT)
    implementation("dev.chrisbanes.haze:haze:1.7.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.7.1")
    implementation("io.github.kyant0:capsule:2.1.1")
    implementation("com.adamglin:phosphor-icon:1.0.0")
}

// AGP 8.8.2에 맞게 activity를 1.10.x로 묶는다(1.11은 AGP 8.9.1·compileSdk 36 필요)
configurations.all {
    resolutionStrategy {
        force("androidx.activity:activity:1.10.1", "androidx.activity:activity-ktx:1.10.1", "androidx.activity:activity-compose:1.10.1")
    }
}
