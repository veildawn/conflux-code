package com.claudemobile.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.claudemobile.core.domain.model.AppLanguage
import com.claudemobile.core.domain.model.AppSettings
import com.claudemobile.core.domain.model.PreferenceKeys
import com.claudemobile.core.domain.model.ThemeMode
import com.claudemobile.core.domain.repository.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Valid range for font scale preference.
 */
internal object SettingsRanges {
    const val FONT_SCALE_MIN: Float = 0.5f
    const val FONT_SCALE_MAX: Float = 3.0f
    const val STREAMING_RENDER_RATE_MIN: Long = 16L
    const val STREAMING_RENDER_RATE_MAX: Long = 1000L
}

/**
 * DataStore-backed implementation of [SettingsStore].
 *
 * Validates ranges on write (rejects out-of-range values) and falls back to
 * defaults on read if a stored value is outside the valid range.
 */
@Singleton
public class SettingsStoreImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    private companion object {
        @Suppress("DEPRECATION")
        val KEY_MODEL_ID = stringPreferencesKey(PreferenceKeys.MODEL_ID)
        val KEY_SYSTEM_PROMPT = stringPreferencesKey(PreferenceKeys.SYSTEM_PROMPT)
        val KEY_THEME_MODE = stringPreferencesKey(PreferenceKeys.THEME_MODE)
        val KEY_FONT_SCALE = floatPreferencesKey(PreferenceKeys.FONT_SCALE)
        val KEY_STREAMING_RENDER_RATE = longPreferencesKey(PreferenceKeys.STREAMING_RENDER_RATE)
        val KEY_DEFAULT_WORKSPACE_PATH = stringPreferencesKey(PreferenceKeys.DEFAULT_WORKSPACE_PATH)
        val KEY_AUTO_START_FOREGROUND_SERVICE = booleanPreferencesKey(PreferenceKeys.AUTO_START_FOREGROUND_SERVICE)
        val KEY_APP_LANGUAGE = stringPreferencesKey(PreferenceKeys.APP_LANGUAGE)

        val DEFAULTS = AppSettings()
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            // modelId is no longer read from the store (ai-provider-presets R11 AC1);
            // the effective model is derived from the Active_Profile. The field is
            // kept in AppSettings for one release to avoid breaking callers; it
            // always returns the default value from this point forward.
            systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULTS.systemPrompt,
            themeMode = prefs[KEY_THEME_MODE]?.toThemeModeOrDefault() ?: DEFAULTS.themeMode,
            fontScale = prefs[KEY_FONT_SCALE]?.validatedFontScale() ?: DEFAULTS.fontScale,
            streamingRenderRate = prefs[KEY_STREAMING_RENDER_RATE]?.validatedRenderRate()
                ?: DEFAULTS.streamingRenderRate,
            defaultWorkspacePath = prefs[KEY_DEFAULT_WORKSPACE_PATH] ?: DEFAULTS.defaultWorkspacePath,
            autoStartForegroundService = prefs[KEY_AUTO_START_FOREGROUND_SERVICE]
                ?: DEFAULTS.autoStartForegroundService,
            appLanguage = prefs[KEY_APP_LANGUAGE]?.toAppLanguageOrDefault() ?: DEFAULTS.appLanguage,
        )
    }.catch { exception ->
        if (exception is IOException) {
            emit(AppSettings())
        } else {
            throw exception
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override suspend fun setModelId(modelId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_MODEL_ID] = modelId
        }
    }

    override suspend fun setSystemPrompt(prompt: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SYSTEM_PROMPT] = prompt
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    override suspend fun setFontScale(scale: Float) {
        require(scale in SettingsRanges.FONT_SCALE_MIN..SettingsRanges.FONT_SCALE_MAX) {
            "Font scale must be between ${SettingsRanges.FONT_SCALE_MIN} and ${SettingsRanges.FONT_SCALE_MAX}, was $scale"
        }
        dataStore.edit { prefs ->
            prefs[KEY_FONT_SCALE] = scale
        }
    }

    override suspend fun setStreamingRenderRate(rateMs: Long) {
        require(rateMs in SettingsRanges.STREAMING_RENDER_RATE_MIN..SettingsRanges.STREAMING_RENDER_RATE_MAX) {
            "Streaming render rate must be between ${SettingsRanges.STREAMING_RENDER_RATE_MIN} and ${SettingsRanges.STREAMING_RENDER_RATE_MAX}ms, was $rateMs"
        }
        dataStore.edit { prefs ->
            prefs[KEY_STREAMING_RENDER_RATE] = rateMs
        }
    }

    override suspend fun setDefaultWorkspacePath(path: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_WORKSPACE_PATH] = path
        }
    }

    override suspend fun setAutoStartForegroundService(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_START_FOREGROUND_SERVICE] = enabled
        }
    }

    override suspend fun setAppLanguage(language: AppLanguage) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_LANGUAGE] = language.name
        }
    }

    /**
     * Legacy accessor consumed only by `LegacyKeyMigrator` (ai-provider-presets R8.1, R11.1).
     *
     * Returns the raw value under [PreferenceKeys.MODEL_ID], or `null` when the
     * preference has never been written. Unlike [settings], this accessor does
     * NOT fall back to [AppSettings.modelId]'s default, so the migrator can
     * distinguish "never configured" from "explicitly set to the default".
     */
    override suspend fun getModel(): String? {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .first()[KEY_MODEL_ID]
    }

    /**
     * Legacy accessor consumed only by `LegacyKeyMigrator` (ai-provider-presets R8.3, R11.1).
     *
     * Removes the [PreferenceKeys.MODEL_ID] preference entirely, so a
     * subsequent [getModel] call returns `null`. The key constant itself is
     * retained for one release per the design note.
     */
    override suspend fun clearModel() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_MODEL_ID)
        }
    }

    /**
     * Returns the font scale if within valid range, otherwise returns the default.
     */
    private fun Float.validatedFontScale(): Float =
        if (this in SettingsRanges.FONT_SCALE_MIN..SettingsRanges.FONT_SCALE_MAX) this
        else DEFAULTS.fontScale

    /**
     * Returns the render rate if within valid range, otherwise returns the default.
     */
    private fun Long.validatedRenderRate(): Long =
        if (this in SettingsRanges.STREAMING_RENDER_RATE_MIN..SettingsRanges.STREAMING_RENDER_RATE_MAX) this
        else DEFAULTS.streamingRenderRate

    /**
     * Parses a stored theme mode string, falling back to default if invalid.
     */
    private fun String.toThemeModeOrDefault(): ThemeMode =
        try {
            ThemeMode.valueOf(this)
        } catch (_: IllegalArgumentException) {
            DEFAULTS.themeMode
        }

    /**
     * Parses a stored app language string, falling back to default if invalid.
     */
    private fun String.toAppLanguageOrDefault(): AppLanguage =
        try {
            AppLanguage.valueOf(this)
        } catch (_: IllegalArgumentException) {
            DEFAULTS.appLanguage
        }
}
