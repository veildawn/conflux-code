package com.claudemobile.feature.settings

import app.cash.turbine.test
import com.claudemobile.core.domain.bridge.BootstrapManager
import com.claudemobile.core.domain.bridge.BootstrapState
import com.claudemobile.core.domain.bridge.HealthCheckResult
import com.claudemobile.core.domain.model.AppSettings
import com.claudemobile.core.domain.model.ThemeMode
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.usecase.GetActiveProfileUseCase
import com.claudemobile.core.domain.repository.CredentialStore
import com.claudemobile.core.domain.repository.DiagnosticsEntry
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import com.claudemobile.core.domain.repository.SettingsStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsStore: SettingsStore
    private lateinit var credentialStore: CredentialStore
    private lateinit var bootstrapManager: BootstrapManager
    private lateinit var diagnosticsRepository: DiagnosticsRepository
    private lateinit var getActiveProfile: GetActiveProfileUseCase
    private lateinit var viewModel: SettingsViewModel

    private val defaultSettings = AppSettings()
    private val settingsFlow = MutableStateFlow(defaultSettings)
    private val activeProfileFlow = MutableStateFlow<ProviderProfile?>(null)

    private fun makeViewModel() = SettingsViewModel(
        settingsStore,
        credentialStore,
        bootstrapManager,
        diagnosticsRepository,
        getActiveProfile,
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        settingsStore = mockk(relaxed = true) {
            every { settings } returns settingsFlow
        }
        credentialStore = mockk(relaxed = true) {
            coEvery { getApiKey() } returns null
        }
        bootstrapManager = mockk(relaxed = true) {
            every { bootstrapState } returns MutableStateFlow(BootstrapState.NotStarted)
        }
        diagnosticsRepository = mockk(relaxed = true) {
            coEvery { getRecentLogs(any()) } returns emptyList()
        }
        getActiveProfile = mockk {
            every { this@mockk.invoke() } returns activeProfileFlow
        }

        viewModel = makeViewModel()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Test
    fun `initial state loads settings from store`() = runTest {
        advanceUntilIdle()

        viewModel.uiState.value.settings shouldBe defaultSettings
    }

    @Test
    fun `initial activeProfile is null when no profile is active`() = runTest {
        advanceUntilIdle()

        viewModel.activeProfile.value shouldBe null
    }

    @Test
    fun `activeProfile reflects the active profile from use case`() = runTest {
        val profile = buildProfile("p1", "GLM Coding", "glm-4.6")

        // Subscribe to the flow first so WhileSubscribed activates it
        viewModel.activeProfile.test {
            awaitItem() // initial null

            activeProfileFlow.value = profile
            val item: ProviderProfile? = awaitItem()
            item shouldBe profile
        }
    }

    @Test
    fun `activeProfile updates when active profile changes`() = runTest {
        val profile1 = buildProfile("p1", "GLM Coding", "glm-4.6")
        val profile2 = buildProfile("p2", "Kimi Code", "kimi-k2-turbo-preview")

        viewModel.activeProfile.test {
            // initial null
            val first: ProviderProfile? = awaitItem()
            first shouldBe null

            activeProfileFlow.value = profile1
            val second: ProviderProfile? = awaitItem()
            second shouldBe profile1

            activeProfileFlow.value = profile2
            val third: ProviderProfile? = awaitItem()
            third shouldBe profile2

            activeProfileFlow.value = null
            val fourth: ProviderProfile? = awaitItem()
            fourth shouldBe null
        }
    }

    // -----------------------------------------------------------------------
    // Settings actions
    // -----------------------------------------------------------------------

    @Test
    fun `SetSystemPrompt action updates settings store`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetSystemPrompt("You are helpful"))
        advanceUntilIdle()

        coVerify { settingsStore.setSystemPrompt("You are helpful") }
    }

    @Test
    fun `SetThemeMode action updates settings store`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetThemeMode(ThemeMode.DARK))
        advanceUntilIdle()

        coVerify { settingsStore.setThemeMode(ThemeMode.DARK) }
    }

    @Test
    fun `SetFontScale with valid value updates settings store`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetFontScale(1.5f))
        advanceUntilIdle()

        coVerify { settingsStore.setFontScale(1.5f) }
    }

    @Test
    fun `SetFontScale with value below minimum is rejected`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetFontScale(0.1f))
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsStore.setFontScale(any()) }
    }

    @Test
    fun `SetFontScale with value above maximum is rejected`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetFontScale(5.0f))
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsStore.setFontScale(any()) }
    }

    @Test
    fun `SetStreamingRenderRate with valid value updates settings store`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetStreamingRenderRate(100L))
        advanceUntilIdle()

        coVerify { settingsStore.setStreamingRenderRate(100L) }
    }

    @Test
    fun `SetStreamingRenderRate with value below minimum is rejected`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetStreamingRenderRate(5L))
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsStore.setStreamingRenderRate(any()) }
    }

    @Test
    fun `SetStreamingRenderRate with value above maximum is rejected`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetStreamingRenderRate(2000L))
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsStore.setStreamingRenderRate(any()) }
    }

    @Test
    fun `SetDefaultWorkspacePath action updates settings store`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetDefaultWorkspacePath("/data/projects"))
        advanceUntilIdle()

        coVerify { settingsStore.setDefaultWorkspacePath("/data/projects") }
    }

    @Test
    fun `SetAutoStartForegroundService action updates settings store`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetAutoStartForegroundService(false))
        advanceUntilIdle()

        coVerify { settingsStore.setAutoStartForegroundService(false) }
    }

    // -----------------------------------------------------------------------
    // Health check
    // -----------------------------------------------------------------------

    @Test
    fun `RunHealthCheck action updates state with result`() = runTest {
        val healthResult = HealthCheckResult(
            prefixInstalled = true,
            prefixVersion = "1.0.0",
            rootfsInstalled = true,
            rootfsDistro = "Ubuntu 22.04",
            nodeVersion = "20.11.0",
            claudeCliVersion = "1.0.0",
            storageUsedBytes = 500_000_000L,
            storageAvailableBytes = 2_000_000_000L
        )
        coEvery { bootstrapManager.healthCheck() } returns healthResult
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.RunHealthCheck)
        advanceUntilIdle()

        viewModel.uiState.value.healthCheckResult shouldBe healthResult
        viewModel.uiState.value.isCheckingHealth shouldBe false
    }

    @Test
    fun `RunHealthCheck sets isCheckingHealth during execution`() = runTest {
        coEvery { bootstrapManager.healthCheck() } coAnswers {
            HealthCheckResult(
                prefixInstalled = false,
                prefixVersion = null,
                rootfsInstalled = false,
                rootfsDistro = null,
                nodeVersion = null,
                claudeCliVersion = null,
                storageUsedBytes = 0L,
                storageAvailableBytes = 1_000_000_000L
            )
        }
        advanceUntilIdle()

        viewModel.uiState.test {
            val initial = awaitItem()
            initial.isCheckingHealth shouldBe false

            viewModel.onAction(SettingsAction.RunHealthCheck)

            val checking = awaitItem()
            checking.isCheckingHealth shouldBe true

            val done = awaitItem()
            done.isCheckingHealth shouldBe false
            done.healthCheckResult shouldNotBe null
        }
    }

    @Test
    fun `RunHealthCheck handles exception gracefully`() = runTest {
        coEvery { bootstrapManager.healthCheck() } throws RuntimeException("Network error")
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.RunHealthCheck)
        advanceUntilIdle()

        viewModel.uiState.value.isCheckingHealth shouldBe false
        viewModel.uiState.value.healthCheckResult shouldBe null
    }

    // -----------------------------------------------------------------------
    // Settings flow propagation
    // -----------------------------------------------------------------------

    @Test
    fun `settings flow updates propagate to UI state`() = runTest {
        advanceUntilIdle()

        val updatedSettings = AppSettings(
            modelId = "claude-opus-4-20250514",
            themeMode = ThemeMode.DARK,
            fontScale = 1.5f
        )
        settingsFlow.value = updatedSettings
        advanceUntilIdle()

        viewModel.uiState.value.settings shouldBe updatedSettings
    }

    // -----------------------------------------------------------------------
    // Font scale / render rate boundary tests
    // -----------------------------------------------------------------------

    @Test
    fun `SetFontScale at boundary minimum is accepted`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetFontScale(0.5f))
        advanceUntilIdle()

        coVerify { settingsStore.setFontScale(0.5f) }
    }

    @Test
    fun `SetFontScale at boundary maximum is accepted`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetFontScale(3.0f))
        advanceUntilIdle()

        coVerify { settingsStore.setFontScale(3.0f) }
    }

    @Test
    fun `SetStreamingRenderRate at boundary minimum is accepted`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetStreamingRenderRate(16L))
        advanceUntilIdle()

        coVerify { settingsStore.setStreamingRenderRate(16L) }
    }

    @Test
    fun `SetStreamingRenderRate at boundary maximum is accepted`() = runTest {
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.SetStreamingRenderRate(1000L))
        advanceUntilIdle()

        coVerify { settingsStore.setStreamingRenderRate(1000L) }
    }

    // -----------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------

    @Test
    fun `LoadDiagnostics action loads entries from repository`() = runTest {
        val entries = listOf(
            DiagnosticsEntry(
                id = "1",
                sessionId = null,
                eventType = "bootstrap",
                timestamp = 1000L,
                message = "Bootstrap started",
                details = null
            ),
            DiagnosticsEntry(
                id = "2",
                sessionId = "session-1",
                eventType = "bridge_lifecycle",
                timestamp = 2000L,
                message = "Process spawned",
                details = "PID: 1234"
            )
        )
        coEvery { diagnosticsRepository.getRecentLogs(any()) } returns entries
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.LoadDiagnostics)
        advanceUntilIdle()

        viewModel.uiState.value.diagnosticsEntries shouldBe entries
        viewModel.uiState.value.isLoadingDiagnostics shouldBe false
    }

    @Test
    fun `ExportDiagnostics produces redacted output`() = runTest {
        val apiKey = "sk-ant-secret-key-12345"
        val entries = listOf(
            DiagnosticsEntry(
                id = "1",
                sessionId = null,
                eventType = "bootstrap",
                timestamp = 1000L,
                message = "API key set: $apiKey",
                details = null
            )
        )
        coEvery { diagnosticsRepository.getRecentLogs(any()) } returns entries
        coEvery { credentialStore.getApiKey() } returns apiKey
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.ExportDiagnostics)
        advanceUntilIdle()

        val export = viewModel.uiState.value.redactedDiagnosticsExport
        export shouldNotBe null
        export!!.contains(apiKey) shouldBe false
        export.contains("[REDACTED]") shouldBe true
    }

    @Test
    fun `ExportDiagnostics without API key does not redact`() = runTest {
        val entries = listOf(
            DiagnosticsEntry(
                id = "1",
                sessionId = null,
                eventType = "bootstrap",
                timestamp = 1000L,
                message = "Bootstrap started",
                details = null
            )
        )
        coEvery { diagnosticsRepository.getRecentLogs(any()) } returns entries
        coEvery { credentialStore.getApiKey() } returns null
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.ExportDiagnostics)
        advanceUntilIdle()

        val export = viewModel.uiState.value.redactedDiagnosticsExport
        export shouldNotBe null
        export!!.contains("Bootstrap started") shouldBe true
    }

    @Test
    fun `ClearDiagnosticsExport clears the export text`() = runTest {
        coEvery { diagnosticsRepository.getRecentLogs(any()) } returns emptyList()
        coEvery { credentialStore.getApiKey() } returns null
        advanceUntilIdle()

        viewModel.onAction(SettingsAction.ExportDiagnostics)
        advanceUntilIdle()
        viewModel.uiState.value.redactedDiagnosticsExport shouldNotBe null

        viewModel.onAction(SettingsAction.ClearDiagnosticsExport)
        viewModel.uiState.value.redactedDiagnosticsExport shouldBe null
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildProfile(
        id: String,
        displayName: String,
        model: String,
    ) = ProviderProfile(
        profileId = id,
        displayName = displayName,
        presetReference = PresetReference.Custom,
        baseUrl = "https://api.example.com",
        apiKey = "sk-test-key",
        model = model,
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        createdAt = 1_000_000L,
        updatedAt = 1_000_000L,
    )
}
