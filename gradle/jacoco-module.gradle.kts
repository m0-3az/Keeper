import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.api.tasks.testing.Test

plugins.apply("jacoco")

configure<JacocoPluginExtension> {
    toolVersion = "0.8.16"
}

val keeperCoverageExcludes = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*_Factory.*",
    "**/*_Impl.*",
    "**/*ComposableSingletons*.*",
    "**/ui/**",
    "**/databinding/**",
    "**/generated/**",
    // Platform adapters are exercised by instrumented tests, not JVM unit tests.
    "**/AndroidKeystoreEnvelope*.*",
    // Android UI/platform entry points are instrumented-tested; logic lives below them.
    "**/*Activity*.*",
    "**/*Service*.*",
    "**/*Receiver*.*",
)

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val jacocoDebugReport = tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    group = "verification"
    description = "JaCoCo report for debug unit tests."
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val buildDirProvider = layout.buildDirectory
    classDirectories.setFrom(
        files(
            buildDirProvider.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
            buildDirProvider.dir("tmp/kotlin-classes/debug"),
            buildDirProvider.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        ).asFileTree.matching { exclude(keeperCoverageExcludes) },
    )
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
    executionData.setFrom(buildDirProvider.file("jacoco/testDebugUnitTest.exec"))

    doFirst {
        val roots = classDirectories.files
        val hasClasses = roots.any { it.isFile && it.extension == "class" } ||
            roots.any { root ->
                root.isDirectory && root.walkTopDown().any { it.isFile && it.extension == "class" }
            }
        if (!hasClasses) {
            throw GradleException(
                "JaCoCo found no class files. Check the Kotlin/Java output paths for this AGP version.",
            )
        }
    }
}

tasks.register<JacocoCoverageVerification>("jacocoDebugUnitTestCoverage") {
    group = "verification"
    description = "Fails if debug unit-test instruction coverage is below 80%."
    dependsOn(jacocoDebugReport)

    val report = jacocoDebugReport.get()
    classDirectories.setFrom(report.classDirectories)
    sourceDirectories.setFrom(report.sourceDirectories)
    executionData.setFrom(report.executionData)

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
