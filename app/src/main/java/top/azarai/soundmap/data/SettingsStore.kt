package top.azarai.soundmap.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.azarai.soundmap.audio.DemoSourceId
import top.azarai.soundmap.audio.DialPosition

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "soundmap_settings")

/** Persisted UI state: last dial position and selected demo source. Never persists play state. */
data class SavedState(
    val position: DialPosition,
    val sourceId: DemoSourceId,
)

class SettingsStore(private val context: Context) {

    val state: Flow<SavedState> = context.dataStore.data.map { prefs ->
        SavedState(
            position = DialPosition(
                angleDeg = prefs[KEY_ANGLE] ?: 0f,
                radius = prefs[KEY_RADIUS] ?: 0f,
            ),
            sourceId = runCatching {
                DemoSourceId.valueOf(prefs[KEY_SOURCE] ?: DemoSourceId.BEEP.name)
            }.getOrDefault(DemoSourceId.BEEP),
        )
    }

    suspend fun save(position: DialPosition, sourceId: DemoSourceId) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANGLE] = position.angleDeg
            prefs[KEY_RADIUS] = position.radius
            prefs[KEY_SOURCE] = sourceId.name
        }
    }

    private companion object {
        val KEY_ANGLE = floatPreferencesKey("angle")
        val KEY_RADIUS = floatPreferencesKey("radius")
        val KEY_SOURCE = stringPreferencesKey("source")
    }
}
