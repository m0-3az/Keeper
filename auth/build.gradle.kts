plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.keeper.vault.auth"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":contracts"))
    implementation(project(":crypto"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.compose.ui.test.junit4)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

apply(from = rootProject.file("gradle/jacoco-module.gradle.kts"))

/*
 * Platform adapters in this module need a device (Android Keystore, Application lifecycle,
 * process-wide composition). They are exercised by the instrumented tests and excluded from the
 * JVM coverage gate, matching the shared JaCoCo policy for crypto's Keystore adapter.
 */
val authPlatformOnlyClasses = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*_Factory.*",
    "**/*_Impl*.*",
    "**/*ComposableSingletons*.*",
    "**/ui/**",
    "**/databinding/**",
    "**/generated/**",
    "**/AndroidKeystoreEnvelope*.*",
    "**/*Activity*.*",
    "**/*Service*.*",
    "**/*Receiver*.*",
    "**/AndroidBiometricVaultKeyStore*.*",
    "**/AuthFeature*.*",
    "**/DefaultAuthComponent*.*",
    "**/LockLifecycleInstaller*.*",
)
tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
    classDirectories.setFrom(
        files(
            layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        ).asFileTree.matching { exclude(authPlatformOnlyClasses) },
    )
}
