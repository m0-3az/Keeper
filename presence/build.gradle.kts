plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.keeper.vault.presence"
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
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.face.detection)
    implementation(libs.litert)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

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

    // Real AesGcmVaultCipher is used by the encrypted enrollment-store tests. The presence
    // production code depends only on the crypto contracts; the concrete cipher is injected by
    // the app.
    testImplementation(project(":crypto"))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
}

apply(from = rootProject.file("gradle/jacoco-module.gradle.kts"))

/*
 * Presence coverage rule.
 *
 * The behavior layer (matcher, liveness state machine, presence controller, enrollment averaging
 * and the encrypted enrollment store) is unit-tested and is NOT excluded.
 *
 * Only hardware/OS entry points are excluded here: CameraX frame plumbing, the ML Kit detector
 * adapter, the LiteRT interpreter adapter, the foreground service, the enrollment activity, its
 * Compose screen and the notification/channel factory. Those are covered by the instrumented
 * tests under src/androidTest (service declaration/notification, activity FLAG_SECURE) and must
 * be exercised on a real device for camera/model behavior.
 */
val presencePlatformOnlyClasses = listOf(
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
    "**/CameraFrameSource*.*",
    "**/FaceImageBitmap*.*",
    "**/MLKitFaceDetector*.*",
    "**/MobileFaceNetEmbedder*.*",
    "**/PresenceNotifications*.*",
    "**/RgbaImageProxyConverter*.*",
    "**/PresenceShieldService*.*",
    "**/PresenceEnrollmentActivity*.*",
)
tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
    classDirectories.setFrom(
        files(
            layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        ).asFileTree.matching { exclude(presencePlatformOnlyClasses) },
    )
}
