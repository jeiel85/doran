import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase 설정은 배포 환경마다 따로 둡니다(README "Firebase"). 없으면 실시간 깨우기 없이
// 주기 동기화로 동작하고 설정 화면에 그 사실을 그대로 보여 줍니다(DECISIONS.md D-9).
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) apply(plugin = "com.google.gms.google-services")

val appVersion = providers.gradleProperty("app.version").orElse("0.1.0").get()
val appVersionCode = appVersion.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { (maj, min, pat) -> maj * 10000 + min * 100 + pat }

// 중계 주소는 -Papp.relayUrl로 정합니다. 주지 않으면 릴리스는 운영 중계, 디버그는 에뮬레이터에서 본 PC의 로컬 중계
// (wrangler dev, README "로컬 중계로 시험")입니다. 예전에는 디버그도 운영이라 에뮬레이터 시험이 운영 중계에 기기를 등록했어요(#45).
val relayOverride = providers.gradleProperty("app.relayUrl").orNull
val relayUrl = relayOverride ?: "https://doran-relay.jeiel85.workers.dev"
val debugRelayUrl = relayOverride ?: "http://10.0.2.2:8787"

// --- 서명 (값은 git에 없는 properties 파일 또는 환경 변수) — 채널마다 다른 키 (DECISIONS.md D-16) ---------
// github: GitHub 릴리스 키(release-signing.properties) / play: Play 업로드 키(play-upload-signing.properties).
val signingProps = Properties().apply {
    listOf("release-signing.properties", "play-upload-signing.properties").forEach { f ->
        rootProject.file(f).takeIf { it.exists() }?.inputStream()?.use(::load)
    }
}
fun signing(name: String): String? = providers.environmentVariable(name).orNull ?: signingProps.getProperty(name)
fun hasKeys(prefix: String) = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !signing("${prefix}_$it").isNullOrBlank() }
val hasReleaseSigning = hasKeys("DORAN_RELEASE")
val hasPlaySigning = hasKeys("DORAN_PLAY_UPLOAD")

android {
    namespace = "com.jeiel85.doran"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jeiel85.doran"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("boolean", "HAS_FIREBASE", "$hasFirebaseConfig")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signing("DORAN_RELEASE_STORE_FILE")!!)
                storePassword = signing("DORAN_RELEASE_STORE_PASSWORD")
                keyAlias = signing("DORAN_RELEASE_KEY_ALIAS")
                keyPassword = signing("DORAN_RELEASE_KEY_PASSWORD")
            }
        }
        if (hasPlaySigning) {
            create("playUpload") {
                storeFile = rootProject.file(signing("DORAN_PLAY_UPLOAD_STORE_FILE")!!)
                storePassword = signing("DORAN_PLAY_UPLOAD_STORE_PASSWORD")
                keyAlias = signing("DORAN_PLAY_UPLOAD_KEY_ALIAS")
                keyPassword = signing("DORAN_PLAY_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    // 배포 채널별 빌드 (전역 지침·DECISIONS.md D-16): 같은 applicationId, 다른 서명 키.
    // github만 자가 업데이트와 문자 수신(RECEIVE_SMS)이 있고, play는 문자 앱 알림으로 문자를 읽습니다.
    flavorDimensions += "channel"
    productFlavors {
        create("github") {
            dimension = "channel"
            buildConfigField("boolean", "SMS_RECEIVER", "true")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "SMS_RECEIVER", "false")
            if (hasPlaySigning) signingConfig = signingConfigs.getByName("playUpload")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "RELAY_URL", "\"$debugRelayUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // 단위 시험은 순수 함수뿐이라 작은 힙·SerialGC로 충분합니다. 커밋 여유가 2GB 안팎인 날 시험 JVM이 G1 힙을 못 잡아
        // hs_err 로그를 남기고 죽던 일이 있었어요(#36).
        unitTests.all {
            it.maxHeapSize = "512m"
            it.jvmArgs("-XX:+UseSerialGC")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Play 빌드만: Play에 새 버전이 있는지 묻기(설치는 Play가, DECISIONS.md D-21).
    "playImplementation"("com.google.android.play:app-update-ktx:2.1.0")

    // "AI로 한 번 더 보기"의 Claude 쪽(DECISIONS.md D-25). OpenAI는 의존성 없이 HTTP로 부릅니다.
    implementation("com.anthropic:anthropic-java:3.3.0")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
