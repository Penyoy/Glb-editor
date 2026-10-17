import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "id.animasi.anima3d.render"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    // Hasil parse .glb dipakai untuk memadankan node glTF dengan entity Filament.
    api(project(":core:gltf"))

    implementation(libs.androidx.core.ktx)

    // Filament dipakai langsung, tanpa pembungkus pihak ketiga, supaya modul ini
    // menguasai penuh TransformManager dan swapchain yang dibutuhkan editor.
    api(libs.filament.android)
    api(libs.filament.gltfio)
    api(libs.filament.utils)
}
