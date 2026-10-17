// Modul ini SENGAJA tidak punya dependensi Android sama sekali.
// Semua logika animasi bisa diuji di JVM biasa lewat `./gradlew :core:anim:test`.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}
