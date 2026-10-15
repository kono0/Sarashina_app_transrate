package com.kono0.sarashinatranslate.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kono0.sarashinatranslate.speech.SpeakerTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** 空なら local.properties の SARASHINA_API_KEY（BuildConfig）を使う。 */
    val apiKeyOverride: String = "",
    val speakerThreshold: Float = SpeakerTracker.DEFAULT_THRESHOLD,
    val nanoFallback: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            apiKeyOverride = p[API_KEY] ?: "",
            speakerThreshold = p[SPEAKER_THRESHOLD] ?: SpeakerTracker.DEFAULT_THRESHOLD,
            nanoFallback = p[NANO_FALLBACK] ?: true,
        )
    }

    suspend fun setApiKey(value: String) = context.dataStore.edit { it[API_KEY] = value.trim() }

    suspend fun setSpeakerThreshold(value: Float) = context.dataStore.edit { it[SPEAKER_THRESHOLD] = value }

    suspend fun setNanoFallback(value: Boolean) = context.dataStore.edit { it[NANO_FALLBACK] = value }

    private companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val SPEAKER_THRESHOLD = floatPreferencesKey("speaker_threshold")
        val NANO_FALLBACK = booleanPreferencesKey("nano_fallback")
    }
}
