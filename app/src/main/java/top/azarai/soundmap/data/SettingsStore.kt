package top.azarai.soundmap.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.azarai.soundmap.audio.DialPosition

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "soundmap_settings")

/** Persisted UI state: whether the effect is on and where the dial marker sits. */
data class SavedState(
    val enabled: Boolean,
    val position: DialPosition,
)

class SettingsStore(private val context: Context) {

    val state: Flow<SavedState> = context.dataStore.data.map { prefs ->
        SavedState(
            enabled = prefs[KEY_ENABLED] ?: false,
            position = DialPosition(
                angleDeg = prefs[KEY_ANGLE] ?: 0f,
                radius = prefs[KEY_RADIUS] ?: 0f,
            ),
        )
    }

    suspend fun save(enabled: Boolean, position: DialPosition) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
            prefs[KEY_ANGLE] = position.angleDeg
            prefs[KEY_RADIUS] = position.radius
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_ANGLE = floatPreferencesKey("angle")
        val KEY_RADIUS = floatPreferencesKey("radius")
    }
}
