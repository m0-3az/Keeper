package com.keeper.vault.duress.duress

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.KeyDerivation
import com.keeper.vault.crypto.DefaultSecureRandomSource
import com.keeper.vault.crypto.DefaultVaultKeyManager
import com.keeper.vault.crypto.FileEnvelopeStore
import com.keeper.vault.duress.FakeKeystoreEnvelope
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RealVaultSecretProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fastDerivation = KeyDerivation { secret, salt, _ ->
        MessageDigest.getInstance("SHA-256").run {
            update(String(secret).toByteArray(Charsets.UTF_8))
            update(salt)
            digest()
        }
    }

    private fun manager(name: String = "real"): DefaultVaultKeyManager = DefaultVaultKeyManager(
        keyDerivation = fastDerivation,
        keystore = FakeKeystoreEnvelope(),
        envelopeStore = FileEnvelopeStore(temp.newFolder(name)),
        random = DefaultSecureRandomSource(),
        defaultDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `the probe recognises the real secret for every domain-separated type`() = runTest {
        val secret = "correct horse".toCharArray()
        for ((index, prefix) in AuthDomainPrefixes.ALL.withIndex()) {
            val manager = manager("real-$index")
            manager.create(prefix + secret).close()

            val probe = EnvelopeRealVaultSecretProbe(manager)

            assertThat(probe.unlocksRealVault(secret.copyOf())).isTrue()
        }
    }

    @Test
    fun `the probe rejects a different secret`() = runTest {
        val manager = manager()
        manager.create(AuthDomainPrefixes.PASSWORD + "correct horse".toCharArray()).close()
        val probe = EnvelopeRealVaultSecretProbe(manager)

        assertThat(probe.unlocksRealVault("wrong horse".toCharArray())).isFalse()
    }
}
