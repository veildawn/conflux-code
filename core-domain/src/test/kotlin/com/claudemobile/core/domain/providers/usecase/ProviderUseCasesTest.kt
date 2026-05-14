package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderPreset
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileDraft
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Instant

/**
 * Unit tests for the seven provider use cases against a fake
 * [ProviderProfileStore].
 *
 * Use cases under test:
 *  - [CreateFromPresetUseCase]
 *  - [CreateCustomUseCase]
 *  - [UpdateProfileUseCase]
 *  - [DeleteProfileUseCase]
 *  - [SetActiveProfileUseCase]
 *  - [ListProfilesUseCase]
 *  - [GetActiveProfileUseCase]
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 3.1, 3.7, 4.2, 4.3, 4.5, 5.1,
 * 5.2.
 */
class ProviderUseCasesTest : DescribeSpec({

    // ---------------------------------------------------------------------
    // Common fixtures.
    // ---------------------------------------------------------------------

    val glmPreset = ProviderPreset(
        presetId = "glm_coding_plan",
        displayNameResId = 0,
        baseUrl = "https://open.bigmodel.cn/api/anthropic",
        defaultModel = "glm-4.6",
        defaultSmallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.AuthToken,
    )

    val kimiPresetWithSmallFast = ProviderPreset(
        presetId = "kimi_code_plan",
        displayNameResId = 0,
        baseUrl = "https://api.moonshot.cn/anthropic",
        defaultModel = "kimi-k2-turbo-preview",
        defaultSmallFastModel = "kimi-fast",
        authHeaderStyle = AuthHeaderStyle.AuthToken,
    )

    fun customDraft(
        displayName: String = "My Custom",
        baseUrl: String = "https://custom.example.com",
        apiKey: String = "key-1",
        model: String = "custom-model",
        smallFastModel: String? = null,
        authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.ApiKey,
    ) = ProviderProfileDraft(
        displayName = displayName,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        smallFastModel = smallFastModel,
        authHeaderStyle = authHeaderStyle,
        presetReference = PresetReference.Custom,
    )

    /**
     * Sequential UUID generator for deterministic ids in tests.
     * Returns `id-1`, `id-2`, ... — the value carries no semantic
     * meaning beyond being unique across calls within a single test.
     */
    fun seqUuids(): UuidGenerator {
        var n = 0
        return object : UuidGenerator {
            override fun generate(): String {
                n += 1
                return "id-$n"
            }
        }
    }

    /** Fixed-clock time provider; tests advance manually if they care. */
    fun fixedClock(epochMillis: Long): TimeProvider = object : TimeProvider {
        override fun now(): Instant = Instant.ofEpochMilli(epochMillis)
    }

    // ---------------------------------------------------------------------
    // CreateFromPresetUseCase.
    // ---------------------------------------------------------------------

    describe("CreateFromPresetUseCase") {

        it("copies preset fields and assigns generated id + timestamps (R2 AC1, AC2; Property 20)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateFromPresetUseCase(
                    store = store,
                    uuidGenerator = seqUuids(),
                    timeProvider = fixedClock(1_000L),
                )

                val result = useCase(preset = glmPreset, apiKey = "user-key")

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                val profile = result.value
                profile.profileId shouldBe "id-1"
                profile.presetReference shouldBe PresetReference.Preset(glmPreset.presetId)
                profile.baseUrl shouldBe glmPreset.baseUrl
                profile.model shouldBe glmPreset.defaultModel
                profile.smallFastModel shouldBe glmPreset.defaultSmallFastModel // null
                profile.authHeaderStyle shouldBe glmPreset.authHeaderStyle
                profile.apiKey shouldBe "user-key"
                profile.createdAt shouldBe 1_000L
                profile.updatedAt shouldBe 1_000L

                // Round-trip via the store.
                store.list().shouldContainExactly(profile)
            }
        }

        it("uses preset.defaultSmallFastModel when no override is supplied (R1 AC5)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateFromPresetUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(preset = kimiPresetWithSmallFast, apiKey = "k")

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                result.value.smallFastModel shouldBe "kimi-fast"
            }
        }

        it("applies displayName / model / smallFastModel overrides (R2 AC3)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateFromPresetUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(
                    preset = glmPreset,
                    apiKey = "k",
                    displayNameOverride = "Work GLM",
                    modelOverride = "glm-4-air",
                    smallFastModelOverride = "glm-fast",
                )

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                with(result.value) {
                    displayName shouldBe "Work GLM"
                    model shouldBe "glm-4-air"
                    smallFastModel shouldBe "glm-fast"
                    // baseUrl / authHeaderStyle / presetReference still come from preset.
                    baseUrl shouldBe glmPreset.baseUrl
                    authHeaderStyle shouldBe glmPreset.authHeaderStyle
                }
            }
        }

        it("blank displayName / model overrides fall back to preset values") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateFromPresetUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(
                    preset = glmPreset,
                    apiKey = "k",
                    displayNameOverride = "   ",
                    modelOverride = "",
                )

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                with(result.value) {
                    displayName shouldBe "preset:${glmPreset.presetId}"
                    model shouldBe glmPreset.defaultModel
                }
            }
        }

        it("rejects empty apiKey with INVALID_ARGUMENT and does not persist (R2 AC4 defence-in-depth)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateFromPresetUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(preset = glmPreset, apiKey = "")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
                store.list().shouldBe(emptyList())
            }
        }

        it("yields pairwise-distinct profileIds across multiple invocations (Property 18)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateFromPresetUseCase(store, seqUuids(), fixedClock(0L))

                val a = useCase(preset = glmPreset, apiKey = "k")
                val b = useCase(preset = glmPreset, apiKey = "k")
                val c = useCase(preset = glmPreset, apiKey = "k")

                val ids = listOf(a, b, c).map {
                    (it as AppResult.Success).value.profileId
                }
                ids.toSet().size shouldBe 3
            }
        }

        it("propagates store failure as STORAGE_ERROR") {
            runTest {
                val store = FakeProviderProfileStore().apply {
                    nextUpsertError = RuntimeException("disk full")
                }
                val useCase = CreateFromPresetUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(preset = glmPreset, apiKey = "k")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.STORAGE_ERROR
            }
        }
    }

    // ---------------------------------------------------------------------
    // CreateCustomUseCase.
    // ---------------------------------------------------------------------

    describe("CreateCustomUseCase") {

        it("persists a new Custom profile from a valid draft (R3 AC1)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(2_000L))

                val result = useCase(customDraft())

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                val profile = result.value
                profile.profileId shouldBe "id-1"
                profile.presetReference shouldBe PresetReference.Custom
                profile.baseUrl shouldBe "https://custom.example.com"
                profile.apiKey shouldBe "key-1"
                profile.model shouldBe "custom-model"
                profile.smallFastModel shouldBe null
                profile.authHeaderStyle shouldBe AuthHeaderStyle.ApiKey
                profile.createdAt shouldBe 2_000L
                profile.updatedAt shouldBe 2_000L
                store.list().shouldContainExactly(profile)
            }
        }

        it("forces presetReference to Custom even if draft carries a Preset reference (defence-in-depth)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(0L))

                val drafted = customDraft().copy(
                    presetReference = PresetReference.Preset("glm_coding_plan"),
                )

                val result = useCase(drafted)

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                result.value.presetReference shouldBe PresetReference.Custom
            }
        }

        it("preserves authHeaderStyle from the draft (R3 AC7)") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(customDraft(authHeaderStyle = AuthHeaderStyle.AuthToken))

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                result.value.authHeaderStyle shouldBe AuthHeaderStyle.AuthToken
            }
        }

        it("preserves a non-empty smallFastModel and treats empty string as null") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(0L))

                val withSmallFast = useCase(customDraft(smallFastModel = "fast-1"))
                withSmallFast.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                withSmallFast.value.smallFastModel shouldBe "fast-1"

                val empty = useCase(customDraft(smallFastModel = ""))
                empty.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                empty.value.smallFastModel shouldBe null
            }
        }

        it("rejects invalid drafts (empty apiKey) with INVALID_ARGUMENT") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(customDraft(apiKey = ""))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
                store.list().shouldBe(emptyList())
            }
        }

        it("rejects invalid drafts (malformed baseUrl) with INVALID_ARGUMENT") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(customDraft(baseUrl = "not-a-url"))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
                store.list().shouldBe(emptyList())
            }
        }

        it("rejects invalid drafts (blank model) with INVALID_ARGUMENT") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(customDraft(model = "   "))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
            }
        }

        it("rejects invalid drafts (blank displayName) with INVALID_ARGUMENT") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = CreateCustomUseCase(store, seqUuids(), fixedClock(0L))

                val result = useCase(customDraft(displayName = ""))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
            }
        }
    }

    // ---------------------------------------------------------------------
    // UpdateProfileUseCase.
    // ---------------------------------------------------------------------

    describe("UpdateProfileUseCase") {

        suspend fun seedCustom(store: FakeProviderProfileStore): ProviderProfile {
            val seeded = ProviderProfile(
                profileId = "p-custom",
                displayName = "Original",
                presetReference = PresetReference.Custom,
                baseUrl = "https://orig.example.com",
                apiKey = "orig-key",
                model = "orig-model",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.ApiKey,
                createdAt = 100L,
                updatedAt = 100L,
            )
            store.upsert(seeded).getOrThrow()
            return seeded
        }

        suspend fun seedPreset(store: FakeProviderProfileStore): ProviderProfile {
            val seeded = ProviderProfile(
                profileId = "p-preset",
                displayName = "GLM",
                presetReference = PresetReference.Preset("glm_coding_plan"),
                baseUrl = "https://open.bigmodel.cn/api/anthropic",
                apiKey = "preset-key",
                model = "glm-4.6",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.AuthToken,
                createdAt = 100L,
                updatedAt = 100L,
            )
            store.upsert(seeded).getOrThrow()
            return seeded
        }

        it("updates allowed fields on a Custom profile and advances updatedAt (R4 AC2, AC3)") {
            runTest {
                val store = FakeProviderProfileStore()
                seedCustom(store)
                val useCase = UpdateProfileUseCase(store, fixedClock(500L))

                val result = useCase(
                    profileId = "p-custom",
                    displayName = "Renamed",
                    apiKey = "new-key",
                    model = "new-model",
                    baseUrl = "https://new.example.com",
                    authHeaderStyle = AuthHeaderStyle.AuthToken,
                )

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                with(result.value) {
                    displayName shouldBe "Renamed"
                    apiKey shouldBe "new-key"
                    model shouldBe "new-model"
                    baseUrl shouldBe "https://new.example.com"
                    authHeaderStyle shouldBe AuthHeaderStyle.AuthToken
                    createdAt shouldBe 100L
                    updatedAt shouldBe 500L
                }
            }
        }

        it("preserves baseUrl and authHeaderStyle on preset-derived profiles (R4 AC2, AC4)") {
            runTest {
                val store = FakeProviderProfileStore()
                seedPreset(store)
                val useCase = UpdateProfileUseCase(store, fixedClock(500L))

                // Caller asks to mutate baseUrl & authStyle: silently ignored
                // (use case re-emits the existing values instead).
                val result = useCase(
                    profileId = "p-preset",
                    displayName = "GLM-Renamed",
                    baseUrl = "https://attacker.example.com",
                    authHeaderStyle = AuthHeaderStyle.ApiKey,
                )

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                with(result.value) {
                    displayName shouldBe "GLM-Renamed"
                    baseUrl shouldBe "https://open.bigmodel.cn/api/anthropic"
                    authHeaderStyle shouldBe AuthHeaderStyle.AuthToken
                }
            }
        }

        it("returns NOT_FOUND for a missing profileId") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = UpdateProfileUseCase(store, fixedClock(0L))

                val result = useCase(profileId = "no-such-profile", displayName = "x")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.NOT_FOUND
            }
        }

        it("returns INVALID_ARGUMENT for a blank profileId") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = UpdateProfileUseCase(store, fixedClock(0L))

                val result = useCase(profileId = "  ", displayName = "x")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
            }
        }

        it("preserves untouched fields when only one delta is supplied") {
            runTest {
                val store = FakeProviderProfileStore()
                val original = seedCustom(store)
                val useCase = UpdateProfileUseCase(store, fixedClock(500L))

                val result = useCase(profileId = "p-custom", apiKey = "rotated-key")

                result.shouldBeInstanceOf<AppResult.Success<ProviderProfile>>()
                with(result.value) {
                    apiKey shouldBe "rotated-key"
                    displayName shouldBe original.displayName
                    model shouldBe original.model
                    baseUrl shouldBe original.baseUrl
                    smallFastModel shouldBe original.smallFastModel
                    authHeaderStyle shouldBe original.authHeaderStyle
                    createdAt shouldBe original.createdAt
                    updatedAt shouldBe 500L
                }
            }
        }

        it("translates BaseUrlLocked store error to INVALID_ARGUMENT") {
            runTest {
                // Defence-in-depth: even though the use case forces existing
                // baseUrl on preset profiles, a hostile store can still
                // return BaseUrlLocked. Verify the mapping.
                val store = FakeProviderProfileStore()
                seedPreset(store)
                store.nextUpsertError = ProviderProfileStoreError.BaseUrlLocked
                val useCase = UpdateProfileUseCase(store, fixedClock(0L))

                val result = useCase(profileId = "p-preset", displayName = "x")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
            }
        }

        it("translates KeystoreUnavailable to KEYSTORE_ERROR") {
            runTest {
                val store = FakeProviderProfileStore()
                seedCustom(store)
                store.nextUpsertError = ProviderProfileStoreError.KeystoreUnavailable("p-custom")
                val useCase = UpdateProfileUseCase(store, fixedClock(0L))

                val result = useCase(profileId = "p-custom", apiKey = "x")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.KEYSTORE_ERROR
            }
        }
    }

    // ---------------------------------------------------------------------
    // DeleteProfileUseCase.
    // ---------------------------------------------------------------------

    describe("DeleteProfileUseCase") {

        it("removes the profile via the store (R4 AC5)") {
            runTest {
                val store = FakeProviderProfileStore()
                store.upsert(
                    ProviderProfile(
                        profileId = "to-delete",
                        displayName = "x",
                        presetReference = PresetReference.Custom,
                        baseUrl = "https://x.example.com",
                        apiKey = "k",
                        model = "m",
                        smallFastModel = null,
                        authHeaderStyle = AuthHeaderStyle.ApiKey,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                ).getOrThrow()
                val useCase = DeleteProfileUseCase(store)

                val result = useCase("to-delete")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                store.get("to-delete") shouldBe null
            }
        }

        it("rejects blank profileId with INVALID_ARGUMENT") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = DeleteProfileUseCase(store)

                val result = useCase("   ")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.INVALID_ARGUMENT
            }
        }

        it("translates store NotFound to NOT_FOUND") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = DeleteProfileUseCase(store)

                val result = useCase("missing")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    // ---------------------------------------------------------------------
    // SetActiveProfileUseCase.
    // ---------------------------------------------------------------------

    describe("SetActiveProfileUseCase") {

        suspend fun seedAndUseCase(): Pair<FakeProviderProfileStore, SetActiveProfileUseCase> {
            val store = FakeProviderProfileStore()
            store.upsert(
                ProviderProfile(
                    profileId = "p-1",
                    displayName = "x",
                    presetReference = PresetReference.Custom,
                    baseUrl = "https://x.example.com",
                    apiKey = "k",
                    model = "m",
                    smallFastModel = null,
                    authHeaderStyle = AuthHeaderStyle.ApiKey,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            ).getOrThrow()
            return store to SetActiveProfileUseCase(store)
        }

        it("sets the active profile (R5 AC1, AC2)") {
            runTest {
                val (store, useCase) = seedAndUseCase()

                val result = useCase("p-1")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                store.getActive()?.profileId shouldBe "p-1"
            }
        }

        it("clears the active profile when given null") {
            runTest {
                val (store, useCase) = seedAndUseCase()
                useCase("p-1").shouldBeInstanceOf<AppResult.Success<Unit>>()

                val result = useCase(null)

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                store.getActive() shouldBe null
            }
        }

        it("returns NOT_FOUND when targeting a non-existent profile") {
            runTest {
                val store = FakeProviderProfileStore()
                val useCase = SetActiveProfileUseCase(store)

                val result = useCase("ghost")

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.code shouldBe ErrorCode.NOT_FOUND
            }
        }
    }

    // ---------------------------------------------------------------------
    // ListProfilesUseCase.
    // ---------------------------------------------------------------------

    describe("ListProfilesUseCase") {

        it("emits the current profile list from the store (R4 AC1)") {
            runTest {
                val store = FakeProviderProfileStore()
                val a = ProviderProfile(
                    profileId = "a", displayName = "A",
                    presetReference = PresetReference.Custom,
                    baseUrl = "https://a.example.com", apiKey = "k", model = "m",
                    smallFastModel = null, authHeaderStyle = AuthHeaderStyle.ApiKey,
                    createdAt = 0, updatedAt = 100,
                )
                val b = a.copy(profileId = "b", displayName = "B", updatedAt = 200)
                store.upsert(a).getOrThrow()
                store.upsert(b).getOrThrow()

                val useCase = ListProfilesUseCase(store)
                val emitted = useCase().first()

                // Fake store maintains arrival order; sort by updatedAt desc.
                emitted.map { it.profileId }.toSet() shouldBe setOf("a", "b")
                emitted.size shouldBe 2
            }
        }
    }

    // ---------------------------------------------------------------------
    // GetActiveProfileUseCase.
    // ---------------------------------------------------------------------

    describe("GetActiveProfileUseCase") {

        it("emits the current active profile and updates on change (R5 AC2)") {
            runTest {
                val store = FakeProviderProfileStore()
                val seeded = ProviderProfile(
                    profileId = "active-1", displayName = "X",
                    presetReference = PresetReference.Custom,
                    baseUrl = "https://x.example.com", apiKey = "k", model = "m",
                    smallFastModel = null, authHeaderStyle = AuthHeaderStyle.ApiKey,
                    createdAt = 0, updatedAt = 0,
                )
                store.upsert(seeded).getOrThrow()

                val useCase = GetActiveProfileUseCase(store)

                // No active yet.
                useCase().first() shouldBe null

                // Set active and re-collect.
                store.setActive("active-1").getOrThrow()
                val active = useCase().first()
                active.shouldNotBeNull()
                active.profileId shouldBe "active-1"
            }
        }
    }
})

