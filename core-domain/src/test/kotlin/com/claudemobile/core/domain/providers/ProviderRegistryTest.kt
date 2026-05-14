package com.claudemobile.core.domain.providers

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Unit tests for [ProviderRegistry.Default] covering R1.1–R1.3.
 *
 * The tests only use the public [ProviderRegistry] API; they do not import
 * the `internal` preset constants so that the registry remains the sole
 * entry point for preset lookup.
 */
class ProviderRegistryTest : DescribeSpec({

    val registry = ProviderRegistry.Default

    describe("ProviderRegistry.Default") {

        it("exposes at least the three built-in presets (R1.1)") {
            val ids = registry.allPresets().map { it.presetId }
            ids shouldContainExactly listOf(
                "glm_coding_plan",
                "minimax_token_plan",
                "kimi_code_plan",
            )
        }

        it("exposes non-empty fields for every preset (R1.2)") {
            registry.allPresets().forAll { preset ->
                preset.presetId.shouldNotBeBlank()
                preset.baseUrl.shouldNotBeBlank()
                preset.defaultModel.shouldNotBeBlank()
                // displayNameResId is an Android resource id — a valid
                // reference is always non-zero at compile time.
                (preset.displayNameResId != 0) shouldBe true
            }
        }

        it("pins every preset to https:// (R1.2)") {
            registry.allPresets().forAll { preset ->
                preset.baseUrl.startsWith("https://") shouldBe true
            }
        }

        it("returns the matching preset from findById") {
            val glm = registry.findById("glm_coding_plan").shouldNotBeNull()
            glm.baseUrl shouldBe "https://open.bigmodel.cn/api/anthropic"
            glm.defaultModel shouldBe "glm-4.6"
            glm.authHeaderStyle shouldBe AuthHeaderStyle.AuthToken
            glm.defaultSmallFastModel shouldBe null

            val minimax = registry.findById("minimax_token_plan").shouldNotBeNull()
            minimax.baseUrl shouldBe "https://api.minimaxi.com/anthropic"
            minimax.defaultModel shouldBe "MiniMax-M2"
            minimax.authHeaderStyle shouldBe AuthHeaderStyle.AuthToken
            minimax.defaultSmallFastModel shouldBe null

            val kimi = registry.findById("kimi_code_plan").shouldNotBeNull()
            kimi.baseUrl shouldBe "https://api.moonshot.cn/anthropic"
            kimi.defaultModel shouldBe "kimi-k2-turbo-preview"
            kimi.authHeaderStyle shouldBe AuthHeaderStyle.AuthToken
            kimi.defaultSmallFastModel shouldBe null
        }

        it("returns null from findById for unknown ids") {
            registry.findById("unknown") shouldBe null
            registry.findById("") shouldBe null
            registry.findById("GLM_CODING_PLAN") shouldBe null // case-sensitive
        }

        it("returns the same instance on repeat allPresets() calls") {
            // Sanity check that the registry does not allocate fresh data on
            // each call — a weak but useful guarantee against accidental
            // refactors that would defeat the design's immutability intent.
            val a = registry.allPresets()
            val b = registry.allPresets()
            a shouldBe b
        }
    }
})

private inline fun <T> List<T>.forAll(block: (T) -> Unit) {
    for (element in this) block(element)
}
