package top.azarai.soundmap.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Observable snapshot of the demo player, surfaced to the UI. */
data class EngineState(
    val playing: Boolean = false,
    val position: DialPosition = DialPosition.CENTER,
    val sourceId: DemoSourceId = DemoSourceId.BEEP,
    val error: String? = null,
)

/**
 * Process-wide owner of the demo player. The [BinauralPlayer] renders our own audio, so the
 * spatial effect is baked into the samples and reaches any output (incl. Bluetooth). The
 * foreground service only keeps the process alive while playing.
 */
object SoundMapEngine {

    private var player: BinauralPlayer? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Starts playback of the current source at the current position. */
    fun play(): EngineState {
        val p = player ?: BinauralPlayer().also { player = it }
        p.setSource(_state.value.sourceId)
        val ok = p.start(_state.value.position)
        _state.update { it.copy(playing = ok, error = if (ok) null else "无法初始化音频输出") }
        return _state.value
    }

    /** Stops playback; audio output ends. */
    fun stop() {
        player?.stop()
        _state.update { it.copy(playing = false) }
    }

    /** Moves the sound around the listener (live while playing). */
    fun setPosition(position: DialPosition) {
        player?.setPosition(position)
        _state.update { it.copy(position = position) }
    }

    /** Swaps which demo sound plays (takes effect on the next block). */
    fun setSource(id: DemoSourceId) {
        player?.setSource(id)
        _state.update { it.copy(sourceId = id) }
    }
}
