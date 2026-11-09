package com.keeper.vault.presence.liveness

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.presence.DetectedFace
import com.keeper.vault.contracts.presence.IntRect
import com.keeper.vault.contracts.presence.LivenessState
import org.junit.Test

class EyeOpenLivenessDetectorTest {
    private val detector = EyeOpenLivenessDetector(blinkTimeoutMillis = 10_000L)

    @Test
    fun idleBeforeAnyFrame() {
        assertThat(detector.onFrame(emptyList(), 0L)).isEqualTo(LivenessState.WaitingForFace)
    }

    @Test
    fun multipleFacesFailsTheChallenge() {
        val state = detector.onFrame(listOf(face(), face()), 0L)

        assertThat(state).isEqualTo(LivenessState.MultipleFaces)
    }

    @Test
    fun aFaceStartsWaitingForBlink() {
        assertThat(detector.onFrame(listOf(face()), 0L)).isEqualTo(LivenessState.WaitingForBlink)
    }

    @Test
    fun closedThenOpenEyeIsABlink() {
        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 0L))
            .isEqualTo(LivenessState.WaitingForBlink)
        assertThat(detector.onFrame(listOf(face(openProbability = 0.05f)), 100L))
            .isEqualTo(LivenessState.WaitingForBlink)
        assertThat(detector.onFrame(listOf(face(openProbability = 0.95f)), 200L))
            .isEqualTo(LivenessState.BlinkDetected)
    }

    @Test
    fun blinkResultIsStickyUntilReset() {
        detector.onFrame(listOf(face(openProbability = 0.9f)), 0L)
        detector.onFrame(listOf(face(openProbability = 0.1f)), 100L)
        detector.onFrame(listOf(face(openProbability = 0.9f)), 200L)

        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 300L))
            .isEqualTo(LivenessState.BlinkDetected)

        detector.reset()
        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 400L))
            .isEqualTo(LivenessState.WaitingForBlink)
    }

    @Test
    fun aLongClosureIsNotABlink() {
        detector.onFrame(listOf(face(openProbability = 0.9f)), 0L)
        detector.onFrame(listOf(face(openProbability = 0.1f)), 100L)
        detector.onFrame(listOf(face(openProbability = 0.9f)), 2_000L)

        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 2_100L))
            .isEqualTo(LivenessState.WaitingForBlink)
    }

    @Test
    fun noBlinkWithinTheTimeoutFails() {
        detector.onFrame(listOf(face(openProbability = 0.9f)), 1_000L)

        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 10_999L))
            .isEqualTo(LivenessState.WaitingForBlink)
        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 11_000L))
            .isEqualTo(LivenessState.TimedOut)
    }

    @Test
    fun missingProbabilitiesCannotPassLiveness() {
        detector.onFrame(listOf(face(openProbability = null)), 0L)

        assertThat(detector.onFrame(listOf(face(openProbability = null)), 10_000L))
            .isEqualTo(LivenessState.TimedOut)
    }

    @Test
    fun oneClosedEyeCountsAsClosed() {
        detector.onFrame(listOf(face(leftOpen = 0.9f, rightOpen = 0.9f)), 0L)
        detector.onFrame(listOf(face(leftOpen = 0.05f, rightOpen = 0.9f)), 100L)

        assertThat(detector.onFrame(listOf(face(leftOpen = 0.9f, rightOpen = 0.9f)), 200L))
            .isEqualTo(LivenessState.BlinkDetected)
    }

    @Test
    fun losingTheFaceRestartsTheChallengeWindow() {
        detector.onFrame(listOf(face(openProbability = 0.9f)), 0L)
        assertThat(detector.onFrame(emptyList(), 9_000L)).isEqualTo(LivenessState.WaitingForFace)
        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 9_500L))
            .isEqualTo(LivenessState.WaitingForBlink)
        assertThat(detector.onFrame(listOf(face(openProbability = 0.9f)), 19_500L))
            .isEqualTo(LivenessState.TimedOut)
    }

    private fun face(
        openProbability: Float? = 0.9f,
        leftOpen: Float? = openProbability,
        rightOpen: Float? = openProbability,
    ): DetectedFace = DetectedFace(
        bounds = IntRect(10, 10, 90, 90),
        leftEye = null,
        rightEye = null,
        leftEyeOpenProbability = leftOpen,
        rightEyeOpenProbability = rightOpen,
        trackingId = 1,
    )
}
