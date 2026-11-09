package com.keeper.vault.presence.shield

import com.google.common.truth.Truth.assertThat
import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.PresenceConfig
import com.keeper.vault.contracts.presence.PresenceEvent
import com.keeper.vault.presence.face.L2FaceMatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPresenceShieldTest {
    @Test
    fun noFaceIsDelayedByStartupGracePlusTheNoFaceTimeout() = runTest {
        val source = FakeFrameSource(frames(40) { emptyList() }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = FakeFaceDetector(default = emptyList()),
            config = PresenceConfig(startupGraceMillis = 1_500L, noFaceTimeoutMillis = 2_000L),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(3_300L)
        runCurrent()

        assertThat(recorded.map { it.event }).doesNotContain(PresenceEvent.NoFace)

        advanceTimeBy(900L)
        runCurrent()

        val noFace = recorded.first { it.event == PresenceEvent.NoFace }
        assertThat(noFace.atMillis).isAtLeast(3_500L)
        assertThat(noFace.atMillis).isLessThan(4_200L)
    }

    @Test
    fun ownerPresentThenNoFaceAfterTwoSecondsWithoutAFace() = runTest {
        val detector = FakeFaceDetector()
        detector.enqueue(listOf(face()))
        detector.enqueue(listOf(face()))
        repeat(8) { detector.enqueue(emptyList()) }
        val source = FakeFrameSource(frames(10) { listOf(face()) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = detector,
            config = PresenceConfig(requiredConsecutiveMatches = 2),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(6_000L)
        runCurrent()

        assertThat(recorded.map { it.event }).containsAtLeast(
            PresenceEvent.OwnerPresent,
            PresenceEvent.NoFace,
        )
        val owner = recorded.first { it.event == PresenceEvent.OwnerPresent }
        val noFace = recorded.first { it.event == PresenceEvent.NoFace }
        assertThat(owner.atMillis).isEqualTo(700L)
        assertThat(noFace.atMillis).isAtLeast(2_700L)
    }

    @Test
    fun moreThanOneFaceIsReportedImmediately() = runTest {
        val detector = FakeFaceDetector(default = listOf(face(), face(trackingId = 2)))
        val source = FakeFrameSource(frames(10) { listOf(face(), face(trackingId = 2)) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = detector,
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(100L)
        runCurrent()

        assertThat(recorded.first().event).isEqualTo(PresenceEvent.MultipleFaces)
        assertThat(recorded.first().atMillis).isEqualTo(0L)
    }

    @Test
    fun distanceOverThresholdIsAMismatch() = runTest {
        val detector = FakeFaceDetector(default = listOf(face()))
        val embedder = FakeFaceEmbedder(default = FaceEmbedding(floatArrayOf(50f, 50f, 50f)))
        val source = FakeFrameSource(frames(10) { listOf(face()) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = detector,
            embedder = embedder,
            config = PresenceConfig(requiredConsecutiveMatches = 1),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(1_500L)
        runCurrent()

        assertThat(recorded.map { it.event }).contains(PresenceEvent.OwnerMismatch)
        assertThat(recorded.map { it.event }).doesNotContain(PresenceEvent.OwnerPresent)
    }

    @Test
    fun blinkChallengeUsesABurstThenReturnsToTheContinuousRate() = runTest {
        val detector = FakeFaceDetector()
        detector.enqueue(listOf(face()))
        detector.enqueue(listOf(face()))
        detector.enqueue(listOf(face()))
        detector.enqueue(listOf(face(leftOpen = 0.05f, rightOpen = 0.05f)))
        detector.enqueue(listOf(face()))
        repeat(6) { detector.enqueue(listOf(face())) }
        val source = FakeFrameSource(frames(12) { listOf(face()) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = detector,
            config = PresenceConfig(
                continuousAnalysisIntervalMillis = 700L,
                challengeBurstFps = 15,
                challengeBurstMillis = 1_500L,
                requiredConsecutiveMatches = 2,
            ),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(3_000L)
        runCurrent()

        assertThat(recorded.map { it.event }).contains(PresenceEvent.LivenessPassed)
        val requests = source.requests
        assertThat(requests.size).isAtLeast(6)
        // 0 ms -> 700 ms continuous; 700 ms -> 766 ms challenge burst.
        assertThat(requests[1] - requests[0]).isEqualTo(700L)
        assertThat(requests[2] - requests[1]).isIn(55L..80L)
        assertThat(requests[3] - requests[2]).isIn(55L..80L)
        // After the blink, analysis returns to 1-2 fps.
        assertThat(requests[5] - requests[4]).isIn(690L..710L)
    }

    @Test
    fun noBlinkWithinTenSecondsIsABlinkTimeout() = runTest {
        val detector = FakeFaceDetector(default = listOf(face()))
        val source = FakeFrameSource(frames(40) { listOf(face()) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = detector,
            config = PresenceConfig(
                continuousAnalysisIntervalMillis = 700L,
                blinkTimeoutMillis = 10_000L,
                requiredConsecutiveMatches = 2,
            ),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(12_500L)
        runCurrent()

        val timeout = recorded.first { it.event == PresenceEvent.BlinkTimeout }
        assertThat(timeout.atMillis).isAtLeast(10_700L)
        assertThat(recorded.count { it.event == PresenceEvent.BlinkTimeout }).isEqualTo(1)
    }

    @Test
    fun analysisIsSuspendedWhileTheGateIsClosed() = runTest {
        val gate = MutablePresenceGate(false)
        val source = FakeFrameSource(frames(30) { listOf(face()) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = gate,
            source = source,
            detector = FakeFaceDetector(default = listOf(face())),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(3_000L)
        runCurrent()

        assertThat(source.requests).isEmpty()
        assertThat(recorded).isEmpty()

        gate.set(true)
        advanceTimeBy(2_000L)
        runCurrent()

        assertThat(source.requests).isNotEmpty()

        val requestsWhileOpen = source.requests.size
        gate.set(false)
        advanceTimeBy(3_000L)
        runCurrent()

        assertThat(source.requests.size).isEqualTo(requestsWhileOpen)
    }

    @Test
    fun missingEnrollmentFailsClosedWithoutEmbedding() = runTest {
        val embedder = FakeFaceEmbedder()
        val source = FakeFrameSource(frames(10) { listOf(face()) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = FakeFaceDetector(default = listOf(face())),
            embedder = embedder,
            enrolled = null,
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(2_000L)
        runCurrent()

        assertThat(recorded.map { it.event }.filterIsInstance<PresenceEvent.Failure>()).isNotEmpty()
        assertThat(embedder.calls).isEqualTo(0)
    }

    @Test
    fun requiredConsecutiveMatchesMustBeReached() = runTest {
        val detector = FakeFaceDetector()
        detector.enqueue(listOf(face()))
        detector.enqueue(listOf(face()))
        repeat(6) { detector.enqueue(listOf(face())) }
        val embedder = FakeFaceEmbedder()
        // First two frames are owner-like, then a clear mismatch for the third.
        embedder.enqueue(PresenceTestFixtures.ownerEmbedding())
        embedder.enqueue(PresenceTestFixtures.ownerEmbedding())
        embedder.enqueue(FaceEmbedding(floatArrayOf(50f, 50f, 50f)))
        val source = FakeFrameSource(frames(8) { listOf(face()) }, now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = detector,
            embedder = embedder,
            config = PresenceConfig(requiredConsecutiveMatches = 3),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(2_000L)
        runCurrent()

        assertThat(recorded.map { it.event }).doesNotContain(PresenceEvent.OwnerPresent)
        assertThat(recorded.map { it.event }).contains(PresenceEvent.OwnerMismatch)
    }

    @Test
    fun aCameraFrameFailureIsReportedOnce() = runTest {
        val source = FakeFrameSource(emptyList(), now = { testScheduler.currentTime })
        val shield = shield(
            gate = MutablePresenceGate(true),
            source = source,
            detector = FakeFaceDetector(),
        )
        val recorded = record(shield)

        shield.start()
        advanceTimeBy(3_000L)
        runCurrent()

        assertThat(recorded.map { it.event }.filterIsInstance<PresenceEvent.Failure>()).hasSize(1)
    }

    private fun TestScope.shield(
        gate: PresenceGate,
        source: PresenceFrameSource,
        detector: FakeFaceDetector,
        embedder: FakeFaceEmbedder = FakeFaceEmbedder(),
        config: PresenceConfig = PresenceConfig(),
        enrolled: FaceEmbedding? = PresenceTestFixtures.ownerEmbedding(),
    ): DefaultPresenceShield = DefaultPresenceShield(
        config = config,
        gate = gate,
        detector = detector,
        embedder = embedder,
        matcher = L2FaceMatcher(),
        clock = Clock { testScheduler.currentTime },
        scope = backgroundScope,
        initialFrameSource = source,
    ).also { shield ->
        shield.updateEnrolled(enrolled)
    }

    private fun TestScope.record(shield: DefaultPresenceShield): MutableList<RecordedEvent> {
        val recorded = mutableListOf<RecordedEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shield.events.collect { event ->
                recorded += RecordedEvent(event, testScheduler.currentTime)
            }
        }
        return recorded
    }

    private fun frames(count: Int, block: (Int) -> List<com.keeper.vault.contracts.presence.DetectedFace>): List<PresenceFrame> =
        List(count) { PresenceFrame(PresenceTestFixtures.image(), timestampMillis = it * 700L) }

    private fun face(leftOpen: Float = 0.9f, rightOpen: Float = 0.9f, trackingId: Int = 1) =
        PresenceTestFixtures.face(leftOpen = leftOpen, rightOpen = rightOpen, trackingId = trackingId)

    private data class RecordedEvent(val event: PresenceEvent, val atMillis: Long)
}
