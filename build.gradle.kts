// Phase 0 scaffold. Root build logic is intentionally small; module boundaries are real Gradle modules.
//
// AGP 9 has built-in Kotlin. KGP 2.4.21 is placed on the buildscript classpath so the built-in
// compiler, the Compose compiler plugin, and KSP share one Kotlin generation. This combination is
// inside the published compatibility matrix (KGP 2.4.21: Gradle <= 9.7.0, AGP <= 9.3.1).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
