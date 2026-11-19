plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jiucai.provision"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jiucai.provision"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"
    }

    // 自签名证书随仓库提供，保证每次发布签名一致、可覆盖安装。
    // 仅用于个人自建分发，私钥公开不影响用途（不用于身份鉴别）。
    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "jiucai-s7-2026"
            keyAlias = "jiucai-s7"
            keyPassword = "jiucai-s7-2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
