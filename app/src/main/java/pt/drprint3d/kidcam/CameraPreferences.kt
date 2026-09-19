package pt.drprint3d.kidcam

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_prefs")

class CameraPreferences(private val context: Context) {
    companion object {
        val LAST_USED_CAMERA_ID = stringPreferencesKey("last_used_camera_id")
        val MIRROR_X = booleanPreferencesKey("mirror_x")
        val MIRROR_Y = booleanPreferencesKey("mirror_y")
    }

    val lastUsedCameraId: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_USED_CAMERA_ID]
        }

    val mirrorX: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[MIRROR_X] ?: false
        }

    val mirrorY: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[MIRROR_Y] ?: false
        }

    suspend fun saveLastUsedCameraId(cameraId: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_USED_CAMERA_ID] = cameraId
        }
    }

    suspend fun saveMirrorX(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MIRROR_X] = enabled
        }
    }

    suspend fun saveMirrorY(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MIRROR_Y] = enabled
        }
    }
}
