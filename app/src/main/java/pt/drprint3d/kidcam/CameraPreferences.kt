package pt.drprint3d.kidcam

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Extension property to get the DataStore instance
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_prefs")

class CameraPreferences(private val context: Context) {
    companion object {
        val LAST_USED_CAMERA_ID = stringPreferencesKey("last_used_camera_id")
    }

    val lastUsedCameraId: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_USED_CAMERA_ID]
        }

    suspend fun saveLastUsedCameraId(cameraId: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_USED_CAMERA_ID] = cameraId
        }
    }
}
