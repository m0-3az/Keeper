package com.keeper.vault.presence.shield

import com.keeper.vault.contracts.platform.Clock
import com.keeper.vault.contracts.presence.FaceDetector
import com.keeper.vault.contracts.presence.FaceEmbedder
import com.keeper.vault.contracts.presence.FaceEmbedding
import com.keeper.vault.contracts.presence.FaceMatcher
import com.keeper.vault.contracts.presence.LivenessDetector
import com.keeper.vault.contracts.presence.LivenessState
import com.keeper.vault.contracts.presence.PresenceConfig
import com.keeper.vault.contracts.presence.PresenceEvent
import com.keeper.vault.contracts.presence.PresenceShield
import com.keeper.vault.presence.face.L2FaceMatcher
import com.keeper.vault.presence.liveness.EyeOpenLivenessDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * Continuous owner-presence controller.
 *
 * Responsibilities:
 * - pull frames at 700 ms (1.4 fps) while the owner is present;
 * - run a short 10-15 fps burst during the blink challenge, then return to the continuous rate;
 * - emit presence events for the app, which owns locking; this class never locks or decrypts;
 * - suspend all analysis when [gate] is closed.
 *
 * It never receives a `VaultKey` and has no dependency on `:auth` or `:crypto`.
 */
class DefaultPresenceShield(
    private val config: PresenceConfig = PresenceConfig(),
    private val gate: PresenceGate,
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val matcher: FaceMatcher = L2FaceMatcher(),
    private val clock: Clock,
    private val scope: CoroutineScope,
    initialFrameSource: PresenceFrameSource? = null,
    private val livenessDetector: LivenessDetector = EyeOpenLivenessDetector(
        blinkTimeoutMillis = config.blinkTimeoutMillis,
    ),
) : PresenceShield, PresenceFrameSourceAware {
    private val eventFlow = MutableSharedFlow<PresenceEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Hot event stream. Subscribe before [start]. */
    override val events: SharedFlow<PresenceEvent> = eventFlow.asSharedFlow()

    @Volatile
    private var frameSource: PresenceFrameSource? = initialFrameSource

    @Volatile
    private var enrolled: FaceEmbedding? = null

    private var job: Job? = null
    private var openedAtMillis: Long = 0L
    private var lastFaceSeenMillis: Long = 0L
    private var faceSeenSinceOpen: Boolean = false
    private var consecutiveMatches: Int = 0
    private var ownerConfirmed: Boolean = false
    private var livenessStarted: Boolean = false
    private var livenessFinished: Boolean = false
    private var burstUntilMillis: Long = 0L
    private var lastEvent: PresenceEvent? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch { collectGate() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        resetSession()
    }

    override fun updateEnrolled(embedding: FaceEmbedding?) {
        enrolled = embedding
    }

    override fun setFrameSource(source: PresenceFrameSource?) {
        frameSource = source
    }

    private suspend fun collectGate() {
        gate.unlocked.collectLatest { unlocked ->
            if (unlocked) {
                resetSession()
                openedAtMillis = clock.nowMillis()
                lastFaceSeenMillis = openedAtMillis
                analyzeWhileUnlocked()
            } else {
                resetSession()
            }
        }
    }

    private suspend fun analyzeWhileUnlocked() {
        while (currentCoroutineContext().isActive) {
            val currentEnrolled = enrolled
            if (currentEnrolled == null) {
                emit(PresenceEvent.Failure("Owner enrollment is not available"))
                delay(config.continuousAnalysisIntervalMillis)
                continue
            }
            val source = frameSource
            if (source == null) {
                emit(PresenceEvent.Failure("Camera frame source is not attached"))
                delay(config.continuousAnalysisIntervalMillis)
                continue
            }

            val frame = withTimeoutOrNull(frameTimeoutMillis()) {
                source.nextFrame()
            }
            if (frame == null) {
                emit(PresenceEvent.Failure("Camera frame unavailable"))
                delay(config.continuousAnalysisIntervalMillis)
                continue
            }

            val now = clock.nowMillis()
            val interval = analyzeFrame(frame, currentEnrolled, now)
            delay(interval)
        }
    }

    private suspend fun analyzeFrame(
        frame: PresenceFrame,
        currentEnrolled: FaceEmbedding,
        now: Long,
    ): Long {
        val faces = try {
            detector.detect(frame.image)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(PresenceEvent.Failure("Face detection failed"))
            return config.continuousAnalysisIntervalMillis
        }

        if (faces.size > 1) {
            lastFaceSeenMillis = now
            faceSeenSinceOpen = true
            consecutiveMatches = 0
            ownerConfirmed = false
            livenessStarted = false
            livenessFinished = false
            burstUntilMillis = 0L
            livenessDetector.reset()
            emit(PresenceEvent.MultipleFaces)
            return config.continuousAnalysisIntervalMillis
        }

        if (faces.isEmpty()) {
            consecutiveMatches = 0
            ownerConfirmed = false
            livenessStarted = false
            livenessFinished = false
            burstUntilMillis = 0L
            livenessDetector.reset()
            reportNoFaceIfDue(now)
            return config.continuousAnalysisIntervalMillis
        }

        lastFaceSeenMillis = now
        faceSeenSinceOpen = true
        val face = faces.first()
        val embedding = try {
            embedder.embed(frame.image, face)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(PresenceEvent.Failure("Face embedding failed"))
            return config.continuousAnalysisIntervalMillis
        }

        val isOwner = try {
            matcher.isOwner(embedding, currentEnrolled, config.matchThreshold)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(PresenceEvent.Failure("Face matching failed"))
            return config.continuousAnalysisIntervalMillis
        }

        if (!isOwner) {
            consecutiveMatches = 0
            ownerConfirmed = false
            livenessStarted = false
            livenessFinished = false
            burstUntilMillis = 0L
            livenessDetector.reset()
            emit(PresenceEvent.OwnerMismatch)
            return config.continuousAnalysisIntervalMillis
        }

        consecutiveMatches += 1
        if (consecutiveMatches < config.requiredConsecutiveMatches) {
            return config.continuousAnalysisIntervalMillis
        }

        if (!ownerConfirmed) {
            ownerConfirmed = true
            emit(PresenceEvent.OwnerPresent)
        }

        if (!livenessStarted) {
            livenessStarted = true
            burstUntilMillis = now + config.challengeBurstMillis
        }

        if (!livenessFinished) {
            when (livenessDetector.onFrame(faces, now)) {
                LivenessState.BlinkDetected -> {
                    livenessFinished = true
                    burstUntilMillis = 0L
                    emit(PresenceEvent.LivenessPassed)
                }

                LivenessState.TimedOut -> {
                    livenessFinished = true
                    burstUntilMillis = 0L
                    emit(PresenceEvent.BlinkTimeout)
                }

                else -> Unit
            }
        }

        return if (now < burstUntilMillis) burstIntervalMillis() else config.continuousAnalysisIntervalMillis
    }

    private fun reportNoFaceIfDue(now: Long) {
        val timeoutAt = if (faceSeenSinceOpen) {
            lastFaceSeenMillis + config.noFaceTimeoutMillis
        } else {
            // The startup grace delays the beginning of the no-face countdown, so a warming-up
            // camera cannot trigger a lock before it has had a chance to deliver a face.
            openedAtMillis + config.startupGraceMillis + config.noFaceTimeoutMillis
        }
        if (now >= timeoutAt) {
            emit(PresenceEvent.NoFace)
        }
    }

    private fun emit(event: PresenceEvent) {
        if (event == lastEvent) return
        lastEvent = event
        eventFlow.tryEmit(event)
    }

    private fun resetSession() {
        consecutiveMatches = 0
        ownerConfirmed = false
        livenessStarted = false
        livenessFinished = false
        burstUntilMillis = 0L
        lastEvent = null
        faceSeenSinceOpen = false
        livenessDetector.reset()
        openedAtMillis = clock.nowMillis()
        lastFaceSeenMillis = openedAtMillis
    }

    private fun burstIntervalMillis(): Long = max(1L, 1_000L / config.challengeBurstFps)

    private fun frameTimeoutMillis(): Long =
        max(2_000L, config.continuousAnalysisIntervalMillis * 3)

    private companion object {
        const val EVENT_BUFFER: Int = 32
    }
}
