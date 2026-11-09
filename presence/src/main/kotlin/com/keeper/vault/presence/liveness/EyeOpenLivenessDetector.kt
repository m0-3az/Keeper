package com.keeper.vault.presence.liveness

import com.keeper.vault.contracts.presence.DetectedFace
import com.keeper.vault.contracts.presence.LivenessDetector
import com.keeper.vault.contracts.presence.LivenessState

/**
 * Eye-open-probability blink detector.
 *
 * A blink is a closed eye (either eye below [eyeClosedProbability]) followed by an open pair
 * (both eyes above [eyeOpenProbability]) within [maxBlinkDurationMillis]. If the probabilities are
 * missing the detector cannot prove liveness and the challenge times out.
 *
 * This is a deterrent, not spoof-proof liveness. The controller supplies the two-rate sampling:
 * a short high-rate burst after the owner is matched, then the 1-2 fps continuous rate.
 */
class EyeOpenLivenessDetector(
    private val eyeClosedProbability: Float = 0.25f,
    private val eyeOpenProbability: Float = 0.65f,
    private val maxBlinkDurationMillis: Long = 1_000L,
    private val blinkTimeoutMillis: Long = 10_000L,
) : LivenessDetector {
    private var state: LivenessState = LivenessState.Idle
    private var firstSeenAtMillis: Long? = null
    private var closedAtMillis: Long? = null

    override fun reset() {
        state = LivenessState.Idle
        firstSeenAtMillis = null
        closedAtMillis = null
    }

    override fun onFrame(faces: List<DetectedFace>, timestampMillis: Long): LivenessState {
        if (faces.size > 1) {
            clearChallenge()
            state = LivenessState.MultipleFaces
            return state
        }
        if (faces.isEmpty()) {
            clearChallenge()
            state = LivenessState.WaitingForFace
            return state
        }

        if (state == LivenessState.BlinkDetected || state == LivenessState.TimedOut) {
            return state
        }

        if (firstSeenAtMillis == null ||
            state == LivenessState.Idle ||
            state == LivenessState.WaitingForFace ||
            state == LivenessState.MultipleFaces
        ) {
            firstSeenAtMillis = timestampMillis
            closedAtMillis = null
            state = LivenessState.WaitingForBlink
        }

        val firstSeen = firstSeenAtMillis ?: timestampMillis
        if (timestampMillis - firstSeen >= blinkTimeoutMillis) {
            state = LivenessState.TimedOut
            return state
        }

        val face = faces.first()
        when {
            isClosed(face) -> {
                if (closedAtMillis == null) {
                    closedAtMillis = timestampMillis
                }
                state = LivenessState.WaitingForBlink
            }

            isOpen(face) -> {
                val closedAt = closedAtMillis
                if (closedAt != null && timestampMillis - closedAt <= maxBlinkDurationMillis) {
                    state = LivenessState.BlinkDetected
                    return state
                }
                closedAtMillis = null
                state = LivenessState.WaitingForBlink
            }

            else -> {
                state = LivenessState.WaitingForBlink
            }
        }
        return state
    }

    private fun isClosed(face: DetectedFace): Boolean {
        val left = face.leftEyeOpenProbability
        val right = face.rightEyeOpenProbability
        return (left != null && left < eyeClosedProbability) ||
            (right != null && right < eyeClosedProbability)
    }

    private fun isOpen(face: DetectedFace): Boolean {
        val left = face.leftEyeOpenProbability
        val right = face.rightEyeOpenProbability
        return left != null && right != null &&
            left > eyeOpenProbability && right > eyeOpenProbability
    }

    private fun clearChallenge() {
        firstSeenAtMillis = null
        closedAtMillis = null
    }
}
