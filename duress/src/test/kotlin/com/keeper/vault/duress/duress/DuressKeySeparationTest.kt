package com.keeper.vault.duress.duress

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.crypto.KeyDerivation
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.crypto.DefaultSecureRandomSource
import com.keeper.vault.crypto.DefaultVaultKeyManager
import com.keeper.vault.crypto.FileEnvelopeStore
import com.keeper.vault.duress.FakeKeystoreEnvelope
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Key-envelope separation with the real [DefaultVaultKeyManager].
 *
 * PBKDF2 itself is already covered by `:crypto`; the test derivation is a fast 32-byte SHA-256 so
 * this suite checks envelope separation, not the KDF cost.
 */
class DuressKeySeparationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fastDerivation = KeyDerivation { secret, salt, _ ->
        MessageDigest.getInstance("SHA-256").run {
            update(String(secret).toByteArray(Charsets.UTF_8))
            update(salt)
            digest()
        }
    }

    private fun manager(name: String, envelope: FakeKeystoreEnvelope): DefaultVaultKeyManager =
        DefaultVaultKeyManager(
            keyDerivation = fastDerivation,
            keystore = envelope,
            envelopeStore = FileEnvelopeStore(temp.newFolder(name)),
            random = DefaultSecureRandomSource(),
            defaultDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun `a real secret cannot unlock the decoy envelope`() = runTest {
        val real = manager("real", FakeKeystoreEnvelope())
        val decoy = manager("decoy", FakeKeystoreEnvelope())
        real.create("real-secret".toCharArray()).close()
        decoy.create("duress-secret".toCharArray()).close()

        val failure = runCatching { decoy.unlock("real-secret".toCharArray()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `the decoy secret cannot unlock the real envelope`() = runTest {
        val real = manager("real", FakeKeystoreEnvelope())
        val decoy = manager("decoy", FakeKeystoreEnvelope())
        real.create("real-secret".toCharArray()).close()
        decoy.create("duress-secret".toCharArray()).close()

        val failure = runCatching { real.unlock("duress-secret".toCharArray()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(KeeperException.Crypto::class.java)
    }

    @Test
    fun `each envelope produces a different vault key`() = runTest {
        val real = manager("real", FakeKeystoreEnvelope())
        val decoy = manager("decoy", FakeKeystoreEnvelope())

        val realKey = real.create("real-secret".toCharArray())
        val decoyKey = decoy.create("duress-secret".toCharArray())
        val realBytes = realKey.copyBytes()
        val decoyBytes = decoyKey.copyBytes()
        try {
            assertThat(realBytes).isNotEqualTo(decoyBytes)
            assertThat(File(temp.root, "real/envelope.bin").isFile).isTrue()
            assertThat(File(temp.root, "decoy/envelope.bin").isFile).isTrue()
        } finally {
            realKey.close()
            decoyKey.close()
            realBytes.fill(0)
            decoyBytes.fill(0)
        }
    }
}
