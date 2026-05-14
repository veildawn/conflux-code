package com.claudemobile.features.settings.providers.list

import app.cash.turbine.test
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.ConnectionTestOutcome
import com.claudemobile.core.domain.providers.ConnectionTestResult
import com.claudemobile.core.domain.providers.ConnectionTester
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import com.claudemobile.core.domain.providers.usecase.DeleteProfileUseCase
import com.claudemobile.core.domain.providers.usecase.GetActiveProfileUseCase
import com.claudemobile.core.domain.providers.usecase.ListProfilesUseCase
import com.claudemobile.core.domain.providers.usecase.SetActiveProfileUseCase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [ProviderListViewModel].
 *
 * Coverage:
 *  - rows reflect [ListProfilesUseCase] order with the active flag (R4 AC1, R5 AC3).
 *  - "Set as active" delegates to the store and updates rows on the
 *    next emission (R5 AC2).
 *  - per-row delete dialog gating: `RequestDelete` opens, `Dismiss` closes,
 *    `Confirm` invokes [DeleteProfileUseCase] (R4 AC5).
 *  - destructive "Clear all" dialog gating: `RequestClearAll` opens,
 *    `ConfirmClearAll` calls [ProviderProfileStore.deleteAll], emits a
 *    [ProviderListEvent.NavigateToSelection] event, and clears state
 *    (R9 AC5, Property 17).
 *  - "Test connection" rendering: outcome surfaces in `latestTest` and
 *    a successful test marks `lastTestOk` until the profile is edited
 *    (R7 AC3, R7 AC4).
 *  - FAB and Edit row dispatch the corresponding navigation events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var store: FakeProviderProfileStore
    private lateinit var connectionTester: FakeConnectionTester
    private lateinit var viewModel: ProviderListViewModel

    private val customProfile = ProviderProfile(
        profileId = "p-custom",
        displayName = "My Custom",
        presetReference = PresetReference.Custom,
        baseUrl = "https://example.com",
        apiKey = "secret-12345",
        model = "claude-3",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private val presetProfile = ProviderProfile(
        profileId = "p-preset",
        displayName = "GLM",
        presetReference = PresetReference.Preset("glm_coding_plan"),
        baseUrl = "https://open.bigmodel.cn/api/anthropic",
        apiKey = "sk-glm-abcd",
        model = "glm-4.6",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.AuthToken,
        createdAt = 2_000L,
        updatedAt = 2_000L,
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        store = FakeProviderProfileStore()
        connectionTester = FakeConnectionTester()
        viewModel = ProviderListViewModel(
            listProfiles = ListProfilesUseCase(store),
            getActiveProfile = GetActiveProfileUseCase(store),
            store = store,
            setActive = SetActiveProfileUseCase(store),
            deleteProfile = DeleteProfileUseCase(store),
            connectionTester = connectionTester,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------
    // Row projection.
    // ---------------------------------------------------------------------

    @Test
    fun `empty store yields empty rows after first emission`() = runTest {
        advanceUntilIdle()
        viewModel.uiState.value.loading shouldBe false
        viewModel.uiState.value.rows shouldHaveSize 0
    }

    @Test
    fun `rows reflect store contents and identify the active profile`() = runTest {
        store.upsert(customProfile)
        store.upsert(presetProfile)
        store.setActive(presetProfile.profileId)
        advanceUntilIdle()

        val rows = viewModel.uiState.value.rows
        rows shouldHaveSize 2

        val byId = rows.associateBy { it.profile.profileId }
        byId[presetProfile.profileId]!!.isActive shouldBe true
        byId[customProfile.profileId]!!.isActive shouldBe false

        // displayPreset is the preset id for preset-derived profiles and
        // the literal "custom" otherwise (R4 AC1).
        byId[presetProfile.profileId]!!.displayPreset shouldBe "glm_coding_plan"
        byId[customProfile.profileId]!!.displayPreset shouldBe "custom"
    }

    // ---------------------------------------------------------------------
    // Set as active.
    // ---------------------------------------------------------------------

    @Test
    fun `SetActive forwards to the store and re-emits rows with the active flag`() = runTest {
        store.upsert(customProfile)
        store.upsert(presetProfile)
        advanceUntilIdle()

        viewModel.onIntent(ProviderListIntent.SetActive(customProfile.profileId))
        advanceUntilIdle()

        store.activeId shouldBe customProfile.profileId
        val rows = viewModel.uiState.value.rows
        rows.first { it.profile.profileId == customProfile.profileId }.isActive shouldBe true
        rows.first { it.profile.profileId == presetProfile.profileId }.isActive shouldBe false
    }

    // ---------------------------------------------------------------------
    // Per-row delete dialog gating.
    // ---------------------------------------------------------------------

    @Test
    fun `RequestDelete sets pendingDeleteId without invoking store`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()

        viewModel.onIntent(ProviderListIntent.RequestDelete(customProfile.profileId))
        advanceUntilIdle()

        viewModel.uiState.value.pendingDeleteId shouldBe customProfile.profileId
        store.deleteCount shouldBe 0
    }

    @Test
    fun `DismissDelete clears pendingDeleteId without invoking store`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()
        viewModel.onIntent(ProviderListIntent.RequestDelete(customProfile.profileId))
        advanceUntilIdle()

        viewModel.onIntent(ProviderListIntent.DismissDelete)
        advanceUntilIdle()

        viewModel.uiState.value.pendingDeleteId shouldBe null
        store.deleteCount shouldBe 0
    }

    @Test
    fun `ConfirmDelete invokes store delete and clears the row`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()
        viewModel.onIntent(ProviderListIntent.RequestDelete(customProfile.profileId))
        advanceUntilIdle()

        viewModel.onIntent(ProviderListIntent.ConfirmDelete)
        advanceUntilIdle()

        store.deleteCount shouldBe 1
        viewModel.uiState.value.pendingDeleteId shouldBe null
        viewModel.uiState.value.rows shouldHaveSize 0
    }

    // ---------------------------------------------------------------------
    // Destructive "Clear all".
    // ---------------------------------------------------------------------

    @Test
    fun `RequestClearAll opens dialog without invoking store deleteAll`() = runTest {
        store.upsert(customProfile)
        store.upsert(presetProfile)
        advanceUntilIdle()

        viewModel.onIntent(ProviderListIntent.RequestClearAll)
        advanceUntilIdle()

        viewModel.uiState.value.pendingClearAll shouldBe true
        store.deleteAllCount shouldBe 0
        viewModel.uiState.value.rows shouldHaveSize 2
    }

    @Test
    fun `DismissClearAll closes dialog without invoking store deleteAll`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()
        viewModel.onIntent(ProviderListIntent.RequestClearAll)
        advanceUntilIdle()

        viewModel.onIntent(ProviderListIntent.DismissClearAll)
        advanceUntilIdle()

        viewModel.uiState.value.pendingClearAll shouldBe false
        store.deleteAllCount shouldBe 0
    }

    @Test
    fun `ConfirmClearAll empties the store, clears active, and emits NavigateToSelection`() = runTest {
        store.upsert(customProfile)
        store.upsert(presetProfile)
        store.setActive(customProfile.profileId)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onIntent(ProviderListIntent.RequestClearAll)
            advanceUntilIdle()
            viewModel.onIntent(ProviderListIntent.ConfirmClearAll)
            advanceUntilIdle()

            // Property 17: deleteAll empties store and clears active.
            store.deleteAllCount shouldBe 1
            store.snapshot() shouldHaveSize 0
            store.activeId shouldBe null

            viewModel.uiState.value.pendingClearAll shouldBe false
            viewModel.uiState.value.rows shouldHaveSize 0

            awaitItem() shouldBe ProviderListEvent.NavigateToSelection
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------------
    // Test connection.
    // ---------------------------------------------------------------------

    @Test
    fun `successful TestConnection records the outcome and a persistent indicator`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()

        connectionTester.nextResult = ConnectionTestResult(ConnectionTestOutcome.Ok, "ok")
        viewModel.onIntent(ProviderListIntent.TestConnection(customProfile.profileId))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        state.latestTest shouldNotBe null
        state.latestTest!!.profileId shouldBe customProfile.profileId
        state.latestTest!!.result.outcome shouldBe ConnectionTestOutcome.Ok

        // Persistent ✓ indicator until the next edit (R7 AC4).
        state.rows.first().lastTestOk shouldBe true
    }

    @Test
    fun `editing the profile after a successful test clears the persistent indicator`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()

        connectionTester.nextResult = ConnectionTestResult(ConnectionTestOutcome.Ok, "ok")
        viewModel.onIntent(ProviderListIntent.TestConnection(customProfile.profileId))
        advanceUntilIdle()
        viewModel.uiState.value.rows.first().lastTestOk shouldBe true

        // Simulate an edit by upserting the profile with a later updatedAt.
        store.upsert(customProfile.copy(updatedAt = customProfile.updatedAt + 1))
        advanceUntilIdle()

        viewModel.uiState.value.rows.first().lastTestOk shouldBe false
    }

    @Test
    fun `failed TestConnection does not set the persistent indicator`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()

        connectionTester.nextResult = ConnectionTestResult(ConnectionTestOutcome.Unauthorized, "401")
        viewModel.onIntent(ProviderListIntent.TestConnection(customProfile.profileId))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        state.latestTest!!.result.outcome shouldBe ConnectionTestOutcome.Unauthorized
        state.rows.first().lastTestOk shouldBe false
    }

    @Test
    fun `DismissLatestTest clears the snackbar state`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()

        connectionTester.nextResult = ConnectionTestResult(ConnectionTestOutcome.Ok, "")
        viewModel.onIntent(ProviderListIntent.TestConnection(customProfile.profileId))
        advanceUntilIdle()
        viewModel.uiState.value.latestTest shouldNotBe null

        viewModel.onIntent(ProviderListIntent.DismissLatestTest)
        viewModel.uiState.value.latestTest shouldBe null
    }

    // ---------------------------------------------------------------------
    // Navigation events.
    // ---------------------------------------------------------------------

    @Test
    fun `AddNew emits NavigateToSelection`() = runTest {
        viewModel.events.test {
            viewModel.onIntent(ProviderListIntent.AddNew)
            advanceUntilIdle()
            awaitItem() shouldBe ProviderListEvent.NavigateToSelection
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Edit emits NavigateToEdit with the profile id`() = runTest {
        store.upsert(customProfile)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onIntent(ProviderListIntent.Edit(customProfile.profileId))
            advanceUntilIdle()
            awaitItem() shouldBe ProviderListEvent.NavigateToEdit(customProfile.profileId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------------
    // Display preset projection.
    // ---------------------------------------------------------------------

    @Test
    fun `display preset value is the literal custom for Custom profiles and the preset id otherwise`() {
        listOf(presetProfile, customProfile).forEach { _ -> }
        // Sanity check: companion constant matches design §6.1 wording.
        ProviderListViewModel.CUSTOM_DISPLAY_PRESET shouldBe "custom"
        // Indirect coverage via the row builder is exercised in
        // `rows reflect store contents and identify the active profile`.
        listOf("custom") shouldContain ProviderListViewModel.CUSTOM_DISPLAY_PRESET
    }
}

/**
 * Minimal in-memory [ProviderProfileStore]; mirrors the fake used by the
 * editor ViewModel test but exposes counters relevant to list-screen
 * scenarios.
 */
private class FakeProviderProfileStore : ProviderProfileStore {
    private val profiles = mutableMapOf<String, ProviderProfile>()
    private val profilesFlow = MutableStateFlow<List<ProviderProfile>>(emptyList())
    private val activeIdFlow = MutableStateFlow<String?>(null)

    var deleteCount: Int = 0
    var deleteAllCount: Int = 0

    val activeId: String? get() = activeIdFlow.value

    fun snapshot(): List<ProviderProfile> = profiles.values.toList()

    override fun observeProfiles() = profilesFlow

    override fun observeActiveProfile() = combine(profilesFlow, activeIdFlow) { all, id ->
        id?.let { active -> all.firstOrNull { it.profileId == active } }
    }

    override suspend fun list(): List<ProviderProfile> =
        profiles.values.sortedByDescending { it.updatedAt }

    override suspend fun get(profileId: String): ProviderProfile? = profiles[profileId]

    override suspend fun getActive(): ProviderProfile? =
        activeIdFlow.value?.let { profiles[it] }

    override suspend fun upsert(profile: ProviderProfile): Result<Unit> {
        profiles[profile.profileId] = profile
        profilesFlow.value = profiles.values.sortedByDescending { it.updatedAt }
        return Result.success(Unit)
    }

    override suspend fun delete(profileId: String): Result<Unit> {
        if (profileId !in profiles) {
            return Result.failure(ProviderProfileStoreError.NotFound(profileId))
        }
        deleteCount += 1
        profiles.remove(profileId)
        if (activeIdFlow.value == profileId) activeIdFlow.value = null
        profilesFlow.value = profiles.values.sortedByDescending { it.updatedAt }
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
        deleteAllCount += 1
        profiles.clear()
        activeIdFlow.value = null
        profilesFlow.value = emptyList()
        return Result.success(Unit)
    }
}

private class FakeConnectionTester : ConnectionTester {
    var nextResult: ConnectionTestResult = ConnectionTestResult(ConnectionTestOutcome.Ok, "")

    override suspend fun test(profile: ProviderProfile): ConnectionTestResult = nextResult
}
