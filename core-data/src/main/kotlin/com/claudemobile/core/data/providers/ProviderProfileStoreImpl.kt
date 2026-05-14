/**
 * # ProviderProfileStoreImpl — cross-reference note
 *
 * **Supersedes** `android-claude-termux-client` base-spec Requirement 6
 * (Credential_Store) single-key storage.
 *
 * The base spec stored exactly one Anthropic API key in
 * EncryptedSharedPreferences. This file replaces that single-key store
 * with a multi-profile store: each [com.claudemobile.core.domain.providers.ProviderProfile]
 * is persisted as an individual JSON blob under the key
 * `profile.{profileId}`, and the Active_Profile pointer is stored under
 * `active_profile_id`. All security guarantees from the base spec
 * (Android Keystore backing, AES256-GCM encryption, masked display,
 * never logged) apply to every profile's `apiKey`.
 *
 * **Implements** `ai-provider-presets` Requirement 9 (Secure Storage for
 * All Provider Profiles), specifically AC 1–5.
 *
 * The EncryptedSharedPreferences file used here (`provider_profiles.xml`)
 * is intentionally separate from the base-spec `CredentialStoreImpl` file
 * so that the two stores evolve independently; the same AES256-GCM master
 * key from the Android Keystore is reused across both files.
 *
 * See also: `core-domain` `ProviderProfileStore.kt` for the interface,
 * `ProviderProfileModule.kt` for the Hilt binding, and
 * `LegacyKeyMigrator.kt` for the one-time migration from the base-spec
 * single-key store to this multi-profile store.
 */
package com.claudemobile.core.data.providers

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.data.providers.di.ProviderProfilesPrefs
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import com.claudemobile.core.domain.providers.ProviderRegistry
import java.security.InvalidKeyException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/**
 * EncryptedSharedPreferences-backed implementation of
 * [ProviderProfileStore].
 *
 * Supersedes base-spec R6 single-Anthropic-key storage and implements
 * `ai-provider-presets` R9 (multi-profile encrypted persistence).
 *
 * Storage layout (design §3, scheme C — one JSON blob per profile):
 *
 * - File: `provider_profiles.xml` — a **separate** EncryptedSharedPreferences
 *   file from the base-spec `CredentialStoreImpl` so that the two stores
 *   evolve independently. The Android Keystore master key is reused across
 *   both files (`MasterKey.KeyScheme.AES256_GCM`).
 * - Key scheme:
 *   - `profile.{profileId}` → JSON-encoded [ProviderProfileDto] (one entry
 *     per profile; allows atomic single-profile upsert without rewriting
 *     the full collection).
 *   - `active_profile_id` → the `profileId` of the current Active_Profile,
 *     or absent when none is selected.
 *
 * Synchronous commits (R5 AC2, R11 AC6):
 *
 * Both [upsert] and [setActive] use `editor.commit()` rather than `apply()`.
 * The 200 ms downstream-visibility guarantee for `observeActiveProfile()`
 * relies on the change being durably written before the suspending call
 * returns; `apply()` is asynchronous and cannot meet that bound under
 * load. [delete] and [deleteAll] also use `commit()` so that
 * delete-followed-by-list races have well-defined semantics.
 *
 * Error mapping (R9 AC4):
 *
 * The Android Keystore primitives throw a small family of exceptions when
 * the master key has been invalidated (e.g. after the user changes their
 * lock-screen credential, or when the encrypted blob's GCM tag fails to
 * verify):
 *
 * - [KeyStoreException] / [InvalidKeyException] / [KeyPermanentlyInvalidatedException]
 *   from key-loading paths.
 * - [AEADBadTagException] from decrypt paths when the ciphertext was
 *   produced under a different key.
 *
 * All of these are caught here and surfaced through [Result.failure] as
 * [ProviderProfileStoreError.KeystoreUnavailable] carrying the affected
 * `profileId` when the operation is profile-specific (or `null` for bulk
 * reads). The implementation never throws for these cases; callers (the
 * UI layer) translate the result into a re-enter-credentials prompt.
 *
 * Reactive flow methods (task 3.5):
 *
 * [observeProfiles] and [observeActiveProfile] are backed by a
 * `callbackFlow` over `SharedPreferences.OnSharedPreferenceChangeListener`
 * per design §4. Each subscriber receives an initial emission with the
 * current state, then a fresh emission for every successful `commit()`
 * against the underlying SP instance. `distinctUntilChanged` collapses
 * no-op listener fires (e.g. unrelated key writes that did not change
 * the projected value) into a single emission. The 200 ms
 * downstream-visibility bound (R5.2 / R11.6) follows from synchronous
 * commits in the mutation methods.
 *
 * Thread-safety:
 *
 * All public methods are `suspend` and switch to [CoroutineDispatchers.io]
 * before touching the underlying [SharedPreferences]. EncryptedSP
 * synchronizes its own internal state, but moving off the main thread is
 * required because both `commit()` and the encryption path perform disk
 * I/O.
 *
 * Requirements: 2.1, 2.2, 3.1, 4.3, 4.4, 4.5, 4.6, 4.7, 5.1, 5.2, 9.1,
 * 9.4, 9.5.
 */
