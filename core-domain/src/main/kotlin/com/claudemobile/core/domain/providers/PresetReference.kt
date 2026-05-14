package com.claudemobile.core.domain.providers

/**
 * Origin of a [ProviderProfile]: either derived from a built-in
 * [ProviderPreset] shipped in the [ProviderRegistry], or a user-defined
 * Custom profile with no preset backing.
 *
 * This type is the serialized linkage between a persisted profile and
 * the (potentially evolving) in-code preset list. When a profile
 * references a preset that has been removed in a newer build,
 * [ProviderRegistry.findById] returns `null` and callers treat the
 * profile as Custom for UI purposes.
 *
 * Requirements: 2.1, 3.1, 4.4.
 */
public sealed class PresetReference {

    /**
     * Profile was created from the preset with the given [presetId].
     *
     * @property presetId stable identifier matching a
     *   [ProviderPreset.presetId] known to the [ProviderRegistry] at the
     *   time the profile was created.
     */
    public data class Preset(val presetId: String) : PresetReference()

    /**
     * Profile was created by the user without a preset backing. All
     * fields on the owning [ProviderProfile] are user-supplied.
     */
    public data object Custom : PresetReference()
}
