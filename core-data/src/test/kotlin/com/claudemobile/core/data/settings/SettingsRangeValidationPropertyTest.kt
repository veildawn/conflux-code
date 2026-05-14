package com.claudemobile.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.cash.turbine.test
import com.claudemobile.core.domain.model.AppSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for Settings range validation fallback.
 *
 * **Validates: Requirements 9.4**
 *
 * Property 14: For any fontScale value outside [0.5, 3.0], or streamingRenderRate value
 * outside [16, 1000], SettingsStore should return the default value (fontScale=1.0,
 * streamingRenderRate=50) when reading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRangeValidationPropertyTest : FunSpec({

    val tempDirBase = File(System.getProperty("java.io.tmpdir"), "settings_range_prop_test_${System.nanoTime()}")
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
                File(tempDirBase, "test_range_${fileCounter.incrementAndGet()}.preferences_pb")
            }
        )
        return dataStore to SettingsStoreImpl(dataStore)
    }

    /**
     * Property 14: Settings range validation fallback - fontScale
     *
     * **Validates: Requirements 9.4**
     *
     * For any fontScale value outside [0.5, 3.0], SettingsStore should return the
     * default value (1.0) when reading.
     */
    test("Feature: android-claude-termux-client, Property 14: Settings range validation fallback - fontScale outside range returns default") {
        checkAll(PropTestConfig(iterations = 100), arbFontScaleOutsideRange()) { invalidScale ->
            val (dataStore, settingsStore) = createSettingsStore()

            // Write invalid value directly to DataStore, bypassing SettingsStore validation
            dataStore.edit { prefs ->
                prefs[floatPreferencesKey("font_scale")] = invalidScale
            }

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.fontScale shouldBe 1.0f
            }
        }
    }

    /**
     * Property 14: Settings range validation fallback - streamingRenderRate
     *
     * **Validates: Requirements 9.4**
     *
     * For any streamingRenderRate value outside [16, 1000], SettingsStore should return
     * the default value (50) when reading.
     */
    test("Feature: android-claude-termux-client, Property 14: Settings range validation fallback - streamingRenderRate outside range returns default") {
        checkAll(PropTestConfig(iterations = 100), arbStreamingRenderRateOutsideRange()) { invalidRate ->
            val (dataStore, settingsStore) = createSettingsStore()

            // Write invalid value directly to DataStore, bypassing SettingsStore validation
            dataStore.edit { prefs ->
                prefs[longPreferencesKey("streaming_render_rate_ms")] = invalidRate
            }

            settingsStore.settings.test {
                val settings = awaitItem()
                settings.streamingRenderRate shouldBe 50L
            }
        }
    }
})

// --- Generators ---

/**
 * Generates fontScale values outside the valid range [0.5, 3.0].
 * Produces negative values, values < 0.5, values > 3.0, and very large values.
 */
private fun arbFontScaleOutsideRange(): Arb<Float> = arbitrary {
    val belowRange = Arb.boolean().bind()
    if (belowRange) {
        // Generate values below 0.5: negative, zero, or just under 0.5
        val value = Arb.float(min = -1000f, max = 0.499f).bind()
        if (value.isNaN() || value.isInfinite()) -1.0f else value
    } else {
        // Generate values above 3.0: just over 3.0 or very large
        val value = Arb.float(min = 3.001f, max = 10000f).bind()
        if (value.isNaN() || value.isInfinite()) 5.0f else value
    }
}

/**
 * Generates streamingRenderRate values outside the valid range [16, 1000].
 * Produces values < 16 (including negative) and values > 1000.
 */
private fun arbStreamingRenderRateOutsideRange(): Arb<Long> = arbitrary {
    val belowRange = Arb.boolean().bind()
    if (belowRange) {
        // Generate values below 16 (including negative and zero)
        Arb.long(min = -10000L, max = 15L).bind()
    } else {
        // Generate values above 1000
        Arb.long(min = 1001L, max = 1000000L).bind()
    }
}