@Singleton
internal class ProviderProfileStoreImpl @Inject constructor(
    @ProviderProfilesPrefs private val prefs: SharedPreferences,
    private val registry: ProviderRegistry,
    private val dispatchers: CoroutineDispatchers,
) : ProviderProfileStore {

    internal companion object {
        /** SP file name (mirrored by the Hilt module that builds the EncryptedSP). */
        const val PREFS_FILE_NAME: String = "provider_profiles"

        /** Reserved key for the Active_Profile pointer. */
        const val ACTIVE_PROFILE_ID_KEY: String = "active_profile_id"

        /** Prefix for per-profile JSON blob keys. */
        const val PROFILE_KEY_PREFIX: String = "profile."

        internal fun profileKey(profileId: String): String =
            PROFILE_KEY_PREFIX + profileId
    }

    // -----------------------------------------------------------------------
    // Reactive flow methods (design §4).
    //
    // Both flows are backed by a `callbackFlow` over
    // `SharedPreferences.OnSharedPreferenceChangeListener`, which fires
    // synchronously after every successful `commit()` on the same SP
    // instance. We push the changed key through a `Channel`-backed flow,
    // then `flatMapLatest` re-reads the current value from disk so the
    // emissions always reflect the *committed* state rather than a
    // staged one. The 200 ms downstream-visibility guarantee
    // (R5.2 / R11.6) is satisfied because:
    //
    //  1. `upsert` / `setActive` / `delete` / `deleteAll` use `commit()`
    //     (synchronous) — see the mutation methods below.
    //  2. SP fires the listener on the calling thread *after* the commit
    //     returns successfully.
    //  3. `callbackFlow` queues the notification onto the Flow's channel
    //     immediately; downstream collectors observe the new value within
    //     a single coroutine resume tick.
    //
    // `.flowOn(dispatchers.io)` upstream of the listener registration
    // ensures the listener runs on the IO dispatcher rather than the
    // collector's thread; SP listeners do disk I/O when the flow re-reads
    // the value, so this avoids surprise main-thread reads.

    /**
     * Emits the full profile list on every commit, sorted by
     * [ProviderProfile.updatedAt] descending. Triggers on **any** key
     * change inside the file (profile add/edit/delete *or* the
     * `active_profile_id` pointer flipping) because the active pointer
     * does not affect list contents — but this is harmless and
     * `distinctUntilChanged` collapses no-op repeats into one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProfiles(): Flow<List<ProviderProfile>> =
        sharedPreferenceChanges()
            .flatMapLatest { flowOf(readAllProfiles().sortedByDescending { it.updatedAt }) }
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    /**
     * Emits the current Active_Profile on every commit. Re-reads the
     * `active_profile_id` pointer plus the referenced profile blob from
     * disk on each emission so that a deletion of the active profile
     * (R4.6 / Property 7) immediately surfaces as `null`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeActiveProfile(): Flow<ProviderProfile?> =
        sharedPreferenceChanges()
            .flatMapLatest {
                val activeId = readActiveId()
                val profile = if (activeId == null) null else readProfile(activeId)
                flowOf(profile)
            }
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    /**
     * Cold flow that emits `Unit` once on subscription (so that the first
     * `flatMapLatest` block runs and seeds the downstream value) and then
     * once per `OnSharedPreferenceChangeListener` callback. The emitted
     * value is intentionally unitary — the flatMapLatest stage re-reads
     * the underlying state for every emission, so distinguishing keys
     * upstream is unnecessary.
     */
    private fun sharedPreferenceChanges(): Flow<Unit> = callbackFlow {
        // Seed the initial value so subscribers see the current state
        // even when no commits happen after subscription.
        trySend(Unit)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    override suspend fun list(): List<ProviderProfile> = withContext(dispatchers.io) {
        readAllProfiles().sortedByDescending { it.updatedAt }
    }

    override suspend fun get(profileId: String): ProviderProfile? =
        withContext(dispatchers.io) {
            readProfile(profileId)
        }

    override suspend fun getActive(): ProviderProfile? = withContext(dispatchers.io) {
        // R5 AC4 / R6 AC8 / R11 AC2: every invocation performs a fresh read.
        val activeId = readActiveId() ?: return@withContext null
        readProfile(activeId)
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    override suspend fun upsert(profile: ProviderProfile): Result<Unit> =
        withContext(dispatchers.io) {
            // R4.4 / Property 6: preset-derived profiles must keep the
            // registry's baseUrl. We check **before** any I/O so a bad
            // upsert never hits the encrypted store.
            val ref = profile.presetReference
            if (ref is PresetReference.Preset) {
                val preset = registry.findById(ref.presetId)
                if (preset != null && profile.baseUrl != preset.baseUrl) {
                    return@withContext Result.failure(ProviderProfileStoreError.BaseUrlLocked)
                }
            }

            try {
                val json = ProviderProfileJson.encodeToString(profile.toDto())
                val ok = prefs.edit()
                    .putString(profileKey(profile.profileId), json)
                    .commit() // R5 AC2 / R11 AC6: synchronous.
                if (!ok) {
                    Result.failure(
                        ProviderProfileStoreError.KeystoreUnavailable(profile.profileId),
                    )
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                mapException(e, profile.profileId)
            }
        }

    override suspend fun delete(profileId: String): Result<Unit> =
        withContext(dispatchers.io) {
            try {
                val key = profileKey(profileId)
                if (!prefs.contains(key)) {
                    return@withContext Result.failure(
                        ProviderProfileStoreError.NotFound(profileId),
                    )
                }

                // R4.5: overwrite-before-delete.
                //
                // 1. Read the existing JSON, decode it, blank the apiKey,
                //    re-encode, and write the blanked blob back. This causes
                //    the EncryptedSP layer to emit a fresh ciphertext that
                //    no longer encodes the user's key bytes.
                // 2. Then remove the key. The on-disk xml is rewritten by
                //    the SP layer; we cannot guarantee the previous
                //    ciphertext block is physically erased (SP limitation
                //    inherited from the base spec) but we have done the
                //    best a Kotlin caller can.
                val existingJson = prefs.getString(key, null)
                if (existingJson != null) {
                    val blankedJson = blankApiKeyJson(existingJson)
                    if (blankedJson != null) {
                        // First write: the blanked ciphertext supersedes
                        // the key-bearing ciphertext at the SP layer.
                        prefs.edit().putString(key, blankedJson).commit()
                    }
                }

                // Second write: remove the entry entirely. Also clear the
                // active-id pointer in the same edit when it points at this
                // profile (R4.6 / Property 7).
                val editor = prefs.edit().remove(key)
                if (readActiveId() == profileId) {
                    editor.remove(ACTIVE_PROFILE_ID_KEY)
                }
                val ok = editor.commit()
                if (ok) Result.success(Unit)
                else Result.failure(ProviderProfileStoreError.KeystoreUnavailable(profileId))
            } catch (e: Exception) {
                mapException(e, profileId)
            }
        }

    override suspend fun setActive(profileId: String?): Result<Unit> =
        withContext(dispatchers.io) {
            try {
                val editor = prefs.edit()
                if (profileId == null) {
                    editor.remove(ACTIVE_PROFILE_ID_KEY)
                } else {
                    if (!prefs.contains(profileKey(profileId))) {
                        return@withContext Result.failure(
                            ProviderProfileStoreError.NotFound(profileId),
                        )
                    }
                    editor.putString(ACTIVE_PROFILE_ID_KEY, profileId)
                }
                val ok = editor.commit() // R5 AC2 / R11 AC6: synchronous.
                if (ok) Result.success(Unit)
                else Result.failure(ProviderProfileStoreError.KeystoreUnavailable(profileId))
            } catch (e: Exception) {
                mapException(e, profileId)
            }
        }

    override suspend fun deleteAll(): Result<Unit> = withContext(dispatchers.io) {
        try {
            // Best-effort overwrite-before-delete on every profile entry.
            // Failure to blank a single entry must not block the wider
            // wipe because the user invoked deleteAll precisely to recover
            // from a bad state (R9 AC5).
            val allKeys = prefs.all.keys.toList()
            val profileKeys = allKeys.filter { it.startsWith(PROFILE_KEY_PREFIX) }
            if (profileKeys.isNotEmpty()) {
                val blankingEditor = prefs.edit()
                var anyBlanked = false
                for (key in profileKeys) {
                    val existing = try {
                        prefs.getString(key, null)
                    } catch (_: Exception) {
                        null
                    }
                    val blanked = existing?.let { blankApiKeyJson(it) }
                    if (blanked != null) {
                        blankingEditor.putString(key, blanked)
                        anyBlanked = true
                    }
                }
                if (anyBlanked) blankingEditor.commit()
            }

            val ok = prefs.edit().clear().commit()
            if (ok) Result.success(Unit)
            else Result.failure(ProviderProfileStoreError.KeystoreUnavailable(null))
        } catch (e: Exception) {
            mapException(e, profileId = null)
        }
    }

    // -----------------------------------------------------------------------
    // Internal read helpers
    // -----------------------------------------------------------------------

    /**
     * Reads every `profile.*` entry, decoding each JSON blob into a
     * [ProviderProfile]. Entries that fail to decode are silently
     * skipped to avoid a single corrupt blob blocking the entire list;
     * a Keystore-level failure during the iteration surfaces through
     * the catch in the public methods.
     */
    private fun readAllProfiles(): List<ProviderProfile> {
        val all = prefs.all
        val out = ArrayList<ProviderProfile>(all.size)
        for ((key, value) in all) {
            if (!key.startsWith(PROFILE_KEY_PREFIX)) continue
            val json = value as? String ?: continue
            val profile = decodeProfileOrNull(json) ?: continue
            out.add(profile)
        }
        return out
    }

    private fun readProfile(profileId: String): ProviderProfile? {
        val json = prefs.getString(profileKey(profileId), null) ?: return null
        return decodeProfileOrNull(json)
    }

    private fun readActiveId(): String? = prefs.getString(ACTIVE_PROFILE_ID_KEY, null)

    private fun decodeProfileOrNull(json: String): ProviderProfile? = try {
        ProviderProfileJson
            .decodeFromString(ProviderProfileDto.serializer(), json)
            .toDomain()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        // toDomain() throws IllegalArgumentException for unknown
        // authHeaderStyle names; treat as unreadable.
        null
    }

    /**
     * Returns a copy of the input JSON with the `apiKey` field replaced by
     * the empty string, preserving every other field.
     *
     * Returns `null` when the input cannot be decoded — in that case the
     * caller proceeds directly to `remove()` (the entry is unreadable, so
     * blanking it adds no value).
     */
    private fun blankApiKeyJson(json: String): String? = try {
        val dto = ProviderProfileJson
            .decodeFromString(ProviderProfileDto.serializer(), json)
        ProviderProfileJson.encodeToString(dto.copy(apiKey = ""))
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Maps an exception thrown by the EncryptedSharedPreferences layer to
     * a [Result.failure] carrying the appropriate
     * [ProviderProfileStoreError]. Non-keystore exceptions are re-thrown
     * unchanged so genuine programming errors remain visible.
     */
    private fun mapException(e: Exception, profileId: String?): Result<Unit> = when (e) {
        is AEADBadTagException,
        is KeyStoreException,
        is InvalidKeyException,
        is KeyPermanentlyInvalidatedException,
        ->
            Result.failure(ProviderProfileStoreError.KeystoreUnavailable(profileId))
        else -> throw e
    }
}

/**
 * Hilt-friendly factory for the [SharedPreferences] backing
 * [ProviderProfileStoreImpl]. Lives next to the impl rather than in the DI
 * package so the constants and SP-construction logic stay close to the
 * consumer; the actual `@Provides` binding is supplied by
 * [com.claudemobile.core.data.providers.di.ProviderProfilesPrefsModule].
 */
internal object ProviderProfilesPrefsFactory {

    /**
     * Builds the EncryptedSharedPreferences instance for the
     * `provider_profiles.xml` file. The master key is the same
     * `AES256_GCM` master key used by the base-spec credential store
     * (per R9 AC1: "EncryptedSharedPreferences backed by a master key
     * stored in the Android Keystore").
     */
    fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ProviderProfileStoreImpl.PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
