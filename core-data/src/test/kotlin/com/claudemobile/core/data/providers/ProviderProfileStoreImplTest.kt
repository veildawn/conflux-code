package com.claudemobile.core.data.providers

import android.content.SharedPreferences
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderPreset
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import com.claudemobile.core.domain.providers.ProviderRegistry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [ProviderProfileStoreImpl] against a fake in-memory
 * [SharedPreferences].
 *
 * The fake bypasses EncryptedSharedPreferences entirely (which would
 * require an Android Keystore at test time) and exercises the
 * implementation's logic — JSON encoding / decoding, key naming, the
 * `BaseUrlLocked` precondition, the overwrite-before-delete sequence,
 * and the active-id pointer maintenance — against an Android contract
 * that mirrors the real `SharedPreferences` semantics relevant to the
 * store: atomic `commit()`, `contains` / `getString` reads, `edit()`
 * staging followed by an explicit commit, and `clear()` semantics.
 *
 * Validates: Requirements 2.1, 2.2, 3.1, 4.3, 4.4, 4.5, 4.6, 4.7, 5.1,
 * 5.2, 9.1, 9.4, 9.5.
 */
class ProviderProfileStoreImplTest : DescribeSpec({

    describe("upsert + get round-trip (R4.7, R12.1)") {

        it("persists a Custom profile and reads it back unchanged") {
            runTest {
                val store = newStore()
                val profile = customProfile(profileId = "p-1", apiKey = "secret-1")

                store.upsert(profile).isSuccess shouldBe true

                store.get("p-1") shouldBe profile
            }
        }

        it("persists a Preset-derived profile when baseUrl matches the registry") {
            runTest {
                val registry = registryWith(GLM_TEST_PRESET)
                val store = newStore(registry = registry)
                val profile = presetProfile(
                    profileId = "p-2",
                    presetId = GLM_TEST_PRESET.presetId,
                    baseUrl = GLM_TEST_PRESET.baseUrl,
                )

                store.upsert(profile).isSuccess shouldBe true

                store.get("p-2") shouldBe profile
            }
        }

        it("returns null for a missing profile id") {
            runTest {
                val store = newStore()
                store.get("missing").shouldBeNull()
            }
        }
    }

    describe("list (R4.1)") {

        it("returns profiles sorted by updatedAt descending") {
            runTest {
                val store = newStore()
                val older = customProfile(profileId = "p-old", updatedAt = 1_000L)
                val newer = customProfile(profileId = "p-new", updatedAt = 2_000L)
                val newest = customProfile(profileId = "p-newest", updatedAt = 3_000L)

                store.upsert(older).isSuccess shouldBe true
                store.upsert(newer).isSuccess shouldBe true
                store.upsert(newest).isSuccess shouldBe true

                store.list().map { it.profileId } shouldContainExactly listOf(
                    "p-newest", "p-new", "p-old",
                )
            }
        }

        it("returns an empty list when nothing is stored") {
            runTest {
                val store = newStore()
                store.list() shouldBe emptyList()
            }
        }
    }

    describe("setActive + getActive (R5.1, R5.2)") {

        it("stores and retrieves the active profile id") {
            runTest {
                val store = newStore()
                val profile = customProfile(profileId = "active-1")

                store.upsert(profile).isSuccess shouldBe true
                store.setActive("active-1").isSuccess shouldBe true

                store.getActive() shouldBe profile
            }
        }

        it("setActive(null) clears the active reference") {
            runTest {
                val store = newStore()
                val profile = customProfile(profileId = "active-2")
                store.upsert(profile).isSuccess shouldBe true
                store.setActive("active-2").isSuccess shouldBe true

                store.setActive(null).isSuccess shouldBe true

                store.getActive().shouldBeNull()
            }
        }

        it("setActive on a missing profile id fails with NotFound") {
            runTest {
                val store = newStore()
                val result = store.setActive("does-not-exist")

                result.isFailure shouldBe true
                val err = result.exceptionOrNull()
                err.shouldBeInstanceOf<ProviderProfileStoreError.NotFound>()
                err.profileId shouldBe "does-not-exist"
            }
        }

        it("getActive performs a fresh read on every call (R5.4 / R6.8)") {
            runTest {
                val (prefs, store) = newStoreWithPrefs()
                val p1 = customProfile(profileId = "p-1", apiKey = "k1")
                val p2 = customProfile(profileId = "p-2", apiKey = "k2")
                store.upsert(p1).isSuccess shouldBe true
                store.upsert(p2).isSuccess shouldBe true
                store.setActive("p-1").isSuccess shouldBe true

                store.getActive() shouldBe p1

                // Mutate the store out from underneath via a fresh
                // commit; the next getActive must reflect the new value
                // rather than a cached snapshot.
                prefs.edit().putString("active_profile_id", "p-2").commit()

                store.getActive() shouldBe p2
            }
        }
    }

    describe("delete-active clears the active reference (R4.6, Property 7)") {

        it("clears active when the deleted profile is the active one") {
            runTest {
                val store = newStore()
                val profile = customProfile(profileId = "to-delete")
                store.upsert(profile).isSuccess shouldBe true
                store.setActive("to-delete").isSuccess shouldBe true

                store.delete("to-delete").isSuccess shouldBe true

                store.get("to-delete").shouldBeNull()
                store.getActive().shouldBeNull()
            }
        }

        it("preserves active when a non-active profile is deleted") {
            runTest {
                val store = newStore()
                val active = customProfile(profileId = "stay")
                val other = customProfile(profileId = "other")
                store.upsert(active).isSuccess shouldBe true
                store.upsert(other).isSuccess shouldBe true
                store.setActive("stay").isSuccess shouldBe true

                store.delete("other").isSuccess shouldBe true

                store.getActive() shouldBe active
            }
        }

        it("delete on a missing profile id fails with NotFound") {
            runTest {
                val store = newStore()
                val result = store.delete("nope")

                result.isFailure shouldBe true
                val err = result.exceptionOrNull()
                err.shouldBeInstanceOf<ProviderProfileStoreError.NotFound>()
                err.profileId shouldBe "nope"
            }
        }
    }

    describe("delete overwrites apiKey before removal (R4.5)") {

        it("performs a blanking write before removing the entry") {
            runTest {
                val (prefs, store) = newStoreWithPrefs()
                val profile = customProfile(profileId = "to-blank", apiKey = "secret-bytes")
                store.upsert(profile).isSuccess shouldBe true

                // Snapshot the writes against the fake to prove the
                // sequence: 1) blanking write replaces the apiKey, 2)
                // remove deletes the entry.
                prefs.recordWrites = true

                store.delete("to-blank").isSuccess shouldBe true

                // The fake records every (key, op) pair across the two
                // edits performed by `delete`. We expect a `putString` on
                // the profile key (the blanking write) followed by a
                // `remove` on the same key.
                val opsForKey = prefs.recordedOps.filter { it.key == "profile.to-blank" }
                opsForKey shouldHaveSize 2
                opsForKey[0].kind shouldBe FakeOpKind.Put
                opsForKey[1].kind shouldBe FakeOpKind.Remove

                // The blanking write must NOT contain the original key
                // bytes — that's the whole point of the overwrite step.
                val blankedJson = opsForKey[0].value
                blankedJson!!.contains("secret-bytes") shouldBe false
            }
        }
    }

    describe("upsert rejects baseUrl drift on preset-derived profiles (R4.4)") {

        it("returns BaseUrlLocked when preset-derived baseUrl differs from the registry") {
            runTest {
                val registry = registryWith(GLM_TEST_PRESET)
                val store = newStore(registry = registry)
                val drifted = presetProfile(
                    profileId = "drift",
                    presetId = GLM_TEST_PRESET.presetId,
                    baseUrl = "https://attacker.example.com",
                )

                val result = store.upsert(drifted)

                result.isFailure shouldBe true
                result.exceptionOrNull() shouldBe ProviderProfileStoreError.BaseUrlLocked

                // Importantly, the bad write must not have hit the store.
                store.get("drift").shouldBeNull()
            }
        }

        it("accepts preset-derived upsert when baseUrl matches the registry") {
            runTest {
                val registry = registryWith(GLM_TEST_PRESET)
                val store = newStore(registry = registry)
                val ok = presetProfile(
                    profileId = "ok",
                    presetId = GLM_TEST_PRESET.presetId,
                    baseUrl = GLM_TEST_PRESET.baseUrl,
                )

                store.upsert(ok).isSuccess shouldBe true
                store.get("ok") shouldBe ok
            }
        }

        it("does not enforce the lock when the registry no longer knows the preset") {
            runTest {
                // Registry is empty -> findById returns null -> the store
                // falls through to the Custom-style write path. This
                // matches design §3 / R4.4 which scopes the lock to
                // *known* presets, allowing older builds' profiles to
                // remain editable after a preset is removed.
                val store = newStore(registry = emptyRegistry())
                val orphaned = presetProfile(
                    profileId = "orphan",
                    presetId = "ghost_preset",
                    baseUrl = "https://anywhere.example.com",
                )

                store.upsert(orphaned).isSuccess shouldBe true
                store.get("orphan") shouldBe orphaned
            }
        }
    }

    describe("deleteAll empties the store and clears active (R9.5, Property 17)") {

        it("removes every profile entry and the active id pointer") {
            runTest {
                val store = newStore()
                val a = customProfile(profileId = "a")
                val b = customProfile(profileId = "b")
                store.upsert(a).isSuccess shouldBe true
                store.upsert(b).isSuccess shouldBe true
                store.setActive("a").isSuccess shouldBe true

                store.deleteAll().isSuccess shouldBe true

                store.list() shouldBe emptyList()
                store.getActive().shouldBeNull()
            }
        }
    }

    describe("upsert preserves data across multiple writes") {

        it("overwrites an existing profile with new field values") {
            runTest {
                val store = newStore()
                val v1 = customProfile(
                    profileId = "p",
                    displayName = "V1",
                    apiKey = "k-v1",
                    updatedAt = 1_000L,
                )
                val v2 = v1.copy(
                    displayName = "V2",
                    apiKey = "k-v2",
                    updatedAt = 2_000L,
                )

                store.upsert(v1).isSuccess shouldBe true
                store.upsert(v2).isSuccess shouldBe true

                store.get("p") shouldBe v2
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Test fixtures and helpers
// ---------------------------------------------------------------------------

/**
 * Single-preset registry used in `BaseUrlLocked` tests. Mirrors the
 * shape of the real GLM Coding preset but lives in the test package so
 * the production constants stay `internal`.
 */
private val GLM_TEST_PRESET: ProviderPreset = ProviderPreset(
    presetId = "glm_coding_plan_test",
    displayNameResId = 0,
    baseUrl = "https://open.bigmodel.cn/api/anthropic",
    defaultModel = "glm-4.6",
    defaultSmallFastModel = null,
    authHeaderStyle = AuthHeaderStyle.AuthToken,
)

private fun registryWith(vararg presets: ProviderPreset): ProviderRegistry =
    object : ProviderRegistry {
        override fun allPresets(): List<ProviderPreset> = presets.toList()
        override fun findById(presetId: String): ProviderPreset? =
            presets.firstOrNull { it.presetId == presetId }
    }

private fun emptyRegistry(): ProviderRegistry =
    object : ProviderRegistry {
        override fun allPresets(): List<ProviderPreset> = emptyList()
        override fun findById(presetId: String): ProviderPreset? = null
    }

private val testDispatchers = object : CoroutineDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}

private fun newStore(
    registry: ProviderRegistry = emptyRegistry(),
): ProviderProfileStoreImpl {
    val prefs = FakeSharedPreferences()
    return ProviderProfileStoreImpl(
        prefs = prefs,
        registry = registry,
        dispatchers = testDispatchers,
    )
}

private fun newStoreWithPrefs(
    registry: ProviderRegistry = emptyRegistry(),
): Pair<FakeSharedPreferences, ProviderProfileStoreImpl> {
    val prefs = FakeSharedPreferences()
    val store = ProviderProfileStoreImpl(
        prefs = prefs,
        registry = registry,
        dispatchers = testDispatchers,
    )
    return prefs to store
}

private fun customProfile(
    profileId: String,
    displayName: String = "Custom $profileId",
    baseUrl: String = "https://api.anthropic.com",
    apiKey: String = "sk-test-$profileId",
    model: String = "claude-3-5-sonnet-20241022",
    smallFastModel: String? = null,
    authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.ApiKey,
    createdAt: Long = 1_000L,
    updatedAt: Long = 1_000L,
): ProviderProfile = ProviderProfile(
    profileId = profileId,
    displayName = displayName,
    presetReference = PresetReference.Custom,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    smallFastModel = smallFastModel,
    authHeaderStyle = authHeaderStyle,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun presetProfile(
    profileId: String,
    presetId: String,
    baseUrl: String,
    displayName: String = "Preset $profileId",
    apiKey: String = "sk-test-$profileId",
    model: String = "glm-4.6",
    smallFastModel: String? = null,
    authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.AuthToken,
    createdAt: Long = 1_000L,
    updatedAt: Long = 1_000L,
): ProviderProfile = ProviderProfile(
    profileId = profileId,
    displayName = displayName,
    presetReference = PresetReference.Preset(presetId),
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    smallFastModel = smallFastModel,
    authHeaderStyle = authHeaderStyle,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ---------------------------------------------------------------------------
// Fake SharedPreferences
//
// A minimal in-memory implementation of the SharedPreferences contract
// covering only the surface used by ProviderProfileStoreImpl:
//   - getString / getAll / contains
//   - edit() / putString / remove / clear / commit / apply
// Listeners are not implemented because task 3.4 covers synchronous
// CRUD only; the listener-driven flow methods are stubbed in the impl.
// ---------------------------------------------------------------------------

private enum class FakeOpKind { Put, Remove, Clear }

private data class FakeOp(
    val kind: FakeOpKind,
    val key: String?,
    val value: String?,
)

private class FakeSharedPreferences : SharedPreferences {

    private val backing: MutableMap<String, Any?> = LinkedHashMap()

    /** When true, every committed editor records its ops into [recordedOps]. */
    var recordWrites: Boolean = false
    val recordedOps: MutableList<FakeOp> = mutableListOf()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(backing)

    override fun getString(key: String, defValue: String?): String? =
        backing[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        backing[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        backing[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        backing[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        backing[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        backing[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = backing.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        // No-op: task 3.4 does not exercise listener-driven flows.
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        // No-op.
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val staged: MutableList<FakeOp> = mutableListOf()
        private var clearRequested: Boolean = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            staged += FakeOp(FakeOpKind.Put, key, value)
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            // Not used by the store; kept compatible with the contract.
            staged += FakeOp(FakeOpKind.Put, key, null)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            staged += FakeOp(FakeOpKind.Put, key, value.toString())
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            staged += FakeOp(FakeOpKind.Put, key, value.toString())
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            staged += FakeOp(FakeOpKind.Put, key, value.toString())
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            staged += FakeOp(FakeOpKind.Put, key, value.toString())
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            staged += FakeOp(FakeOpKind.Remove, key, null)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            staged += FakeOp(FakeOpKind.Clear, null, null)
            return this
        }

        override fun commit(): Boolean {
            applyStaged()
            return true
        }

        override fun apply() {
            applyStaged()
        }

        private fun applyStaged() {
            if (clearRequested) backing.clear()
            for (op in staged) {
                when (op.kind) {
                    FakeOpKind.Put -> if (op.key != null) backing[op.key] = op.value
                    FakeOpKind.Remove -> if (op.key != null) backing.remove(op.key)
                    FakeOpKind.Clear -> {
                        // Already handled above.
                    }
                }
                if (recordWrites) recordedOps += op
            }
            staged.clear()
            clearRequested = false
        }
    }
}
