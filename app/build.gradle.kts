import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.animasi.anima3d.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.animasi.anima3d"
        minSdk = 26
        // Compose UI 1.12.1 dan Filament 1.77.3 sama-sama mensyaratkan compileSdk 37,
        // tetapi targetSdk tetap 36 supaya perubahan perilaku Android 17 belum berlaku.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":render"))
    implementation(project(":core:gltf"))
    implementation(project(":core:anim"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
