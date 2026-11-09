package com.keeper.vault.contracts

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.auth.SecretPolicy
import com.keeper.vault.contracts.crypto.CryptoConstants
import com.keeper.vault.contracts.crypto.KeyEnvelope
import com.keeper.vault.contracts.crypto.VaultKey
import com.keeper.vault.contracts.intruder.IntruderSettings
import com.keeper.vault.contracts.platform.SecretZeroizer
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.PresenceConfig
import com.keeper.vault.contracts.settings.ThemeMode
import org.junit.Assert.assertThrows
import org.junit.Test

class ContractsSmokeTest {
    @Test
    fun `secret zeroizer overwrites characters and bytes`() {
        val chars = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val bytes = byteArrayOf(1, 2, 3, 4)
        val floats = floatArrayOf(1f, 2f, 3f)

        SecretZeroizer.zero(chars)
        SecretZeroizer.zero(bytes)
        SecretZeroizer.zero(floats)

        assertThat(chars).isEqualTo(CharArray(6))
        assertThat(bytes).isEqualTo(ByteArray(4))
        assertThat(floats).isEqualTo(FloatArray(3))
    }

    @Test
    fun `vault key rejects wrong length and redacts toString`() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultKey.from(ByteArray(16))
        }

        VaultKey.from(ByteArray(CryptoConstants.VAULT_KEY_BYTES)).use { key ->
            assertThat(key.toString()).doesNotContain("0")
            assertThat(key.copyBytes()).hasLength(CryptoConstants.VAULT_KEY_BYTES)
        }
    }

    @Test
    fun `key envelope enforces the 600k iteration floor`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyEnvelope(
                version = 1,
                salt = ByteArray(CryptoConstants.SALT_BYTES),
                iterations = 200_000,
                wrappedLayerA = byteArrayOf(1),
            )
        }

        val envelope = KeyEnvelope(
            version = 1,
            salt = ByteArray(CryptoConstants.SALT_BYTES),
            iterations = CryptoConstants.MIN_PBKDF2_ITERATIONS,
            wrappedLayerA = byteArrayOf(1, 2, 3),
        )
        assertThat(envelope.toString()).doesNotContain("1, 2, 3")
    }

    @Test
    fun `intruder defaults are opt in and thirty days`() {
        val settings = IntruderSettings()
        assertThat(settings.enabled).isFalse()
        assertThat(settings.captureOnFaceMismatch).isFalse()
        assertThat(settings.failureThreshold).isEqualTo(5)
        assertThat(settings.retentionDays).isEqualTo(30)

        assertThrows(IllegalArgumentException::class.java) {
            IntruderSettings(enabled = false, captureOnFaceMismatch = true)
        }
    }

    @Test
    fun `face embedding distance is zero for identical vectors`() {
        val a = FaceEmbedding(floatArrayOf(1f, 2f, 3f))
        val b = FaceEmbedding(floatArrayOf(1f, 2f, 3f))
        val c = FaceEmbedding(floatArrayOf(2f, 2f, 3f))

        assertThat(a.l2Distance(b)).isEqualTo(0f)
        assertThat(a.l2Distance(c)).isGreaterThan(0f)
        assertThat(a.toString()).doesNotContain("1.0")
    }

    @Test
    fun `presence config has a low continuous rate and a burst challenge`() {
        val config = PresenceConfig()
        assertThat(config.continuousAnalysisIntervalMillis).isAtLeast(500L)
        assertThat(config.challengeBurstFps).isAtLeast(10)
        assertThat(config.blinkTimeoutMillis).isEqualTo(10_000L)
        assertThat(config.noFaceTimeoutMillis).isEqualTo(2_000L)
    }

    @Test
    fun `secret policy rejects impossible ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecretPolicy(minLength = 10, maxLength = 4, allowBiometricConvenience = true)
        }
    }

    @Test
    fun `five themes exist`() {
        assertThat(ThemeMode.entries.map { it.name })
            .containsExactly("DARK", "LIGHT", "AMOLED", "MIDNIGHT", "FOREST")
    }
}
