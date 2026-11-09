package com.keeper.vault.auth

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.SecretValidation
import org.junit.Test

class DefaultSecretValidatorTest {
    private val validator = DefaultSecretValidator()

    @Test
    fun `password enforces the minimum length`() {
        assertThat(validator.validate(SecretType.PASSWORD, "short".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
        assertThat(validator.validate(SecretType.PASSWORD, "long enough".toCharArray()))
            .isEqualTo(SecretValidation.Valid)
    }

    @Test
    fun `password enforces the maximum length`() {
        val tooLong = CharArray(SecretPolicies.PASSWORD.maxLength + 1) { 'a' }
        val atLimit = CharArray(SecretPolicies.PASSWORD.maxLength) { 'a' }

        assertThat(validator.validate(SecretType.PASSWORD, tooLong))
            .isInstanceOf(SecretValidation.Invalid::class.java)
        assertThat(validator.validate(SecretType.PASSWORD, atLimit))
            .isEqualTo(SecretValidation.Valid)
    }

    @Test
    fun `empty and whitespace-only secrets are invalid`() {
        assertThat(validator.validate(SecretType.PASSWORD, CharArray(0)))
            .isInstanceOf(SecretValidation.Invalid::class.java)
        assertThat(validator.validate(SecretType.PASSWORD, "        ".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
    }

    @Test
    fun `pin accepts four to twelve digits only`() {
        assertThat(validator.validate(SecretType.PIN, "1234".toCharArray()))
            .isEqualTo(SecretValidation.Valid)
        assertThat(validator.validate(SecretType.PIN, "123456789012".toCharArray()))
            .isEqualTo(SecretValidation.Valid)
        assertThat(validator.validate(SecretType.PIN, "123".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
        assertThat(validator.validate(SecretType.PIN, "1234567890123".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
        assertThat(validator.validate(SecretType.PIN, "12a4".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
    }

    @Test
    fun `pattern requires four to nine distinct grid nodes`() {
        assertThat(validator.validate(SecretType.PATTERN, "0123".toCharArray()))
            .isEqualTo(SecretValidation.Valid)
        assertThat(validator.validate(SecretType.PATTERN, "012345678".toCharArray()))
            .isEqualTo(SecretValidation.Valid)
        assertThat(validator.validate(SecretType.PATTERN, "012".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
        assertThat(validator.validate(SecretType.PATTERN, "0012".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
        assertThat(validator.validate(SecretType.PATTERN, "0129".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
    }

    @Test
    fun `signature validates the canonical character length`() {
        assertThat(validator.validate(SecretType.SIGNATURE, "S1abcdefghijklmnop".toCharArray()))
            .isEqualTo(SecretValidation.Valid)
        assertThat(validator.validate(SecretType.SIGNATURE, "S1short".toCharArray()))
            .isInstanceOf(SecretValidation.Invalid::class.java)
    }

    @Test
    fun `policies exist for every secret type and allow the biometric path`() {
        SecretType.entries.forEach { type ->
            val policy = SecretPolicies.forType(type)
            assertThat(policy.minLength).isAtLeast(1)
            assertThat(policy.maxLength).isAtLeast(policy.minLength)
            assertThat(policy.allowBiometricConvenience).isTrue()
        }
    }
}
