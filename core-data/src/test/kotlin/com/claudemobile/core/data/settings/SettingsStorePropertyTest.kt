package com.claudemobile.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.cash.turbine.test
import com.claudemobile.core.domain.model.AppSettings
import com.claudemobile.core.domain.model.ThemeMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based tests for the Settings Store.
 *
 * Tags: Feature: android-claude-termux-client, Property 7: Settings Write-Read Round-Trip,
 *        Property 8: Invalid Preference Fallback
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStorePropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 7: Settings Write-Read Round-Trip"),
        io.kotest.core.Tag("Property 8: Invalid Preference Fallback")
    )

    val tempDirBase = File(System.getProperty("java.io.tmpdir"), "settings_prop_test_${System.nanoTime()}")
    val fileCounter = AtomicInteger(0)

    beforeSpec {
        tempDirBase.mkdirs()
    }

    afterSpec {
        tempDirBase.deleteRecursively()
    }

    /**
     * Creates a fresh DataStore and SettingsStoreImpl for each property test iteration.
     */
    fun createSettingsStore(): Pair<DataStore<Preferences>, SettingsStoreImpl> {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = {
                File(tempDirBase, "test_${fileCounter.incrementAndGet()}.preferences_pb")
            }
        )
        return dataStore to SettingsStoreImpl(dataStore)
    }

    /**
     * Property 7: Settings Write-Read Round-Trip
     *
     * **Validates: Requirements 9.6**
     *
     * For any preference key and valid value within the declared range,
     * writing the value through the Settings_Store and reading it back
     * SHALL return the same value.
     */
    test("Property 7: write valid fontScale, read back yields same value") {
        checkAll(PropTestConfig(iterations = 100), arbValidFontScale()) { fontScale ->
            val (_, settingsStore) = createSettingsStore()

            settingsStore.setFontScale(fontScale)

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.fontScale shouldBe fontScale
            }
        }
    }

    test("Property 7: write valid streamingRenderRate, read back yields same value") {
        checkAll(PropTestConfig(iterations = 100), arbValidStreamingRenderRate()) { rate ->
            val (_, settingsStore) = createSettingsStore()

            settingsStore.setStreamingRenderRate(rate)

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.streamingRenderRate shouldBe rate
            }
        }
    }

    test("Property 7: write valid themeMode, read back yields same value") {
        checkAll(PropTestConfig(iterations = 100), Arb.enum<ThemeMode>()) { mode ->
            val (_, settingsStore) = createSettingsStore()

            settingsStore.setThemeMode(mode)

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.themeMode shouldBe mode
            }
        }
    }

    test("Property 7: write valid modelId, read back via getModel() yields same value (settings flow no longer reflects modelId)") {
        checkAll(PropTestConfig(iterations = 100), arbModelId()) { modelId ->
            val (_, settingsStore) = createSettingsStore()

            // setModelId is deprecated (ai-provider-presets R11 AC1); the settings flow
            // no longer reads KEY_MODEL_ID. The value is still persisted so
            // LegacyKeyMigrator can read it via getModel().
            @Suppress("DEPRECATION")
            settingsStore.setModelId(modelId)

            // Verify the value is accessible via the legacy migrator accessor.
            settingsStore.getModel() shouldBe modelId

            // The settings flow always returns the default for modelId.
            settingsStore.settings.test {
                val settings = awaitItem()
                settings.modelId shouldBe AppSettings().modelId
            }
        }
    }

    test("Property 7: write valid systemPrompt, read back yields same value") {
        checkAll(PropTestConfig(iterations = 100), Arb.string(0..200)) { prompt ->
            val (_, settingsStore) = createSettingsStore()

            settingsStore.setSystemPrompt(prompt)

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.systemPrompt shouldBe prompt
            }
        }
    }

    test("Property 7: write valid defaultWorkspacePath, read back yields same value") {
        checkAll(PropTestConfig(iterations = 100), arbWorkspacePath()) { path ->
            val (_, settingsStore) = createSettingsStore()

            settingsStore.setDefaultWorkspacePath(path)

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.defaultWorkspacePath shouldBe path
            }
        }
    }

    test("Property 7: write valid autoStartForegroundService, read back yields same value") {
        checkAll(PropTestConfig(iterations = 100), Arb.boolean()) { enabled ->
            val (_, settingsStore) = createSettingsStore()

            settingsStore.setAutoStartForegroundService(enabled)

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.autoStartForegroundService shouldBe enabled
            }
        }
    }

    /**
     * Property 8: Invalid Preference Fallback
     *
     * **Validates: Requirements 9.4**
     *
     * For any preference key and value that falls outside the declared valid range,
     * reading the key from the Settings_Store SHALL return the declared default value
     * rather than the invalid value.
     */
    test("Property 8: invalid fontScale outside range falls back to default") {
        checkAll(PropTestConfig(iterations = 100), arbInvalidFontScale()) { invalidScale ->
            val (dataStore, settingsStore) = createSettingsStore()

            // Write invalid value directly to DataStore, bypassing validation
            dataStore.edit { prefs ->
                prefs[floatPreferencesKey("font_scale")] = invalidScale
            }

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.fontScale shouldBe AppSettings().fontScale
            }
        }
    }

    test("Property 8: invalid streamingRenderRate outside range falls back to default") {
        checkAll(PropTestConfig(iterations = 100), arbInvalidStreamingRenderRate()) { invalidRate ->
            val (dataStore, settingsStore) = createSettingsStore()

            // Write invalid value directly to DataStore, bypassing validation
            dataStore.edit { prefs ->
                prefs[longPreferencesKey("streaming_render_rate_ms")] = invalidRate
            }

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.streamingRenderRate shouldBe AppSettings().streamingRenderRate
            }
        }
    }
})

