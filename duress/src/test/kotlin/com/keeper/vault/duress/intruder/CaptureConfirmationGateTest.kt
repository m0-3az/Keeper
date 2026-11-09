package com.keeper.vault.duress.intruder

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class CaptureConfirmationGateTest {
    @After
    fun tearDown() {
        CaptureConfirmationGate.clearForTests()
    }

    @Test
    fun aTokenCanBeConsumedExactlyOnce() {
        val token = CaptureConfirmationGate.issue(nowMillis = 1_000L)

        assertThat(CaptureConfirmationGate.consume(token, nowMillis = 1_001L)).isTrue()
        assertThat(CaptureConfirmationGate.consume(token, nowMillis = 1_002L)).isFalse()
    }

    @Test
    fun missingOrBlankTokensAreRejected() {
        assertThat(CaptureConfirmationGate.consume(null, nowMillis = 1_000L)).isFalse()
        assertThat(CaptureConfirmationGate.consume("", nowMillis = 1_000L)).isFalse()
        assertThat(CaptureConfirmationGate.consume("not-issued", nowMillis = 1_000L)).isFalse()
    }

    @Test
    fun validationDoesNotConsumeTheToken() {
        val token = CaptureConfirmationGate.issue(nowMillis = 1_000L)

        assertThat(CaptureConfirmationGate.isValid(token, nowMillis = 1_001L)).isTrue()
        assertThat(CaptureConfirmationGate.isValid(token, nowMillis = 1_002L)).isTrue()
        assertThat(CaptureConfirmationGate.isValid("forged", nowMillis = 1_002L)).isFalse()
        assertThat(CaptureConfirmationGate.consume(token, nowMillis = 1_003L)).isTrue()
    }

    @Test
    fun revokedTokensAreRejected() {
        val token = CaptureConfirmationGate.issue(nowMillis = 1_000L)

        CaptureConfirmationGate.revoke(token)

        assertThat(CaptureConfirmationGate.isValid(token, nowMillis = 1_001L)).isFalse()
        assertThat(CaptureConfirmationGate.consume(token, nowMillis = 1_001L)).isFalse()
    }

    @Test
    fun expiredTokensAreRejected() {
        val expired = CaptureConfirmationGate.issue(nowMillis = 1_000L, ttlMillis = 500L)
        assertThat(CaptureConfirmationGate.consume(expired, nowMillis = 1_501L)).isFalse()

        val boundary = CaptureConfirmationGate.issue(nowMillis = 1_000L, ttlMillis = 500L)
        assertThat(CaptureConfirmationGate.consume(boundary, nowMillis = 1_500L)).isTrue()
    }
}
