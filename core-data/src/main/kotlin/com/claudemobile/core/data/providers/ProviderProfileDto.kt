package com.claudemobile.core.data.providers

import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire-format data transfer object for [ProviderProfile].
 *
 * Persisted as a JSON blob under the `profile.{profileId}` key inside the
 * `provider_profiles.xml` `EncryptedSharedPreferences` file (see design §3
 * and §9). The on-disk shape is frozen by the design JSON schema:
 *
 * ```json
 * {
 *   "profileId": "…",
 *   "displayName": "…",
 *   "presetReference": { "type": "preset", "presetId": "…" },
 *   "baseUrl": "https://…",
 *   "apiKey": "…",
 *   "model": "…",
 *   "smallFastModel": null,
 *   "authHeaderStyle": "AuthToken",
 *   "createdAt": 1730000000000,
 *   "updatedAt": 1730000000000
 * }
 * ```
 *
 * Notes on the schema:
 *
 * - `presetReference` uses a `"type"` class discriminator with values
 *   `"preset"` (carrying a `presetId` child) or `"custom"` (no other
 *   fields).
 * - `authHeaderStyle` is serialized as the enum **name** (`"ApiKey"` or
 *   `"AuthToken"`), not an ordinal, so that ordering changes in
 *   [AuthHeaderStyle] do not silently remap persisted profiles.
 * - `smallFastModel` is omitted or emitted as `null` when the domain
 *   value is `null` (see [ProviderProfileJson.encodeDefaults] = false).
 *
 * Requirements: 4.7, 12.1.
 */
@Serializable
internal data class ProviderProfileDto(
    val profileId: String,
    val displayName: String,
    val presetReference: PresetReferenceDto,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val smallFastModel: String? = null,
    val authHeaderStyle: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Wire-format representation of [PresetReference].
 *
 * The `"type"` class discriminator distinguishes the two variants and is
 * emitted automatically by kotlinx.serialization's polymorphic encoding.
 *
 * Requirements: 4.7.
 */
@Serializable
internal sealed class PresetReferenceDto {

    @Serializable
    @SerialName("preset")
    data class Preset(val presetId: String) : PresetReferenceDto()

    @Serializable
    @SerialName("custom")
    data object Custom : PresetReferenceDto()
}

/**
 * Shared `Json` configuration used by the provider store.
 *
 * - `classDiscriminator = "type"` matches the design JSON schema for
 *   `presetReference`.
 * - `ignoreUnknownKeys = true` preserves forward compatibility so that a
 *   downgrade cannot crash the store when a newer build adds fields.
 * - `encodeDefaults = false` keeps `smallFastModel: null` out of the
 *   emitted JSON when the field is absent (readers still tolerate the
 *   explicit `null` form, so both shapes round-trip to the same domain).
 */
internal val ProviderProfileJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}

// ---------------------------------------------------------------------------
// Domain <-> DTO mappers
//
// These are pure functions — no Android, no I/O — and form part of the
// round-trip correctness property (Property 1). Each mapper is total: it
// maps every domain value to a DTO value and back without loss.
// ---------------------------------------------------------------------------

internal fun PresetReference.toDto(): PresetReferenceDto = when (this) {
    is PresetReference.Preset -> PresetReferenceDto.Preset(presetId)
    PresetReference.Custom -> PresetReferenceDto.Custom
}

internal fun PresetReferenceDto.toDomain(): PresetReference = when (this) {
    is PresetReferenceDto.Preset -> PresetReference.Preset(presetId)
    PresetReferenceDto.Custom -> PresetReference.Custom
}

internal fun ProviderProfile.toDto(): ProviderProfileDto = ProviderProfileDto(
    profileId = profileId,
    displayName = displayName,
    presetReference = presetReference.toDto(),
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    smallFastModel = smallFastModel,
    authHeaderStyle = authHeaderStyle.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * Throws [IllegalArgumentException] when [ProviderProfileDto.authHeaderStyle]
 * is not a recognized [AuthHeaderStyle] name. The store treats this as a
 * `KeystoreUnavailable`-equivalent read failure; callers should not need to
 * handle the exception directly because the store wraps it in
 * `ProviderProfileStoreError`.
 */
internal fun ProviderProfileDto.toDomain(): ProviderProfile = ProviderProfile(
    profileId = profileId,
    displayName = displayName,
    presetReference = presetReference.toDomain(),
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    smallFastModel = smallFastModel,
    authHeaderStyle = AuthHeaderStyle.valueOf(authHeaderStyle),
    createdAt = createdAt,
    updatedAt = updatedAt,
)
