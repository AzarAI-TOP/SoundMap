package top.azarai.soundmap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.azarai.soundmap.audio.DemoSourceId
import top.azarai.soundmap.audio.DialPosition
import top.azarai.soundmap.audio.EngineState
import top.azarai.soundmap.audio.SoundMapEngine
import top.azarai.soundmap.data.SettingsStore

/** Bridges the UI to [SoundMapEngine] / the foreground service and persists position + source. */
class SoundMapViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    val state: StateFlow<EngineState> = SoundMapEngine.state

    init {
        viewModelScope.launch {
            val saved = store.state.first()
            SoundMapEngine.setSource(saved.sourceId)
            SoundMapEngine.setPosition(saved.position)
        }
        // Persist position + source after they settle; never auto-plays.
        viewModelScope.launch {
            SoundMapEngine.state.debounce(400).collect { s ->
                store.save(s.position, s.sourceId)
            }
        }
    }

    fun toggle() {
        if (state.value.playing) {
            SoundMapEngine.stop()
            SoundMapService.stop(getApplication())
        } else {
            SoundMapEngine.play()
            if (state.value.playing) SoundMapService.start(getApplication())
        }
    }

    fun onPositionChange(position: DialPosition) {
        SoundMapEngine.setPosition(position)
    }

    fun onSourceChange(id: DemoSourceId) {
        SoundMapEngine.setSource(id)
    }
}
