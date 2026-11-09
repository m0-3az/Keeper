package com.keeper.vault.isolation

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.isolation.TamperKind
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidProbeFactoryTest {

    @Test
    fun `factory wires every source into probes`() = runTest {
        val sources = FakeSources().apply {
            existingPaths = setOf("/system/bin/su")
            packages = setOf("com.topjohnwu.magisk")
            tags = "release-keys,test-keys"
            debugger = true
            debuggableBuild = true
            services = listOf("com.example.reader/.Service")
            buildFields = BUILD_FIELDS.copy(fingerprint = "generic/sdk_gphone64_x86_64/emu64xa:14")
            mappings = listOf("/data/local/tmp/libfrida-agent.so")
            classes = setOf("de.robv.android.xposed.XposedBridge")
        }

        val findings = AndroidProbeFactory(sources).create().flatMap { it.probe() }

        assertThat(findings.map { it.kind }).containsAtLeast(
            TamperKind.ROOT,
            TamperKind.DEBUGGER,
            TamperKind.ACCESSIBILITY_SERVICE,
            TamperKind.EMULATOR,
            TamperKind.HOOK_FRAMEWORK,
        )
    }

    @Test
    fun `factory queries installed packages once for root and hook probes`() = runTest {
        val sources = FakeSources()

        AndroidProbeFactory(sources).create()

        assertThat(sources.installedPackagesCalls).isEqualTo(1)
    }

    @Test
    fun `factory builds a quiet probe list on a clean device`() = runTest {
        val sources = FakeSources()

        val findings = AndroidProbeFactory(sources).create().flatMap { it.probe() }

        assertThat(findings).isEmpty()
    }

    private class FakeSources : AndroidProbeSources {
        var existingPaths: Set<String> = emptySet()
        var packages: Set<String> = emptySet()
        var tags: String? = "release-keys"
        var debugger: Boolean = false
        var debuggableBuild: Boolean = false
        var services: List<String> = emptyList()
        var buildFields: BuildFields = BUILD_FIELDS
        var mappings: List<String> = emptyList()
        var classes: Set<String> = emptySet()
        var installedPackagesCalls: Int = 0
            private set

        override fun fileExists(path: String): Boolean = path in existingPaths

        override fun installedPackages(): Set<String> {
            installedPackagesCalls++
            return packages
        }

        override fun debuggable(): Boolean = debuggableBuild

        override fun buildTags(): String? = tags

        override fun debuggerConnected(): Boolean = debugger

        override fun accessibilityServices(): List<String> = services

        override fun buildFields(): BuildFields = buildFields

        override fun processMappings(): List<String> = mappings

        override fun classExists(className: String): Boolean = className in classes
    }

    private companion object {
        val BUILD_FIELDS = BuildFields(
            fingerprint = "google/panther/panther:14/UQ1A/1:user/release-keys",
            model = "Pixel 7",
            hardware = "panther",
            product = "panther",
            manufacturer = "Google",
            brand = "google",
            device = "panther",
        )
    }
}
