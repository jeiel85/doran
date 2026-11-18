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

val relayUrl = providers.gradleProperty("app.relayUrl")
    .orElse("https://doran-relay.jeiel85.workers.dev").get()

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

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
