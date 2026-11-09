package com.keeper.vault.presence.shield

import com.keeper.vault.contracts.presence.FaceImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vault-unlocked state supplied by the app.
 *
 * Presence defines this tiny interface instead of importing `:auth`; the app maps
 * `LockState.Unlocked` to `true`. Analysis is suspended while [unlocked] is false.
 */
interface PresenceGate {
    val unlocked: StateFlow<Boolean>
}

/** Small test/app-friendly gate backed by a [MutableStateFlow]. */
class MutablePresenceGate(initial: Boolean = false) : PresenceGate {
    private val state = MutableStateFlow(initial)
    override val unlocked: StateFlow<Boolean> = state.asStateFlow()

    fun set(value: Boolean) {
        state.value = value
    }
}

/** One camera frame offered to the presence controller. */
data class PresenceFrame(
    val image: FaceImage,
    val timestampMillis: Long,
)

/**
 * Pull-based frame source.
 *
 * The controller asks for a frame only when it wants to analyze one, so the steady-state analysis
 * rate is the controller's rate, not the camera's preview rate. Implementations may drop stale
 * frames. Returning null means "no frame is available right now".
 */
interface PresenceFrameSource {
    suspend fun nextFrame(): PresenceFrame?

    fun close()
}

/**
 * A [com.keeper.vault.contracts.presence.PresenceShield] that can be attached to a camera frame
 * source after construction.
 *
 * The app constructs the shield with its detector/embedder; the foreground service owns the
 * CameraX lifecycle and attaches the camera here. Keeping this out of the frozen contract lets the
 * behavior layer stay camera-agnostic.
 */
interface PresenceFrameSourceAware {
    fun setFrameSource(source: PresenceFrameSource?)
}
