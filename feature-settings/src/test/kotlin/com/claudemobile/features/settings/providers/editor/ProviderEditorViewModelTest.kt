package com.claudemobile.features.settings.providers.editor

import app.cash.turbine.test
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.ConnectionTestOutcome
import com.claudemobile.core.domain.providers.ConnectionTestResult
import com.claudemobile.core.domain.providers.ConnectionTester
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderPreset
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import com.claudemobile.core.domain.providers.ProviderRegistry
import com.claudemobile.core.domain.providers.ValidationError
import com.claudemobile.core.domain.providers.ValidationField
import com.claudemobile.core.domain.providers.usecase.CreateCustomUseCase
import com.claudemobile.core.domain.providers.usecase.CreateFromPresetUseCase
import com.claudemobile.core.domain.providers.usecase.SetActiveProfileUseCase
import com.claudemobile.core.domain.providers.usecase.UpdateProfileUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.time.Instant

/**
 * Unit tests for [ProviderEditorViewModel].
 *
 * Coverage:
 *  - validation blocks save (R2 AC4, R3 AC4–AC6, R3 AC3)
 *  - successful save emits [ProviderEditorUiState.Saved] and clears the
 *    `apiKey` field (R9 AC3, R2 AC5)
 *  - [ProviderProfileStoreError.BaseUrlLocked] surfaces as an inline
 *    error on the `baseUrl` field (R4 AC4)
 *  - Connection_Test outcome renders into the editing state (R7 AC3)
 *
 * Validates: Requirements 2.4, 2.5, 3.2, 3.3, 3.4, 3.5, 3.6, 4.4, 7.1,
 * 7.3, 7.4, 9.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderEditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var registry: ProviderRegistry
    private lateinit var store: FakeProviderProfileStore
    private lateinit var connectionTester: FakeConnectionTester
    private lateinit var viewModel: ProviderEditorViewModel

    private val customPreset = ProviderPreset(
        presetId = "test_preset",
        displayNameResId = 0,
        baseUrl = "https://preset.example.com/api",
        defaultModel = "preset-model",
        defaultSmallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.AuthToken,
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        registry = object : ProviderRegistry {
            override fun allPresets() = listOf(customPreset)
            override fun findById(presetId: String) =
                if (presetId == customPreset.presetId) customPreset else null
        }

        store = FakeProviderProfileStore()
        connectionTester = FakeConnectionTester()

        val timeProvider = object : TimeProvider {
            override fun now(): Instant = Instant.ofEpochMilli(1_000L)
        }
        val uuids = object : UuidGenerator {
            private var n = 0
            override fun generate(): String {
                n += 1
                return "id-$n"
            }
        }

        viewModel = ProviderEditorViewModel(
            registry = registry,
            store = store,
            createFromPreset = CreateFromPresetUseCase(store, uuids, timeProvider),
            createCustom = CreateCustomUseCase(store, uuids, timeProvider),
            updateProfile = UpdateProfileUseCase(store, timeProvider),
            setActiveProfile = SetActiveProfileUseCase(store),
            connectionTester = connectionTester,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------
    // Initialize.
    // -----------------------------------------------------------------

    @Test
    fun `initialize for unknown preset id surfaces Error state`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Preset("no_such_preset")))
        advanceUntilIdle()

        viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Error>()
    }

    @Test
    fun `initialize Custom yields editing state with empty form and editable baseUrl`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.preset shouldBe null
        state.form.baseUrlReadOnly shouldBe false
        state.form.submitEnabled shouldBe false
        state.form.authHeaderStyle shouldBe AuthHeaderStyle.ApiKey
    }

    @Test
    fun `initialize Preset locks baseUrl and pre-populates from preset`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Preset(customPreset.presetId)))
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.preset shouldBe customPreset
        state.form.baseUrlReadOnly shouldBe true
        state.form.baseUrl.value shouldBe customPreset.baseUrl
        state.form.baseUrl.valid shouldBe true
        state.form.model.value shouldBe customPreset.defaultModel
        state.form.model.valid shouldBe true
        state.form.authHeaderStyle shouldBe AuthHeaderStyle.AuthToken
    }

    // -----------------------------------------------------------------
    // Validation blocks save.
    // -----------------------------------------------------------------

    @Test
    fun `Save with invalid form does not invoke store`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        // Form is empty → submitEnabled is false.
        viewModel.onIntent(ProviderEditorIntent.Save)
        advanceUntilIdle()

        store.upsertCount shouldBe 0
        viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
    }

    @Test
    fun `field-level validation reports per-field errors independently`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        // Fill displayName + apiKey + model with valid values, leave baseUrl invalid.
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "My Provider"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "secret"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "claude-3"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, "not-a-url"))
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.form.displayName.valid shouldBe true
        state.form.apiKey.valid shouldBe true
        state.form.model.valid shouldBe true
        state.form.baseUrl.valid shouldBe false
        state.form.baseUrl.error shouldBe ValidationError.BaseUrlInvalid
        state.form.submitEnabled shouldBe false
    }

    @Test
    fun `entering valid baseUrl flips submitEnabled to true`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "X"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, "https://example.com"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "key"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "m"))
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.form.submitEnabled shouldBe true
    }

    // -----------------------------------------------------------------
    // Successful save clears apiKey field.
    // -----------------------------------------------------------------

    @Test
    fun `successful Save in Custom mode emits Saved and clears apiKey field`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "My Custom"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, "https://example.com"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "supersecret"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "the-model"))
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.Save)
        advanceUntilIdle()

        val saved = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Saved>()
        saved.profileId shouldBe "id-1"
        // The form snapshot exposed on Saved has apiKey reset.
        saved.form.apiKey.value shouldBe ""
        store.upsertCount shouldBe 1
        store.lastUpserted?.apiKey shouldBe "supersecret"
    }

    @Test
    fun `successful Save in Preset mode persists with preset baseUrl and authStyle`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Preset(customPreset.presetId)))
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "GLM"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "presetkey"))
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.Save)
        advanceUntilIdle()

        val saved = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Saved>()
        saved.form.apiKey.value shouldBe ""
        val persisted = store.lastUpserted!!
        persisted.baseUrl shouldBe customPreset.baseUrl
        persisted.authHeaderStyle shouldBe customPreset.authHeaderStyle
        persisted.presetReference shouldBe PresetReference.Preset(customPreset.presetId)
        persisted.apiKey shouldBe "presetkey"
    }

    // -----------------------------------------------------------------
    // BaseUrlLocked error mapping.
    // -----------------------------------------------------------------

    @Test
    fun `BaseUrlLocked from store surfaces as inline error on baseUrl field`() = runTest {
        // Seed an existing preset-derived profile so Edit mode can find it.
        val existing = ProviderProfile(
            profileId = "p-1",
            displayName = "Existing",
            presetReference = PresetReference.Preset(customPreset.presetId),
            baseUrl = customPreset.baseUrl,
            apiKey = "old-key",
            model = customPreset.defaultModel,
            smallFastModel = null,
            authHeaderStyle = customPreset.authHeaderStyle,
            createdAt = 0L,
            updatedAt = 0L,
        )
        store.upsert(existing)
        store.upsertCount = 0

        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Edit("p-1")))
        advanceUntilIdle()

        // Force the next upsert to fail with BaseUrlLocked.
        store.nextUpsertError = ProviderProfileStoreError.BaseUrlLocked

        // Trigger save (form is already valid because we loaded from store).
        viewModel.onIntent(ProviderEditorIntent.Save)
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.form.baseUrl.valid shouldBe false
        state.form.baseUrl.error shouldBe ValidationError.BaseUrlPresetLocked
        state.isSaving shouldBe false
    }

    // -----------------------------------------------------------------
    // Connection_Test rendering.
    // -----------------------------------------------------------------

    @Test
    fun `TestConnection updates editing state with the result`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        // Make form valid so the test button is enabled.
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "X"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, "https://example.com"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "k"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "m"))
        advanceUntilIdle()

        connectionTester.nextResult = ConnectionTestResult(
            outcome = ConnectionTestOutcome.Ok,
            userReason = "All good",
        )
        viewModel.onIntent(ProviderEditorIntent.TestConnection)
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.testResult shouldNotBe null
        state.testResult!!.outcome shouldBe ConnectionTestOutcome.Ok
        state.testResult!!.userReason shouldBe "All good"
        state.isTesting shouldBe false
    }

    @Test
    fun `TestConnection unauthorized result renders without clearing the form`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "X"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, "https://example.com"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "wrong-key"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "m"))
        advanceUntilIdle()

        connectionTester.nextResult = ConnectionTestResult(
            outcome = ConnectionTestOutcome.Unauthorized,
            userReason = "401",
        )
        viewModel.onIntent(ProviderEditorIntent.TestConnection)
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.testResult?.outcome shouldBe ConnectionTestOutcome.Unauthorized
        // Form values intact.
        state.form.apiKey.value shouldBe "wrong-key"
        state.form.baseUrl.value shouldBe "https://example.com"
    }

    @Test
    fun `editing a field after a TestConnection clears the previous result`() = runTest {
        viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "X"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, "https://example.com"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "k"))
        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "m"))
        advanceUntilIdle()

        connectionTester.nextResult = ConnectionTestResult(ConnectionTestOutcome.Ok, "ok")
        viewModel.onIntent(ProviderEditorIntent.TestConnection)
        advanceUntilIdle()

        viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "m2"))
        advanceUntilIdle()

        val state = viewModel.uiState.value.shouldBeInstanceOf<ProviderEditorUiState.Editing>()
        state.testResult shouldBe null
    }

    @Test
    fun `state transitions from Loading through Editing to Saved`() = runTest {
        viewModel.uiState.test {
            // initial Loading
            awaitItem().shouldBeInstanceOf<ProviderEditorUiState.Loading>()

            viewModel.onIntent(ProviderEditorIntent.Initialize(EditorMode.Custom))
            advanceUntilIdle()
            // Editing emerges (single state assignment).
            awaitItem().shouldBeInstanceOf<ProviderEditorUiState.Editing>()

            viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.DisplayName, "n"))
            advanceUntilIdle()
            awaitItem().shouldBeInstanceOf<ProviderEditorUiState.Editing>()

            viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.BaseUrl, "https://x.example.com"))
            advanceUntilIdle()
            awaitItem().shouldBeInstanceOf<ProviderEditorUiState.Editing>()

            viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.ApiKey, "k"))
            advanceUntilIdle()
            awaitItem().shouldBeInstanceOf<ProviderEditorUiState.Editing>()

            viewModel.onIntent(ProviderEditorIntent.UpdateField(ValidationField.Model, "m"))
            advanceUntilIdle()
            awaitItem().shouldBeInstanceOf<ProviderEditorUiState.Editing>()

            viewModel.onIntent(ProviderEditorIntent.Save)
            advanceUntilIdle()
            // Edit (isSaving = true) then Saved.
            val maybeSaving = awaitItem()
            if (maybeSaving is ProviderEditorUiState.Editing) {
                maybeSaving.isSaving shouldBe true
                awaitItem().shouldBeInstanceOf<ProviderEditorUiState.Saved>()
            } else {
                maybeSaving.shouldBeInstanceOf<ProviderEditorUiState.Saved>()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}

/**
 * Minimal in-memory [ProviderProfileStore] supporting the use-case
 * surface needed by [ProviderEditorViewModel] tests. Exposes a few
 * test-control hooks (`upsertCount`, `lastUpserted`, `nextUpsertError`)
 * so tests can assert and inject failures.
 */
