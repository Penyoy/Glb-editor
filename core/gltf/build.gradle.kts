// Modul ini menentukan isi file .glb secara otoritatif. Filament juga membaca file
// yang sama, tetapi API-nya tidak memberi tahu mana node yang merupakan joint.
// Karena itu editor memakai hasil parse di sini sebagai sumber kebenaran.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:anim"))
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
