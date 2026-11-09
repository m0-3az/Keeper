package com.keeper.vault.presence.platform

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.keeper.vault.presence.shield.PresenceFrame
import com.keeper.vault.presence.shield.PresenceFrameSource
import kotlinx.coroutines.CompletableDeferred

/**
 * CameraX frame source.
 *
 * The analyzer receives preview-rate frames but does no conversion unless the controller has an
 * outstanding [nextFrame] request. That keeps steady-state work at the controller's 1-2 fps and
 * lets the blink burst reach 10-15 fps without changing the camera configuration.
 */
class CameraFrameSource : ImageAnalysis.Analyzer, PresenceFrameSource {
    private val lock = Any()
    private var pending: CompletableDeferred<PresenceFrame?>? = null

    @Volatile
    private var closed: Boolean = false

    override suspend fun nextFrame(): PresenceFrame? {
        if (closed) return null
        val request = CompletableDeferred<PresenceFrame?>()
        synchronized(lock) {
            if (closed) return null
            pending?.complete(null)
            pending = request
        }
        return try {
            request.await()
        } finally {
            synchronized(lock) {
                if (pending === request) pending = null
            }
        }
    }

    override fun analyze(image: ImageProxy) {
        val request = synchronized(lock) {
            val current = pending
            pending = null
            current
        }
        if (request == null) {
            image.close()
            return
        }
        try {
            request.complete(
                PresenceFrame(
                    image = RgbaImageProxyConverter.convert(image),
                    timestampMillis = SystemClock.elapsedRealtime(),
                ),
            )
        } catch (error: Throwable) {
            request.completeExceptionally(error)
        } finally {
            image.close()
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) {
            pending?.complete(null)
            pending = null
        }
    }
}
