package com.claudemobile.core.data.providers

import android.content.SharedPreferences
import app.cash.turbine.test
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderRegistry
import com.claudemobile.core.domain.providers.ProviderPreset
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Reactive-flow tests for [ProviderProfileStoreImpl] (task 3.5).
 *
 * Exercises the `callbackFlow`-backed `observeProfiles` /
 * `observeActiveProfile` flows against a listener-aware in-memory
 * [SharedPreferences] fake so that the production listener plumbing is
 * actually invoked without pulling in the Android Keystore for tests.
 *
 * Validates: Requirements 4.1, 5.2, 11.6.
 *
 * Properties exercised:
 *  - Property 4: profile list sort invariant (sorted by `updatedAt` desc).
 *  - Property 7: deleting the active profile clears the active reference
 *    (observed here as a `null` emission on `observeActiveProfile`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderProfileStoreObservabilityTest : DescribeSpec({

    describe("observeActiveProfile") {

        it("emits within 200 ms of setActive (R5.2, R11.6)") {
            runTest {
                val store = newStore()
                val profile = customProfile("p-active", apiKey = "k1")
                store.upsert(profile).isSuccess shouldBe true

                store.observeActiveProfile().test {
                    // Initial emission: no active profile yet.
                    awaitItem().shouldBeNull()

                    val elapsed = measureTimeMillis {
                        store.setActive("p-active").isSuccess shouldBe true
                        // Constrain the wait so a regression that loses
                        // the listener fire (or the synchronous commit)
                        // surfaces as a timeout rather than hanging.
                        withTimeout(200) {
                            awaitItem() shouldBe profile
                        }
                    }
                    elapsed shouldBeLessThan 200L

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("emits null when the active profile is deleted (R4.6, Property 7)") {
            runTest {
                val store = newStore()
                val profile = customProfile("p-doomed", apiKey = "k1")
                store.upsert(profile).isSuccess shouldBe true
                store.setActive("p-doomed").isSuccess shouldBe true

                store.observeActiveProfile().test {
                    // Initial emission reflects the already-active state.
                    awaitItem() shouldBe profile

                    // Deleting the active profile must clear the active
                    // pointer in the same commit and therefore emit null.
                    store.delete("p-doomed").isSuccess shouldBe true

                    withTimeout(200) {
                        awaitItem().shouldBeNull()
                    }

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }

    describe("observeProfiles") {

        it("maintains updatedAt-descending sort order across mutations (Property 4)") {
            runTest {
                val store = newStore()

                val a = customProfile("p-A", updatedAt = 1_000L)
                val b = customProfile("p-B", updatedAt = 3_000L)
                val c = customProfile("p-C", updatedAt = 2_000L)

                store.observeProfiles().test {
                    // Initial empty snapshot.
                    awaitItem() shouldBe emptyList()

                    store.upsert(a).isSuccess shouldBe true
                    awaitItem().map { it.profileId } shouldContainExactly listOf("p-A")

                    store.upsert(b).isSuccess shouldBe true
                    awaitItem().map { it.profileId } shouldContainExactly listOf("p-B", "p-A")

                    store.upsert(c).isSuccess shouldBe true
                    val three = awaitItem()
                    three.map { it.profileId } shouldContainExactly listOf("p-B", "p-C", "p-A")

                    // Sort invariant: every adjacent pair must be in
                    // non-increasing updatedAt order. This is checked
                    // explicitly so a future regression that emits an
                    // unsorted snapshot fails on the invariant rather
                    // than only on the exact-id assertion above.
                    three.zipWithNext().forEach { (lhs, rhs) ->
                        (lhs.updatedAt >= rhs.updatedAt) shouldBe true
                    }

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("emits an updated snapshot when an existing profile is edited") {
            runTest {
                val store = newStore()
                val v1 = customProfile("p", displayName = "V1", updatedAt = 1_000L)
                store.upsert(v1).isSuccess shouldBe true

                store.observeProfiles().test {
                    awaitItem().map { it.displayName } shouldContainExactly listOf("V1")

                    val v2 = v1.copy(displayName = "V2", updatedAt = 2_000L)
                    store.upsert(v2).isSuccess shouldBe true

                    awaitItem().map { it.displayName } shouldContainExactly listOf("V2")

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Test fixtures and helpers
// ---------------------------------------------------------------------------

private val testDispatchers = object : CoroutineDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}

private fun emptyRegistry(): ProviderRegistry =
    object : ProviderRegistry {
        override fun allPresets(): List<ProviderPreset> = emptyList()
        override fun findById(presetId: String): ProviderPreset? = null
    }

private fun newStore(): ProviderProfileStoreImpl =
    ProviderProfileStoreImpl(
        prefs = ListenerAwareSharedPreferences(),
        registry = emptyRegistry(),
        dispatchers = testDispatchers,
    )

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

// ---------------------------------------------------------------------------
// Listener-aware fake SharedPreferences
//
// Mirrors the platform's contract for listener semantics that
// [ProviderProfileStoreImpl] relies on:
//
//   - `commit()` is synchronous: it applies staged ops to the backing map
//     and, before returning, fires every registered listener once per
//     changed key on the calling thread.
//   - `clear()` followed by `commit()` fires the listener once with a
//     `null` key (matching the Android API >= 30 behavior).
//   - `apply()` is treated identically to `commit()` for test purposes
//     (sufficient because the production code only uses `commit()`).
//
// The fake intentionally does not implement any thread-safety beyond a
// single-threaded test execution because all tests in this spec run on
// `Dispatchers.Unconfined` and never share an instance across coroutines.
// ---------------------------------------------------------------------------

private class ListenerAwareSharedPreferences : SharedPreferences {

    private val backing: MutableMap<String, Any?> = LinkedHashMap()
    private val listeners: MutableSet<SharedPreferences.OnSharedPreferenceChangeListener> =
        linkedSetOf()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(backing)

    override fun getString(key: String, defValue: String?): String? =
        backing[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = backing[key] as? MutableSet<String> ?: defValues

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
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners.remove(listener)
    }

    private fun fire(key: String?) {
        // Snapshot to avoid ConcurrentModificationException if a listener
        // reacts by registering / unregistering during the callback.
        for (l in listeners.toList()) {
            l.onSharedPreferenceChanged(this, key)
        }
    }

    private inner class FakeEditor : SharedPreferences.Editor {

        private val staged: MutableList<EditorOp> = mutableListOf()
        private var clearRequested: Boolean = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            staged += EditorOp(EditorOpKind.Put, key, value)
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            staged += EditorOp(EditorOpKind.Put, key, null)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            staged += EditorOp(EditorOpKind.Put, key, value.toString())
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            staged += EditorOp(EditorOpKind.Put, key, value.toString())
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            staged += EditorOp(EditorOpKind.Put, key, value.toString())
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            staged += EditorOp(EditorOpKind.Put, key, value.toString())
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            staged += EditorOp(EditorOpKind.Remove, key, null)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            staged += EditorOp(EditorOpKind.Clear, null, null)
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
            val firedKeys = mutableListOf<String?>()
            if (clearRequested) {
                backing.clear()
                firedKeys += null
            }
            for (op in staged) {
                when (op.kind) {
                    EditorOpKind.Put -> if (op.key != null) {
                        backing[op.key] = op.value
                        firedKeys += op.key
                    }
                    EditorOpKind.Remove -> if (op.key != null && backing.containsKey(op.key)) {
                        backing.remove(op.key)
                        firedKeys += op.key
                    }
                    EditorOpKind.Clear -> {
                        // Already handled above.
                    }
                }
            }
            staged.clear()
            clearRequested = false
            // Fire listeners after the commit has fully landed so that
            // listener callbacks reading the SP observe the new state.
            for (key in firedKeys) fire(key)
        }
    }
}

private enum class EditorOpKind { Put, Remove, Clear }

private data class EditorOp(
    val kind: EditorOpKind,
    val key: String?,
    val value: String?,
)
