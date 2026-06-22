package top.azarai.soundmap.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What actually attached, surfaced to the UI and the on-device go/no-go check. */
data class EngineStatus(
    /** Whether DUMP is granted — required to discover other apps' real session ids. */
    val dumpGranted: Boolean,
    /** Sessions we currently hold an effect chain on (incl. the global-0 fallback). */
    val sessionCount: Int,
    /** Of those, how many actually got at least one effect attached. */
    val attachedSessions: Int,
    val anyEffectAttached: Boolean,
)

/** Observable snapshot of the engine, surfaced to the UI. */
data class EngineState(
    val enabled: Boolean = false,
    val position: DialPosition = DialPosition.CENTER,
    val status: EngineStatus? = null,
)

/**
 * Process-wide owner of the audio effect. The effects live in the app process; the
 * foreground service exists only to keep that process alive and show a notification.
 * UI and service both go through this single source of truth.
 */
object SoundMapEngine {

    private var manager: SessionEffectManager? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Turns the effect on at [position] and reports what attached. */
    fun enable(context: Context, position: DialPosition): EngineStatus {
        val mgr = manager ?: SessionEffectManager(context).also {
            it.onChanged = { refreshStatus() }
            manager = it
        }
        mgr.start(SpatialMapping.mapToParams(position))
        val status = mgr.status()
        Log.i("SoundMapSession", "enable(position=$position) -> status=$status")
        _state.value = EngineState(enabled = true, position = position, status = status)
        return status
    }

    /** Live-updates the effect as the user moves the dial. */
    fun update(position: DialPosition) {
        val mgr = manager
        if (mgr == null || !_state.value.enabled) {
            _state.update { it.copy(position = position) }
            return
        }
        mgr.apply(SpatialMapping.mapToParams(position))
        _state.update { it.copy(position = position, status = mgr.status()) }
    }

    /** Releases the effect; audio returns to normal. */
    fun disable() {
        manager?.stop()
        _state.update { it.copy(enabled = false, status = manager?.status()) }
    }

    private fun refreshStatus() {
        val mgr = manager ?: return
        _state.update { it.copy(status = mgr.status()) }
    }
}
