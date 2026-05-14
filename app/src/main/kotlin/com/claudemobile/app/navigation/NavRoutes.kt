package com.claudemobile.app.navigation

/**
 * Navigation route constants for the app.
 */
object NavRoutes {
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
    const val BOOTSTRAP = "bootstrap"

    // --- ai-provider-presets (task 7.1) ---
    /** Provider selection screen: lists built-in presets + custom option. */
    const val PROVIDER_SELECTION = "provider/selection"

    /**
     * Provider editor screen, split into three sibling routes — one per
     * editor variant — instead of a single route with a colon-encoded
     * `mode` argument.
     *
     * The earlier encoding (`provider/editor/{mode}` with `mode` =
     * `preset:{id}` / `custom` / `edit:{id}`) failed at runtime because
     * Navigation Compose interprets a colon (`:`) inside a path segment
     * as a URI scheme delimiter and the registered pattern no longer
     * matches the constructed path, causing
     * `IllegalArgumentException` when calling `navigate(...)`.
     *
     * Use [providerEditorPreset], [providerEditorCustom], and
     * [providerEditorEdit] to construct concrete routes so callers
     * don't have to worry about the encoding.
     */
    const val PROVIDER_EDITOR_PRESET = "provider/editor/preset/{presetId}"
    const val PROVIDER_EDITOR_CUSTOM = "provider/editor/custom"
    const val PROVIDER_EDITOR_EDIT = "provider/editor/edit/{profileId}"

    /** Provider list screen: shows all saved profiles and their active state. */
    const val PROVIDER_LIST = "provider/list"

    // --- Route builders ---

    fun chatRoute(sessionId: String): String = "chat/$sessionId"

    /** Route for the editor screen in preset-creation mode. */
    fun providerEditorPreset(presetId: String): String = "provider/editor/preset/$presetId"

    /** Route for the editor screen in custom-creation mode. */
    fun providerEditorCustom(): String = "provider/editor/custom"

    /** Route for the editor screen editing an existing profile. */
    fun providerEditorEdit(profileId: String): String = "provider/editor/edit/$profileId"
}
