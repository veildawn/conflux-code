package com.claudemobile.core.domain.model

/**
 * Application-wide user preferences.
 */
public data class AppSettings(
    val modelId: String = "claude-sonnet-4-20250514",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    val streamingRenderRate: Long = 50L,
    val defaultWorkspacePath: String = "",
    val autoStartForegroundService: Boolean = true,
    val appLanguage: AppLanguage = AppLanguage.DEFAULT
)

/**
 * The UI theme mode preference.
 */
public enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * Preference key names used by the Settings Store.
 */
public object PreferenceKeys {
    /**
     * Superseded by ai-provider-presets; kept for one release to support LegacyKeyMigrator.
     *
     * The effective model is now derived from the Active_Profile's `model` field
     * (see `ProviderProfile`). This constant must not be used for new reads or
     * writes outside of `LegacyKeyMigrator.getModel()`.
     */
    @Deprecated(
        message = "Superseded by ai-provider-presets; kept for one release to support LegacyKeyMigrator.",
        level = DeprecationLevel.WARNING,
    )
    public const val MODEL_ID: String = "model_id"
    public const val THEME_MODE: String = "theme_mode"
    public const val FONT_SCALE: String = "font_scale"
    public const val STREAMING_RENDER_RATE: String = "streaming_render_rate_ms"
    public const val DEFAULT_WORKSPACE_PATH: String = "default_workspace_path"
    public const val AUTO_START_FOREGROUND_SERVICE: String = "auto_start_fg_service"
    public const val APP_LANGUAGE: String = "app_language"
}
