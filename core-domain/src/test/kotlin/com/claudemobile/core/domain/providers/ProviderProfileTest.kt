package com.claudemobile.core.domain.providers

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit tests for [ProviderProfile] covering:
 *
 * - Boundary behaviour of [ProviderProfile.maskedApiKey] at lengths
 *   0, 1, 4, 5, and 100 (R9.2).
 * - Data-class equality semantics: value-equality on all fields and
 *   structural inequality when a single field differs.
 */
class ProviderProfileTest : DescribeSpec({

    val mask = "\u2022\u2022\u2022\u2022" // four bullets

    fun profile(apiKey: String): ProviderProfile = ProviderProfile(
        profileId = "fixture-id",
        displayName = "Fixture",
        presetReference = PresetReference.Custom,
        baseUrl = "https://api.example.com",
        apiKey = apiKey,
        model = "example-model",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    describe("maskedApiKey() boundary cases") {

        it("length 0 → fully masked (no suffix)") {
            profile("").maskedApiKey() shouldBe mask
        }

        it("length 1 → fully masked (no suffix)") {
            profile("a").maskedApiKey() shouldBe mask
        }

        it("length 4 → fully masked (no suffix)") {
            profile("abcd").maskedApiKey() shouldBe mask
        }

        it("length 5 → mask + last 4 characters") {
            profile("abcde").maskedApiKey() shouldBe mask + "bcde"
        }

        it("length 100 → mask + only the last 4 characters") {
            val key = "k".repeat(96) + "wxyz"
            val masked = profile(key).maskedApiKey()

            masked shouldBe mask + "wxyz"
            // Defence-in-depth: the first 96 characters must not leak.
            (masked.contains("k".repeat(5))) shouldBe false
        }
    }

    describe("equality semantics") {

        val base = ProviderProfile(
            profileId = "p-1",
            displayName = "GLM",
            presetReference = PresetReference.Preset("glm_coding_plan"),
            baseUrl = "https://open.bigmodel.cn/api/anthropic",
            apiKey = "secret-key-1234",
            model = "glm-4.6",
            smallFastModel = null,
            authHeaderStyle = AuthHeaderStyle.AuthToken,
            createdAt = 10L,
            updatedAt = 20L,
        )

        it("two instances with identical fields are equal") {
            val copy = base.copy()
            copy shouldBe base
            copy.hashCode() shouldBe base.hashCode()
        }

        it("differ by profileId → not equal") {
            base.copy(profileId = "p-2") shouldNotBe base
        }

        it("differ by apiKey → not equal") {
            base.copy(apiKey = "other-secret") shouldNotBe base
        }

        it("differ by presetReference variant → not equal") {
            base.copy(presetReference = PresetReference.Custom) shouldNotBe base
        }

        it("differ by authHeaderStyle → not equal") {
            base.copy(authHeaderStyle = AuthHeaderStyle.ApiKey) shouldNotBe base
        }

        it("differ by smallFastModel null vs value → not equal") {
            base.copy(smallFastModel = "glm-4-air") shouldNotBe base
        }

        it("differ by updatedAt → not equal (timestamps participate in equality)") {
            base.copy(updatedAt = 21L) shouldNotBe base
        }

        it("PresetReference.Custom is a singleton (data object)") {
            (PresetReference.Custom === PresetReference.Custom) shouldBe true
        }

        it("PresetReference.Preset uses value equality on presetId") {
            PresetReference.Preset("x") shouldBe PresetReference.Preset("x")
            PresetReference.Preset("x") shouldNotBe PresetReference.Preset("y")
        }
    }
})