// --- Generators ---

/**
 * Generates valid font scale values within the range [0.5, 3.0].
 */
private fun arbValidFontScale(): Arb<Float> = Arb.float(
    min = 0.5f,
    max = 3.0f
).let { arb ->
    arbitrary {
        // Ensure we generate finite values within range (Arb.float can produce NaN/Infinity)
        var value: Float
        do {
            value = arb.bind()
        } while (value.isNaN() || value.isInfinite() || value < 0.5f || value > 3.0f)
        value
    }
}

/**
 * Generates valid streaming render rate values within the range [16, 1000].
 */
private fun arbValidStreamingRenderRate(): Arb<Long> = Arb.long(
    min = 16L,
    max = 1000L
)

/**
 * Generates model ID strings (non-empty, alphanumeric with dashes).
 */
private fun arbModelId(): Arb<String> = arbitrary {
    val length = Arb.long(1L..50L).bind().toInt()
    val chars = ('a'..'z') + ('0'..'9') + listOf('-', '_')
    buildString {
        repeat(length) {
            append(chars.random())
        }
    }
}

/**
 * Generates workspace path strings.
 */
private fun arbWorkspacePath(): Arb<String> = arbitrary {
    val segments = Arb.long(1L..5L).bind().toInt()
    val pathChars = ('a'..'z') + ('0'..'9') + listOf('-', '_')
    buildString {
        append("/")
        repeat(segments) { idx ->
            if (idx > 0) append("/")
            val segLen = Arb.long(1L..15L).bind().toInt()
            repeat(segLen) {
                append(pathChars.random())
            }
        }
    }
}

/**
 * Generates invalid font scale values that are outside the valid range [0.5, 3.0].
 * Produces values either below 0.5 or above 3.0.
 */
private fun arbInvalidFontScale(): Arb<Float> = arbitrary {
    val belowRange = Arb.boolean().bind()
    if (belowRange) {
        // Generate value below 0.5 (but still finite and not NaN)
        val value = Arb.float(min = -100f, max = 0.49f).bind()
        if (value.isNaN() || value.isInfinite()) 0.0f else value
    } else {
        // Generate value above 3.0
        val value = Arb.float(min = 3.01f, max = 100f).bind()
        if (value.isNaN() || value.isInfinite()) 5.0f else value
    }
}

/**
 * Generates invalid streaming render rate values outside the valid range [16, 1000].
 * Produces values either below 16 or above 1000.
 */
private fun arbInvalidStreamingRenderRate(): Arb<Long> = arbitrary {
    val belowRange = Arb.boolean().bind()
    if (belowRange) {
        // Generate value below 16
        Arb.long(min = -1000L, max = 15L).bind()
    } else {
        // Generate value above 1000
        Arb.long(min = 1001L, max = 100000L).bind()
    }
}