private class FakeProviderProfileStore : ProviderProfileStore {

    private val profiles = mutableMapOf<String, ProviderProfile>()
    private val profilesFlow = MutableStateFlow<List<ProviderProfile>>(emptyList())
    private val activeIdFlow = MutableStateFlow<String?>(null)

    var upsertCount: Int = 0
    var lastUpserted: ProviderProfile? = null
    var nextUpsertError: Throwable? = null

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
        nextUpsertError?.let { err ->
            nextUpsertError = null
            return Result.failure(err)
        }
        upsertCount += 1
        lastUpserted = profile
        profiles[profile.profileId] = profile
        profilesFlow.value = profiles.values.sortedByDescending { it.updatedAt }
        return Result.success(Unit)
    }

    override suspend fun delete(profileId: String): Result<Unit> {
        if (profileId !in profiles) {
            return Result.failure(ProviderProfileStoreError.NotFound(profileId))
        }
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
        profiles.clear()
        activeIdFlow.value = null
        profilesFlow.value = emptyList()
        return Result.success(Unit)
    }
}

/**
 * Minimal [ConnectionTester] returning a controllable result. Records
 * whether `test` was ever called for tests that need that signal.
 */
private class FakeConnectionTester : ConnectionTester {
    var nextResult: ConnectionTestResult = ConnectionTestResult(
        outcome = ConnectionTestOutcome.Ok,
        userReason = "default",
    )
    var callCount: Int = 0
    var lastProfile: ProviderProfile? = null

    override suspend fun test(profile: ProviderProfile): ConnectionTestResult {
        callCount += 1
        lastProfile = profile
        return nextResult
    }
}