/**
 * In-memory fake of [ProviderProfileStore] sufficient for use case unit
 * testing. Maintains insertion order; [observeProfiles] / [list] return
 * profiles sorted by `updatedAt` descending to match the contract
 * specified in design §3.
 *
 * This is intentionally _not_ a full simulation of the encrypted-prefs
 * implementation — that is exercised by `ProviderProfileStoreImplTest`
 * in `core-data`. Here we only need a behavioural surface that the
 * use cases under test interact with.
 */
private class FakeProviderProfileStore : ProviderProfileStore {

    private val profiles = mutableMapOf<String, ProviderProfile>()
    private val profilesFlow = MutableStateFlow<List<ProviderProfile>>(emptyList())
    private val activeIdFlow = MutableStateFlow<String?>(null)

    /** Set non-null to make the next [upsert] call fail. */
    var nextUpsertError: Throwable? = null

    override fun observeProfiles() = profilesFlow

    override fun observeActiveProfile() = kotlinx.coroutines.flow.combine(
        profilesFlow,
        activeIdFlow,
    ) { all, id -> id?.let { all.firstOrNull { p -> p.profileId == it } } }

    override suspend fun list(): List<ProviderProfile> = sortedSnapshot()

    override suspend fun get(profileId: String): ProviderProfile? = profiles[profileId]

