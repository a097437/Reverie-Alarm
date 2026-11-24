import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式版簽名：金鑰路徑與密碼放在專案根目錄的 keystore.properties（已排除在 git 之外）
// storeFile=release/reverie-release.jks
// storePassword=…
// keyAlias=reverie
// keyPassword=…
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.reverie.alarm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.reverie.alarm"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "2.6.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            // App Check：正式版用 Play Integrity
            buildConfigField("boolean", "APP_CHECK_DEBUG", "false")
            // StrictMode（主執行緒讀寫硬碟時記錄）只在 debug 版開；量流暢度用的 perfTest 不開，避免影響數據
            buildConfigField("boolean", "STRICT_MODE", "false")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
        // 效能測試用：和正式版一樣最佳化（流暢度接近上架版）。
        // 有正式版金鑰時用同一把簽名，和 GitHub Releases 的正式版可以互相覆蓋安裝、不會清掉資料；
        // 沒有金鑰（例如別人 clone 下來編譯）時退回 debug 金鑰
        create("perfTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            matchingFallbacks += listOf("release")
            // 測試版用 App Check 的偵錯提供者（偵錯權杖要加到 Firebase 主控台的允許清單）
            buildConfigField("boolean", "APP_CHECK_DEBUG", "true")
        }
        // debug 版也用同一把金鑰，三種版本都能互相覆蓋
        getByName("debug") {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "APP_CHECK_DEBUG", "true")
            buildConfigField("boolean", "STRICT_MODE", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

composeCompiler {
    // java.time 等不可變類別當成穩定型別（見 compose-stability.conf），日期參數沒變時元件可以跳過重組
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose-stability.conf"))
    // 想看哪些參數不穩定、哪些元件不能跳過時：./gradlew :app:compileDebugKotlin -PcomposeReports=true
    // 報告在 app/build/compose_reports/
    if (project.findProperty("composeReports") == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_reports")
    }
}

dependencies {
    // ---- 共享帳號：Firebase 登入與雲端班表 ----
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    // ---- AI 快速排班：Gemini 透過 Firebase AI Logic（金鑰留在 Google 那邊，不放進 App） ----
    implementation("com.google.firebase:firebase-ai")
    // ---- App Check：確認請求來自正版 App（上架 Google Play 後在主控台改成「強制執行」） ----
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    "perfTestImplementation"("com.google.firebase:firebase-appcheck-debug")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}