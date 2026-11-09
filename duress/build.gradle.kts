plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.keeper.vault.duress"
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":contracts"))
    implementation(project(":crypto"))
    implementation(project(":vault"))
    implementation(project(":auth"))
    implementation(project(":isolation"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.work.runtime.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

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
    testImplementation(libs.work.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.work.testing)
}

apply(from = rootProject.file("gradle/jacoco-module.gradle.kts"))

/*
 * Phase 6 coverage rule.
 *
 * The logic is NOT excluded: settings validation/persistence, the coordinator, retention cutoff
 * arithmetic, the encrypted image log, the dedicated IntruderKey store (against a JVM fake
 * Keystore), the purge decision and the duress/decoy controller are all unit-tested.
 *
 * Only Android/platform adapters are excluded, and each one is either compile-checked or covered
 * by the instrumented tests under src/androidTest that require a device/emulator:
 * - AndroidDuressWiring / IntruderStorage / RoomDecoyVault: manual DI + Room factories;
 * - AndroidIntruderKeys: the real Android Keystore adapter (StrongBox/TEE);
 * - ServiceIntruderCapture / IntruderCaptureService: CameraX foreground-service plumbing;
 * - IntruderNotifications: notification channel (instrumented notification test);
 * - IntruderPurgeWorker / IntruderPurgeScheduler: WorkManager entry points (instrumented
 *   scheduling test); the retention arithmetic they call is included above;
 * - SharedPreferencesIntruderSettingsPersistence: preferences adapter around the tested codec;
 * - IntruderDatabase/IntruderDatabase_Impl/DAO_Impl: generated Room boilerplate.
 */
val duressPlatformOnlyClasses = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*_Factory.*",
    "**/*_Impl*.*",
    "**/*ComposableSingletons*.*",
    "**/*Activity*.*",
    "**/*Service*.*",
    "**/*Receiver*.*",
    "**/ui/**",
    "**/databinding/**",
    "**/generated/**",
    "**/AndroidDuressWiring*.*",
    "**/IntruderStorage*.*",
    "**/AndroidIntruderKeys*.*",
    "**/ServiceIntruderCapture*.*",
    "**/IntruderNotifications*.*",
    "**/IntruderPurgeWorker*.*",
    "**/IntruderPurgeScheduler*.*",
    "**/SharedPreferencesIntruderSettingsPersistence*.*",
    "**/RoomDecoyVault*.*",
    "**/IntruderDatabase*.*",
)
tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
    classDirectories.setFrom(
        files(
            layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        ).asFileTree.matching { exclude(duressPlatformOnlyClasses) },
    )
}
