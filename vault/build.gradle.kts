plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.keeper.vault.vault"
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
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

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
    testImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

apply(from = rootProject.file("gradle/jacoco-module.gradle.kts"))

/**
 * Keep the 80% gate focused on vault logic.
 *
 * Room 2.8's generated `_Impl` inner delegate classes are not matched by the shared
 * exclude patterns, and platform adapters (Context/Room wiring, dispatchers) are
 * exercised by instrumented tests, as the shared script already does for the Keystore adapter.
 * No repository, file-store, metadata-mapping, or session logic is excluded.
 */
val vaultCoverageExcludes = listOf(
    "**/R.class",
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
    "**/platform/**",
)

tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
    classDirectories.setFrom(
        files(
            layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        ).asFileTree.matching {
            exclude(vaultCoverageExcludes)
        },
    )
}
