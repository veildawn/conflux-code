package com.claudemobile.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.claudemobile.core.domain.model.AppSettings
import com.claudemobile.core.domain.model.ThemeMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreImplTest {

    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsStore: SettingsStoreImpl

    @BeforeEach
    fun setup() {
        testScope = TestScope(testDispatcher + Job())
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { File(tempDir, "test_settings.preferences_pb") }
        )
        settingsStore = SettingsStoreImpl(dataStore)
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Nested
    inner class DefaultValues {

        @Test
        fun `settings flow emits defaults when no preferences are stored`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                val settings = awaitItem()
                settings shouldBe AppSettings()
            }
        }
    }

    @Nested
    inner class SetModelId {

        @Test
        fun `setModelId writes to store but settings flow no longer emits modelId changes`() = runTest(testDispatcher) {
            // setModelId is deprecated (ai-provider-presets R11 AC1); the settings flow
            // no longer reads KEY_MODEL_ID. Writing via setModelId still persists the
            // value so LegacyKeyMigrator can read it via getModel(), but it does NOT
            // cause a new emission on the settings flow.
            @Suppress("DEPRECATION")
            settingsStore.setModelId("claude-opus-4-20250514")

            // getModel() should return the written value (migrator read path).
            val stored = settingsStore.getModel()
            stored shouldBe "claude-opus-4-20250514"

            // settings.modelId always returns the default; it is no longer driven by the store.
            settingsStore.settings.test {
                val settings = awaitItem()
                settings.modelId shouldBe AppSettings().modelId
            }
        }
    }

    @Nested
    inner class SetThemeMode {

        @Test
        fun `setThemeMode persists LIGHT mode`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setThemeMode(ThemeMode.LIGHT)
                val updated = awaitItem()
                updated.themeMode shouldBe ThemeMode.LIGHT
            }
        }

        @Test
        fun `setThemeMode persists DARK mode`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setThemeMode(ThemeMode.DARK)
                val updated = awaitItem()
                updated.themeMode shouldBe ThemeMode.DARK
            }
        }

        @Test
        fun `setThemeMode persists SYSTEM mode`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setThemeMode(ThemeMode.DARK)
                awaitItem()

                settingsStore.setThemeMode(ThemeMode.SYSTEM)
                val updated = awaitItem()
                updated.themeMode shouldBe ThemeMode.SYSTEM
            }
        }
    }

    @Nested
    inner class SetFontScale {

        @Test
        fun `setFontScale persists valid value at minimum`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setFontScale(0.5f)
                val updated = awaitItem()
                updated.fontScale shouldBe 0.5f
            }
        }

        @Test
        fun `setFontScale persists valid value at maximum`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setFontScale(3.0f)
                val updated = awaitItem()
                updated.fontScale shouldBe 3.0f
            }
        }

        @Test
        fun `setFontScale persists valid value in middle of range`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setFontScale(1.5f)
                val updated = awaitItem()
                updated.fontScale shouldBe 1.5f
            }
        }

        @Test
        fun `setFontScale rejects value below minimum`() = runTest(testDispatcher) {
            shouldThrow<IllegalArgumentException> {
                settingsStore.setFontScale(0.4f)
            }
        }

        @Test
        fun `setFontScale rejects value above maximum`() = runTest(testDispatcher) {
            shouldThrow<IllegalArgumentException> {
                settingsStore.setFontScale(3.1f)
            }
        }
    }

    @Nested
    inner class SetStreamingRenderRate {

        @Test
        fun `setStreamingRenderRate persists valid value at minimum`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setStreamingRenderRate(16L)
                val updated = awaitItem()
                updated.streamingRenderRate shouldBe 16L
            }
        }

        @Test
        fun `setStreamingRenderRate persists valid value at maximum`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setStreamingRenderRate(1000L)
                val updated = awaitItem()
                updated.streamingRenderRate shouldBe 1000L
            }
        }

        @Test
        fun `setStreamingRenderRate persists valid value in middle of range`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setStreamingRenderRate(100L)
                val updated = awaitItem()
                updated.streamingRenderRate shouldBe 100L
            }
        }

        @Test
        fun `setStreamingRenderRate rejects value below minimum`() = runTest(testDispatcher) {
            shouldThrow<IllegalArgumentException> {
                settingsStore.setStreamingRenderRate(15L)
            }
        }

        @Test
        fun `setStreamingRenderRate rejects value above maximum`() = runTest(testDispatcher) {
            shouldThrow<IllegalArgumentException> {
                settingsStore.setStreamingRenderRate(1001L)
            }
        }
    }

    @Nested
    inner class SetDefaultWorkspacePath {

        @Test
        fun `setDefaultWorkspacePath persists and emits updated value`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setDefaultWorkspacePath("/storage/emulated/0/projects")
                val updated = awaitItem()
                updated.defaultWorkspacePath shouldBe "/storage/emulated/0/projects"
            }
        }
    }

    @Nested
    inner class SetAutoStartForegroundService {

        @Test
        fun `setAutoStartForegroundService persists false`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setAutoStartForegroundService(false)
                val updated = awaitItem()
                updated.autoStartForegroundService shouldBe false
            }
        }

        @Test
        fun `setAutoStartForegroundService persists true`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                settingsStore.setAutoStartForegroundService(false)
                awaitItem()

                settingsStore.setAutoStartForegroundService(true)
                val updated = awaitItem()
                updated.autoStartForegroundService shouldBe true
            }
        }
    }

    @Nested
    inner class InvalidReadFallback {

        @Test
        fun `invalid font scale on read falls back to default`() = runTest(testDispatcher) {
            // Write an out-of-range value directly to datastore
            dataStore.edit { prefs ->
                prefs[floatPreferencesKey("font_scale")] = 10.0f
            }

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.fontScale shouldBe AppSettings().fontScale
            }
        }

        @Test
        fun `invalid streaming render rate on read falls back to default`() = runTest(testDispatcher) {
            dataStore.edit { prefs ->
                prefs[longPreferencesKey("streaming_render_rate_ms")] = 5L
            }

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.streamingRenderRate shouldBe AppSettings().streamingRenderRate
            }
        }

        @Test
        fun `invalid theme mode string on read falls back to default`() = runTest(testDispatcher) {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("theme_mode")] = "INVALID_MODE"
            }

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.themeMode shouldBe AppSettings().themeMode
            }
        }
    }

    @Nested
    inner class MultipleUpdates {

        @Test
        fun `multiple preference changes emit correctly`() = runTest(testDispatcher) {
            settingsStore.settings.test {
                awaitItem() // defaults

                // setModelId is deprecated; it still writes to the DataStore (so
                // LegacyKeyMigrator can read it via getModel()), which triggers a
                // settings emission. The emitted value has modelId = default because
                // the settings flow no longer reads KEY_MODEL_ID (R11 AC1).
                @Suppress("DEPRECATION")
                settingsStore.setModelId("claude-opus-4-20250514")
                val afterModelId = awaitItem()
                // modelId is always the default in the settings flow (R11 AC1).
                afterModelId.modelId shouldBe AppSettings().modelId

                settingsStore.setFontScale(2.0f)
                val updated = awaitItem()
                updated.modelId shouldBe AppSettings().modelId
                updated.fontScale shouldBe 2.0f
            }
        }
    }
}
