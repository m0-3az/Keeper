package com.keeper.vault.auth

import com.keeper.vault.contracts.auth.SecretPolicy
import com.keeper.vault.contracts.auth.SecretType
import com.keeper.vault.contracts.auth.SecretValidation
import com.keeper.vault.contracts.auth.SecretValidator

/**
 * Setup policy per secret type.
 *
 * A PIN is deliberately allowed to be short enough to be usable, but the UI must warn that a
 * long password is stronger. The signature policy applies to the canonical representation
 * produced by [SignatureCanonicalizer], not to raw screen coordinates.
 */
object SecretPolicies {
    val PASSWORD: SecretPolicy = SecretPolicy(
        minLength = 8,
        maxLength = 256,
        allowBiometricConvenience = true,
    )
    val PIN: SecretPolicy = SecretPolicy(
        minLength = 4,
        maxLength = 12,
        allowBiometricConvenience = true,
    )
    val PATTERN: SecretPolicy = SecretPolicy(
        minLength = 4,
        maxLength = 9,
        allowBiometricConvenience = true,
    )
    val SIGNATURE: SecretPolicy = SecretPolicy(
        minLength = SignatureCanonicalizer.MIN_CANONICAL_LENGTH,
        maxLength = 1024,
        allowBiometricConvenience = true,
    )

    fun forType(type: SecretType): SecretPolicy = when (type) {
        SecretType.PASSWORD -> PASSWORD
        SecretType.PIN -> PIN
        SecretType.PATTERN -> PATTERN
        SecretType.SIGNATURE -> SIGNATURE
    }
}

/** Validates a secret against [SecretPolicies] without copying or storing it. */
class DefaultSecretValidator(
    private val policies: Map<SecretType, SecretPolicy> = SecretType.entries.associateWith(
        SecretPolicies::forType,
    ),
) : SecretValidator {
    override fun validate(type: SecretType, secret: CharArray): SecretValidation {
        val policy = policies.getValue(type)
        if (secret.isEmpty()) {
            return SecretValidation.Invalid("Secret must not be empty")
        }
        if (secret.size < policy.minLength) {
            return SecretValidation.Invalid(minimumReason(type, policy.minLength))
        }
        if (secret.size > policy.maxLength) {
            return SecretValidation.Invalid(maximumReason(type, policy.maxLength))
        }
        return when (type) {
            SecretType.PASSWORD -> {
                if (secret.all { it.isWhitespace() }) {
                    SecretValidation.Invalid("Password must contain a visible character")
                } else {
                    SecretValidation.Valid
                }
            }

            SecretType.PIN -> {
                if (secret.all { it in '0'..'9' }) {
                    SecretValidation.Valid
                } else {
                    SecretValidation.Invalid("PIN must contain digits only")
                }
            }

            SecretType.PATTERN -> validatePattern(secret)
            SecretType.SIGNATURE -> {
                if (secret.any { it.isISOControl() }) {
                    SecretValidation.Invalid("Signature data is malformed")
                } else {
                    SecretValidation.Valid
                }
            }
        }
    }

    private fun validatePattern(secret: CharArray): SecretValidation {
        val seen = BooleanArray(PATTERN_NODES)
        for (char in secret) {
            if (char !in '0'..'8') {
                return SecretValidation.Invalid("Pattern must use the nine grid dots")
            }
            val node = char - '0'
            if (seen[node]) {
                return SecretValidation.Invalid("Pattern must not repeat a dot")
            }
            seen[node] = true
        }
        return SecretValidation.Valid
    }

    private fun minimumReason(type: SecretType, minimum: Int): String = when (type) {
        SecretType.PASSWORD -> "Password must be at least $minimum characters"
        SecretType.PIN -> "PIN must be at least $minimum digits"
        SecretType.PATTERN -> "Pattern must connect at least $minimum dots"
        SecretType.SIGNATURE -> "Signature is too short"
    }

    private fun maximumReason(type: SecretType, maximum: Int): String = when (type) {
        SecretType.PASSWORD -> "Password must be at most $maximum characters"
        SecretType.PIN -> "PIN must be at most $maximum digits"
        SecretType.PATTERN -> "Pattern must connect at most $maximum dots"
        SecretType.SIGNATURE -> "Signature is too long"
    }

    private companion object {
        const val PATTERN_NODES = 9
    }
}
