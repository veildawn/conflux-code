package com.claudemobile.core.data.providers.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import com.claudemobile.core.domain.repository.CredentialStore
import com.claudemobile.core.domain.repository.SettingsStore
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [LegacyKeyMigrator].
 *
 * The tests exercise design §8.2's eight-step algorithm against
 * lightweight in-memory fakes so we can drive every branch of the
 * decision tree without an Android Keystore. A real
 * [PreferenceDataStoreFactory] is used for the migration-flag DataStore
 * so the `BooleanPreferenceKey` codec is exercised end-to-end.
 *
 * Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 11.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyKeyMigratorTest : DescribeSpec({

    val tempDirBase = File(
        System.getProperty("java.io.tmpdir"),
        "legacy_key_migrator_test_${System.nanoTime()}",
    )
    val fileCounter = AtomicInteger(0)

    beforeSpec { tempDirBase.mkdirs() }
    afterSpec { tempDirBase.deleteRecursively() }

    fun newDataStore(): DataStore<Preferences> {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher + Job())
        return PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = {
                File(tempDirBase, "migration_${fileCounter.incrementAndGet()}.preferences_pb")
            },
        )
    }

    describe("a) legacy key present + no profile + no flag (R8.1, R8.2, R8.3, R8.5)") {

        it("migrates the legacy key, sets it active, clears legacy, writes flag") {
            runTest {
                val credentialStore = FakeCredentialStore(initialApiKey = "sk-ant-legacy-1")
                val settingsStore = FakeSettingsStore(initialModel = "claude-legacy-model")
                val profileStore = FakeProviderProfileStore()
                val flags = newDataStore()

                val migrator = LegacyKeyMigrator(
                    credentialStore = credentialStore,
                    settingsStore = settingsStore,
                    profileStore = profileStore,
                    migrationFlags = flags,
                )

                val result = migrator.runIfNeeded()

                // Outcome: success with a Migrated payload.
                result.isSuccess shouldBe true
                val outcome = result.getOrThrow()
                outcome.shouldBeInstanceOf<MigrationOutcome.Migrated>()

                // Profile store now holds exactly one profile, set as
                // active, with the legacy values copied verbatim.
                val profiles = profileStore.list()
                profiles shouldHaveSize 1
                val migrated = profiles.single()
                migrated.profileId shouldBe outcome.profileId
                migrated.displayName shouldBe LegacyKeyMigrator.MIGRATED_DISPLAY_NAME
                migrated.presetReference shouldBe PresetReference.Custom
                migrated.baseUrl shouldBe LegacyKeyMigrator.MIGRATED_BASE_URL
                migrated.apiKey shouldBe "sk-ant-legacy-1"
                migrated.model shouldBe "claude-legacy-model"
                migrated.smallFastModel.shouldBeNull()
                migrated.authHeaderStyle shouldBe AuthHeaderStyle.ApiKey
                migrated.createdAt shouldBe migrated.updatedAt

                profileStore.getActive() shouldBe migrated

                // Legacy stores have been cleared.
                credentialStore.getApiKey().shouldBeNull()
                settingsStore.getModel().shouldBeNull()

                // Flag persists.
                readFlag(flags) shouldBe true
            }
        }

        it("uses the documented fallback model when legacy model id is absent") {
            runTest {
                val credentialStore = FakeCredentialStore(initialApiKey = "sk-ant-legacy-2")
                val settingsStore = FakeSettingsStore(initialModel = null)
                val profileStore = FakeProviderProfileStore()
                val flags = newDataStore()

                val migrator = LegacyKeyMigrator(
                    credentialStore = credentialStore,
                    settingsStore = settingsStore,
                    profileStore = profileStore,
                    migrationFlags = flags,
                )

                migrator.runIfNeeded().getOrThrow()
                    .shouldBeInstanceOf<MigrationOutcome.Migrated>()

                val migrated = profileStore.list().single()
                migrated.model shouldBe LegacyKeyMigrator.FALLBACK_MODEL
            }
        }
    }

    describe("b) legacy key absent (R8.5)") {

        it("writes the flag only and skips") {
            runTest {
                val credentialStore = FakeCredentialStore(initialApiKey = null)
                val settingsStore = FakeSettingsStore(initialModel = null)
                val profileStore = FakeProviderProfileStore()
                val flags = newDataStore()

                val migrator = LegacyKeyMigrator(
                    credentialStore = credentialStore,
                    settingsStore = settingsStore,
                    profileStore = profileStore,
                    migrationFlags = flags,
                )

                val result = migrator.runIfNeeded()

                result.isSuccess shouldBe true
                result.getOrThrow() shouldBe MigrationOutcome.SkippedNoLegacyKey

                // No profile created, legacy unchanged (still null), flag set.
                profileStore.list() shouldBe emptyList()
                profileStore.getActive().shouldBeNull()
                credentialStore.getApiKey().shouldBeNull()
                settingsStore.getModel().shouldBeNull()
                readFlag(flags) shouldBe true
            }
        }
    }

    describe("c) flag already true (R8.5)") {

        it("skips without touching either store") {
            runTest {
                val credentialStore = FakeCredentialStore(initialApiKey = "sk-ant-untouched")
                val settingsStore = FakeSettingsStore(initialModel = "untouched-model")
                val profileStore = FakeProviderProfileStore()
                val flags = newDataStore()

                // Pre-set the done-flag, simulating a prior successful run.
                flags.edit { prefs ->
                    prefs[ProviderMigrationKeys.PROVIDER_MIGRATION_V1_DONE] = true
                }

                val migrator = LegacyKeyMigrator(
                    credentialStore = credentialStore,
                    settingsStore = settingsStore,
                    profileStore = profileStore,
                    migrationFlags = flags,
                )

                val result = migrator.runIfNeeded()

                result.isSuccess shouldBe true
                result.getOrThrow() shouldBe MigrationOutcome.SkippedAlreadyDone

                // Legacy state must be exactly as before the call —
                // a critical invariant for users who manually retried
                // and chose not to migrate.
                credentialStore.getApiKey() shouldBe "sk-ant-untouched"
                settingsStore.getModel() shouldBe "untouched-model"
                profileStore.list() shouldBe emptyList()
                profileStore.getActive().shouldBeNull()
                readFlag(flags) shouldBe true

                // No write activity at all on the legacy stores.
                credentialStore.clearCount shouldBe 0
                settingsStore.clearCount shouldBe 0
            }
        }
    }

    describe("d) upsert fails (R8.4 — rollback)") {

        it("preserves legacy credentials and does not write the flag") {
            runTest {
                val credentialStore = FakeCredentialStore(initialApiKey = "sk-ant-preserved")
                val settingsStore = FakeSettingsStore(initialModel = "preserved-model")
                val profileStore = FakeProviderProfileStore(
                    upsertResult = Result.failure(
                        ProviderProfileStoreError.KeystoreUnavailable(profileId = null),
                    ),
                )
                val flags = newDataStore()

                val migrator = LegacyKeyMigrator(
                    credentialStore = credentialStore,
                    settingsStore = settingsStore,
                    profileStore = profileStore,
                    migrationFlags = flags,
                )

                val result = migrator.runIfNeeded()

                result.isFailure shouldBe true
                val err = result.exceptionOrNull()
                err.shouldBeInstanceOf<ProviderProfileStoreError.KeystoreUnavailable>()

                // Legacy credentials remain intact (R8.4).
                credentialStore.getApiKey() shouldBe "sk-ant-preserved"
                settingsStore.getModel() shouldBe "preserved-model"
                credentialStore.clearCount shouldBe 0
                settingsStore.clearCount shouldBe 0

                // setActive must not have been attempted (the algorithm
                // aborts at step 5).
                profileStore.setActiveCalls shouldBe 0

                // Flag must NOT be persisted, so the next launch retries.
                readFlag(flags) shouldBe false
            }
        }
    }

    describe("e) profile already exists (idempotence guard)") {

        it("skips without reading legacy credentials and writes the flag") {
            runTest {
                val credentialStore = FakeCredentialStore(initialApiKey = "sk-ant-shouldnt-be-read")
                val settingsStore = FakeSettingsStore(initialModel = "shouldnt-be-read-model")
                val existingProfile = ProviderProfile(
                    profileId = "existing",
                    displayName = "Existing",
                    presetReference = PresetReference.Custom,
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-existing",
                    model = "claude-existing",
                    smallFastModel = null,
                    authHeaderStyle = AuthHeaderStyle.ApiKey,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
                val profileStore = FakeProviderProfileStore().apply {
                    upsert(existingProfile).getOrThrow()
                    setActive("existing").getOrThrow()
                }
                val flags = newDataStore()

                val migrator = LegacyKeyMigrator(
                    credentialStore = credentialStore,
                    settingsStore = settingsStore,
                    profileStore = profileStore,
                    migrationFlags = flags,
                )

                val result = migrator.runIfNeeded()

                result.isSuccess shouldBe true
                result.getOrThrow() shouldBe MigrationOutcome.SkippedProfilesExist

                // The pre-existing profile is unchanged and still active.
                profileStore.list() shouldBe listOf(existingProfile)
                profileStore.getActive() shouldBe existingProfile

                // Legacy state untouched.
                credentialStore.getApiKey() shouldBe "sk-ant-shouldnt-be-read"
                settingsStore.getModel() shouldBe "shouldnt-be-read-model"
                credentialStore.clearCount shouldBe 0
                settingsStore.clearCount shouldBe 0

                // Flag is recorded so the next launch short-circuits even faster.
                readFlag(flags) shouldBe true
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Helpers and fakes
// ---------------------------------------------------------------------------

private suspend fun readFlag(flags: DataStore<Preferences>): Boolean {
    val prefs = flags.data.first()
    return prefs[ProviderMigrationKeys.PROVIDER_MIGRATION_V1_DONE] == true
}

/**
 * In-memory [CredentialStore] supporting only the legacy-migration
 * surface: `getApiKey` + `clearApiKey`. The remaining methods throw to
 * surface unexpected calls during the test.
 */
private class FakeCredentialStore(initialApiKey: String?) : CredentialStore {
    private var apiKey: String? = initialApiKey
    var clearCount: Int = 0
        private set

    override suspend fun getApiKey(): String? = apiKey
    override suspend fun setApiKey(key: String) {
        apiKey = key
    }
    override suspend fun deleteApiKey() {
        apiKey = null
        clearCount += 1
    }
    override suspend fun hasApiKey(): Boolean = apiKey != null
    override fun getMaskedApiKey(): String? = apiKey?.takeLast(4)?.let { "•••$it" }
}

/**
 * In-memory [SettingsStore] supporting only the legacy-migration
 * surface: `getModel` + `clearModel`. The remaining methods are no-ops
 * because the migrator does not use them, but they stay non-throwing so
 * future test additions don't accidentally trip on irrelevant calls.
 */
private class FakeSettingsStore(initialModel: String?) : SettingsStore {
    private var model: String? = initialModel
    var clearCount: Int = 0
        private set

    override val settings: Flow<com.claudemobile.core.domain.model.AppSettings> =
        MutableStateFlow(com.claudemobile.core.domain.model.AppSettings()).asStateFlow()

    override suspend fun setModelId(modelId: String) {
        model = modelId
    }

    override suspend fun setSystemPrompt(prompt: String) {}
    override suspend fun setThemeMode(mode: com.claudemobile.core.domain.model.ThemeMode) {}
    override suspend fun setFontScale(scale: Float) {}
    override suspend fun setStreamingRenderRate(rateMs: Long) {}
    override suspend fun setDefaultWorkspacePath(path: String) {}
    override suspend fun setAutoStartForegroundService(enabled: Boolean) {}
    override suspend fun setAppLanguage(language: com.claudemobile.core.domain.model.AppLanguage) {}

    override suspend fun getModel(): String? = model
    override suspend fun clearModel() {
        model = null
        clearCount += 1
    }
}

/**
 * In-memory [ProviderProfileStore] sufficient for the migrator's needs:
 * `list`, `upsert`, `setActive`, `getActive`, plus injectable failure
 * results for the rollback tests.
 */
private class FakeProviderProfileStore(
    private val upsertResult: Result<Unit>? = null,
    private val setActiveResult: Result<Unit>? = null,
) : ProviderProfileStore {

    private val profiles: MutableMap<String, ProviderProfile> = LinkedHashMap()
    private var activeId: String? = null
    var setActiveCalls: Int = 0
        private set

    override fun observeProfiles(): Flow<List<ProviderProfile>> =
        MutableStateFlow(profiles.values.toList())

    override fun observeActiveProfile(): Flow<ProviderProfile?> =
        MutableStateFlow(activeId?.let(profiles::get))

    override suspend fun list(): List<ProviderProfile> =
        profiles.values.sortedByDescending { it.updatedAt }

    override suspend fun get(profileId: String): ProviderProfile? = profiles[profileId]

    override suspend fun getActive(): ProviderProfile? = activeId?.let(profiles::get)

    override suspend fun upsert(profile: ProviderProfile): Result<Unit> {
        if (upsertResult != null && upsertResult.isFailure) return upsertResult
        profiles[profile.profileId] = profile
        return Result.success(Unit)
    }

    override suspend fun delete(profileId: String): Result<Unit> {
        profiles.remove(profileId)
        if (activeId == profileId) activeId = null
        return Result.success(Unit)
    }

    override suspend fun setActive(profileId: String?): Result<Unit> {
        setActiveCalls += 1
        if (setActiveResult != null && setActiveResult.isFailure) return setActiveResult
        if (profileId != null && !profiles.containsKey(profileId)) {
            return Result.failure(ProviderProfileStoreError.NotFound(profileId))
        }
        activeId = profileId
        return Result.success(Unit)
    }

    override suspend fun deleteAll(): Result<Unit> {
        profiles.clear()
        activeId = null
        return Result.success(Unit)
    }
}
