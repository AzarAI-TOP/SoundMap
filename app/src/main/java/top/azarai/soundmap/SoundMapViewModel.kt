package top.azarai.soundmap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.azarai.soundmap.audio.DialPosition
import top.azarai.soundmap.audio.EngineState
import top.azarai.soundmap.audio.SoundMapEngine
import top.azarai.soundmap.data.SettingsStore

/**
 * Bridges the UI and the [SoundMapEngine] / foreground service, and persists the last
 * on/off state and dial position via [SettingsStore].
 */
class SoundMapViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    val state: StateFlow<EngineState> = SoundMapEngine.state

    init {
        viewModelScope.launch {
            val saved = store.state.first()
            SoundMapEngine.update(saved.position)
            if (saved.enabled && !SoundMapEngine.state.value.enabled) {
                enable()
            }
        }
        // Persist state shortly after it settles, so dragging doesn't hammer disk.
        viewModelScope.launch {
            SoundMapEngine.state.debounce(400).collect { s ->
                store.save(s.enabled, s.position)
            }
        }
    }

    fun toggle() {
        if (state.value.enabled) disable() else enable()
    }

    fun onPositionChange(position: DialPosition) {
        SoundMapEngine.update(position)
    }

    private fun enable() {
        SoundMapEngine.enable(getApplication(), state.value.position)
        SoundMapService.start(getApplication())
    }

    private fun disable() {
        SoundMapEngine.disable()
        SoundMapService.stop(getApplication())
    }
}
