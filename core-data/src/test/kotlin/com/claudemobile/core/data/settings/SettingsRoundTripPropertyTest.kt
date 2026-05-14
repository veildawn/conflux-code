package com.claudemobile.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
 * Property-based test for Settings round-trip.
 *
 * **Validates: Requirements 9.6**
 *
 * For any preference key `k` and valid value `v` within that key's range,
 * writing `v` to `k` through SettingsStore and reading `k` back should return `v`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRoundTripPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 13: Settings round-trip")
    )

    val tempDirBase = File(System.getProperty("java.io.tmpdir"), "settings_roundtrip_prop_test_${System.nanoTime()}")
    val fileCounter = AtomicInteger(0)

    beforeSpec {
        tempDirBase.mkdirs()
    }

    afterSpec {
        tempDirBase.deleteRecursively()
    }

    fun createSettingsStore(): Pair<DataStore<Preferences>, SettingsStoreImpl> {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = {
                File(tempDirBase, "roundtrip_${fileCounter.incrementAndGet()}.preferences_pb")
            }
        )
        return dataStore to SettingsStoreImpl(dataStore)
    }

    /**
     * Property 13: Settings round-trip
     *
     * **Validates: Requirements 9.6**
     *
     * For any preference key `k` and valid value `v` within that key's range,
     * writing `v` to `k` through SettingsStore and reading `k` back should return `v`.
     */
    test("Feature: android-claude-termux-client, Property 13: Settings round-trip") {
        checkAll(
            PropTestConfig(iterations = 100),
            arbValidFontScale(),
            arbValidStreamingRenderRate(),
            Arb.enum<ThemeMode>(),
            arbModelId(),
            Arb.string(0..200)
        ) { fontScale, streamingRenderRate, themeMode, modelId ->
            val (_, settingsStore) = createSettingsStore()

            // Write all settings
            settingsStore.setFontScale(fontScale)
            settingsStore.setStreamingRenderRate(streamingRenderRate)
            settingsStore.setThemeMode(themeMode)
            // setModelId is deprecated (ai-provider-presets R11 AC1); the settings flow
            // no longer reads KEY_MODEL_ID. Write it so LegacyKeyMigrator can read it
            // via getModel(), but do not assert it round-trips through settings.
            @Suppress("DEPRECATION")
            settingsStore.setModelId(modelId)

            // Read back and verify round-trip (modelId excluded — see above)
            settingsStore.settings.test {
                val settings = awaitItem()
                settings.fontScale shouldBe fontScale
                settings.streamingRenderRate shouldBe streamingRenderRate
                settings.themeMode shouldBe themeMode
                // modelId always returns the default in the settings flow (R11 AC1).
                settings.modelId shouldBe AppSettings().modelId
            }

            // Verify the written modelId is accessible via the legacy migrator accessor.
            settingsStore.getModel() shouldBe modelId
        }
    }
})

// --- Generators ---

/**
 * Generates valid font scale values within the range [0.5, 3.0].
 */
private fun arbValidFontScale(): Arb<Float> = arbitrary {
    val raw = Arb.float(min = 0.5f, max = 3.0f).bind()
    // Ensure we get a finite value within range
    when {
        raw.isNaN() || raw.isInfinite() -> 1.0f
        raw < 0.5f -> 0.5f
        raw > 3.0f -> 3.0f
        else -> raw
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
 * Generates model ID strings (non-empty, alphanumeric with dashes/underscores).
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
