package com.claudemobile.core.domain.repository

import com.claudemobile.core.domain.model.AppLanguage
import com.claudemobile.core.domain.model.AppSettings
import com.claudemobile.core.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Persistent store for application preferences.
 */
public interface SettingsStore {

    /**
     * A reactive stream of the current application settings.
     * Emits a new value whenever any preference changes.
     */
    public val settings: Flow<AppSettings>

    /**
     * Sets the selected Claude model identifier.
     *
     * @deprecated Superseded by ai-provider-presets (R11 AC1). The effective model is now
     * derived from the Active_Profile's `model` field. This method is retained for one
     * release to support existing callers; it will be removed once the migration window
     * closes. Do not add new call sites.
     */
    @Deprecated(
        message = "Superseded by ai-provider-presets; the effective model is derived from the Active_Profile. Do not add new call sites.",
        level = DeprecationLevel.WARNING,
    )
    public suspend fun setModelId(modelId: String)

    /**
     * Sets the UI theme mode.
     */
    public suspend fun setThemeMode(mode: ThemeMode)

    /**
     * Sets the font scale factor. Valid range: 0.5 to 3.0.
     */
    public suspend fun setFontScale(scale: Float)

    /**
     * Sets the streaming render rate in milliseconds. Valid range: 16 to 1000.
     */
    public suspend fun setStreamingRenderRate(rateMs: Long)

    /**
     * Sets the default workspace path for new sessions.
     */
    public suspend fun setDefaultWorkspacePath(path: String)

    /**
     * Sets whether the foreground service should auto-start when a CLI process begins.
     */
    public suspend fun setAutoStartForegroundService(enabled: Boolean)

    /**
     * Sets the app display language preference.
     */
    public suspend fun setAppLanguage(language: AppLanguage)

    /**
     * Legacy accessor: returns the raw value of the stored model identifier
     * key (base-spec `SettingsKeys.MODEL_ID` / [PreferenceKeys.MODEL_ID]), or
     * null if no value has ever been written.
     *
     * Introduced by the `ai-provider-presets` spec (R8.1, R11.1) so
     * `LegacyKeyMigrator` can read the pre-migration model id and copy it into
     * the migrated `ProviderProfile.model`. Distinct from reading
     * [AppSettings.modelId] from [settings], which always falls back to the
     * default when the key is absent; this accessor MUST return `null` when
     * the underlying preference is missing so the migrator can detect "never
     * configured" vs. "explicitly set to the default".
     *
     * Consumed only by `LegacyKeyMigrator`. The key constant
     * [PreferenceKeys.MODEL_ID] is kept for one release to support this read
     * path; both this method and the constant will be removed once the
     * migration window closes.
     */
    public suspend fun getModel(): String?

    /**
     * Legacy accessor: removes the stored model identifier key
     * (base-spec `SettingsKeys.MODEL_ID` / [PreferenceKeys.MODEL_ID]) from the
     * underlying preferences store.
     *
     * Introduced by the `ai-provider-presets` spec (R8.3, R11.1). Called by
     * `LegacyKeyMigrator` only after a successful migration has persisted the
     * value into a `ProviderProfile` and set it as the Active_Profile.
     * Consumed only by `LegacyKeyMigrator`; will be removed once the
     * migration window closes together with [PreferenceKeys.MODEL_ID].
     */
    public suspend fun clearModel()
}
