package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.crypto.BiometricEnvelope
import org.junit.Test

class PreferencesAdaptersTest {
    private val prefs = FakeSharedPreferences()

    @Test
    fun `attempt state round-trips through shared preferences`() {
        val store = SharedPreferencesAttemptStateStore(prefs)

        store.write(PersistedAttempts(failures = 5, cooldownUntilMillis = 123_456L))
        val loaded = store.read()

        assertThat(loaded.failures).isEqualTo(5)
        assertThat(loaded.cooldownUntilMillis).isEqualTo(123_456L)
    }

    @Test
    fun `a missing cooldown is read as null`() {
        val store = SharedPreferencesAttemptStateStore(prefs)

        store.write(PersistedAttempts(failures = 2, cooldownUntilMillis = null))

        assertThat(store.read()).isEqualTo(PersistedAttempts(2, null))
    }

    @Test
    fun `secret type metadata round-trips and ignores unknown values`() {
        val store = SharedPreferencesSecretTypeStore(prefs)

        assertThat(store.load()).isNull()
        store.save(SecretType.PATTERN)
        assertThat(store.load()).isEqualTo(SecretType.PATTERN)

        prefs.values[SharedPreferencesSecretTypeStore.KEY_TYPE] = "NOT_A_TYPE"
        assertThat(store.load()).isNull()

        store.clear()
        assertThat(store.load()).isNull()
    }

    @Test
    fun `biometric envelope round-trips through shared preferences`() {
        val store = SharedPreferencesBiometricEnvelopeStore(prefs)
        val envelope = BiometricEnvelope(
            iv = ByteArray(12) { it.toByte() },
            ciphertext = ByteArray(48) { (it + 1).toByte() },
        )

        store.save(envelope)

        val loaded = store.load()
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.iv).isEqualTo(envelope.iv)
        assertThat(loaded.ciphertext).isEqualTo(envelope.ciphertext)
    }

    @Test
    fun `clearing the biometric envelope removes it`() {
        val store = SharedPreferencesBiometricEnvelopeStore(prefs)
        store.save(BiometricEnvelope(ByteArray(12) { 1 }, ByteArray(32) { 2 }))

        store.clear()

        assertThat(store.load()).isNull()
    }

    @Test
    fun `corrupt biometric envelope data is treated as absent`() {
        val store = SharedPreferencesBiometricEnvelopeStore(prefs)
        prefs.values[SharedPreferencesBiometricEnvelopeStore.KEY_ENVELOPE] = "not-base64"

        assertThat(store.load()).isNull()
    }

    @Test
    fun `codec rejects malformed payloads`() {
        assertThat(BiometricEnvelopeCodec.decode(null)).isNull()
        assertThat(BiometricEnvelopeCodec.decode("")).isNull()
        assertThat(BiometricEnvelopeCodec.decode("a:b")).isNull()
        assertThat(BiometricEnvelopeCodec.decode("bogus")).isNull()
    }

    @Test
    fun `codec round-trips an envelope`() {
        val envelope = BiometricEnvelope(ByteArray(12) { 4 }, ByteArray(32) { 9 })

        val encoded = BiometricEnvelopeCodec.encode(envelope)
        val decoded = BiometricEnvelopeCodec.decode(encoded)

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.iv).isEqualTo(envelope.iv)
        assertThat(decoded.ciphertext).isEqualTo(envelope.ciphertext)
    }
}