    override suspend fun getActive(): ProviderProfile? =
        activeIdFlow.value?.let { profiles[it] }

    override suspend fun upsert(profile: ProviderProfile): Result<Unit> {
        nextUpsertError?.let { err ->
            nextUpsertError = null
            return Result.failure(err)
        }
        profiles[profile.profileId] = profile
        profilesFlow.value = sortedSnapshot()
        return Result.success(Unit)
    }

    override suspend fun delete(profileId: String): Result<Unit> {
        if (profileId !in profiles) {
            return Result.failure(ProviderProfileStoreError.NotFound(profileId))
        }
        profiles.remove(profileId)
        if (activeIdFlow.value == profileId) {
            activeIdFlow.value = null
        }
        profilesFlow.value = sortedSnapshot()
        return Result.success(Unit)
    }

    override suspend fun setActive(profileId: String?): Result<Unit> {
        if (profileId != null && profileId !in profiles) {
            return Result.failure(ProviderProfileStoreError.NotFound(profileId))
        }
        activeIdFlow.value = profileId
        return Result.success(Unit)
    }

    override suspend fun deleteAll(): Result<Unit> {
        profiles.clear()
        activeIdFlow.value = null
        profilesFlow.value = emptyList()
        return Result.success(Unit)
    }

    private fun sortedSnapshot(): List<ProviderProfile> =
        profiles.values.sortedByDescending { it.updatedAt }
}
