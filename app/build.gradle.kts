import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI 打 tag 构建时通过环境变量注入版本号（tag: v1.2.3 -> versionName 1.2.3）
val ciVersion = System.getenv("RELEASE_VERSION")
val ciVersionCode = System.getenv("RELEASE_CODE")?.toIntOrNull()

// 签名配置：本地读 keystore/signing.properties（git 忽略），
// CI 读 GitHub Secrets 注入的环境变量；两者皆缺时回退 debug 签名（公开克隆仍可构建）
val releaseSigning = Properties().apply {
    val propsFile = rootProject.file("keystore/signing.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
fun signingProp(key: String, envKey: String): String? =
    releaseSigning.getProperty(key) ?: System.getenv(envKey)

android {
    namespace = "com.mengpolar.dailycheck"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mengpolar.dailycheck"
        minSdk = 26
        targetSdk = 34
        versionName = ciVersion ?: "0.1.2"
        versionCode = ciVersionCode ?: 102
    }

    signingConfigs {
        create("release") {
            val ksFile = signingProp("storeFile", "RELEASE_KEYSTORE_FILE") ?: "keystore/release.keystore"
            val ksPass = signingProp("storePassword", "RELEASE_KEYSTORE_PASSWORD")
            val ksAlias = signingProp("keyAlias", "RELEASE_KEY_ALIAS") ?: "dailycheck"
            val keyPass = signingProp("keyPassword", "RELEASE_KEY_PASSWORD")
            if (ksPass != null && keyPass != null && rootProject.file(ksFile).exists()) {
                storeFile = rootProject.file(ksFile)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = keyPass
            } else {
                // 无签名凭据：置空 storeFile，构建时回退 debug 签名（见 buildTypes）
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingConfigs.getByName("release").storeFile != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
