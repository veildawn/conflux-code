package com.claudemobile.core.data.providers

import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString

/**
 * Unit tests for [ProviderProfileDto] and its domain mappers.
 *
 * Covers both `PresetReference` variants (`Preset(presetId)` and
 * `Custom`) as well as the optional `smallFastModel == null` case.
 *
 * Validates: Requirements 4.7, 12.1.
 */
class ProviderProfileDtoTest : DescribeSpec({

    describe("domain <-> DTO round-trip via direct mapping") {

        it("round-trips a Preset-backed profile with smallFastModel set") {
            val profile = ProviderProfile(
                profileId = "11111111-2222-3333-4444-555555555555",
                displayName = "GLM Coding",
                presetReference = PresetReference.Preset("glm_coding_plan"),
                baseUrl = "https://open.bigmodel.cn/api/anthropic",
                apiKey = "sk-test-live-1234",
                model = "glm-4.6",
                smallFastModel = "glm-4-fast",
                authHeaderStyle = AuthHeaderStyle.AuthToken,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_000_500L,
            )

            val roundTripped = profile.toDto().toDomain()

            roundTripped shouldBe profile
        }

        it("round-trips a Preset-backed profile with smallFastModel = null") {
            val profile = ProviderProfile(
                profileId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                displayName = "Kimi Code",
                presetReference = PresetReference.Preset("kimi_code_plan"),
                baseUrl = "https://api.moonshot.cn/anthropic",
                apiKey = "sk-kimi-test",
                model = "kimi-k2-turbo-preview",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.AuthToken,
                createdAt = 1_730_100_000_000L,
                updatedAt = 1_730_100_000_000L,
            )

            val roundTripped = profile.toDto().toDomain()

            roundTripped shouldBe profile
            roundTripped.smallFastModel shouldBe null
        }

        it("round-trips a Custom profile with ApiKey auth style") {
            val profile = ProviderProfile(
                profileId = "deadbeef-dead-beef-dead-beefdeadbeef",
                displayName = "Anthropic (default)",
                presetReference = PresetReference.Custom,
                baseUrl = "https://api.anthropic.com",
                apiKey = "sk-ant-api03-xyz",
                model = "claude-3-5-sonnet-20241022",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.ApiKey,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_000_000L,
            )

            val roundTripped = profile.toDto().toDomain()

            roundTripped shouldBe profile
            roundTripped.presetReference shouldBe PresetReference.Custom
        }
    }

    describe("JSON codec round-trip") {

        it("round-trips a Preset-backed profile through JSON") {
            val profile = ProviderProfile(
                profileId = "profile-preset-1",
                displayName = "MiniMax Token",
                presetReference = PresetReference.Preset("minimax_token_plan"),
                baseUrl = "https://api.minimaxi.com/anthropic",
                apiKey = "mm-test-key",
                model = "MiniMax-M2",
                smallFastModel = "MiniMax-Fast",
                authHeaderStyle = AuthHeaderStyle.AuthToken,
                createdAt = 1_730_200_000_000L,
                updatedAt = 1_730_200_000_000L,
            )

            val json = ProviderProfileJson.encodeToString(profile.toDto())
            val decoded = ProviderProfileJson
                .decodeFromString(ProviderProfileDto.serializer(), json)
                .toDomain()

            decoded shouldBe profile
        }

        it("round-trips a Custom profile through JSON") {
            val profile = ProviderProfile(
                profileId = "profile-custom-1",
                displayName = "Local proxy",
                presetReference = PresetReference.Custom,
                baseUrl = "https://proxy.example.com",
                apiKey = "local-xyz",
                model = "claude-3-5-haiku-20241022",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.ApiKey,
                createdAt = 1_730_300_000_000L,
                updatedAt = 1_730_300_000_001L,
            )

            val json = ProviderProfileJson.encodeToString(profile.toDto())
            val decoded = ProviderProfileJson
                .decodeFromString(ProviderProfileDto.serializer(), json)
                .toDomain()

            decoded shouldBe profile
        }

        it("emits the design discriminator for Preset presetReference") {
            val dto = ProviderProfile(
                profileId = "id-1",
                displayName = "GLM",
                presetReference = PresetReference.Preset("glm_coding_plan"),
                baseUrl = "https://open.bigmodel.cn/api/anthropic",
                apiKey = "k",
                model = "glm-4.6",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.AuthToken,
                createdAt = 0L,
                updatedAt = 0L,
            ).toDto()

            val json = ProviderProfileJson.encodeToString(dto)

            // "type":"preset" is the design-level class discriminator.
            json.shouldContain("\"type\":\"preset\"")
            json.shouldContain("\"presetId\":\"glm_coding_plan\"")
        }

        it("emits the design discriminator for Custom presetReference") {
            val dto = ProviderProfile(
                profileId = "id-2",
                displayName = "Custom",
                presetReference = PresetReference.Custom,
                baseUrl = "https://api.anthropic.com",
                apiKey = "k",
                model = "claude-3-5-sonnet-20241022",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.ApiKey,
                createdAt = 0L,
                updatedAt = 0L,
            ).toDto()

            val json = ProviderProfileJson.encodeToString(dto)

            json.shouldContain("\"type\":\"custom\"")
        }

        it("emits authHeaderStyle as the enum name, not the ordinal") {
            val dto = ProviderProfile(
                profileId = "id-3",
                displayName = "X",
                presetReference = PresetReference.Custom,
                baseUrl = "https://api.anthropic.com",
                apiKey = "k",
                model = "m",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.AuthToken,
                createdAt = 0L,
                updatedAt = 0L,
            ).toDto()

            val json = ProviderProfileJson.encodeToString(dto)

            json.shouldContain("\"authHeaderStyle\":\"AuthToken\"")
        }

        it("tolerates explicit null smallFastModel on decode") {
            val withExplicitNull = """
                {
                  "profileId": "id-null",
                  "displayName": "Null",
                  "presetReference": { "type": "custom" },
                  "baseUrl": "https://api.anthropic.com",
                  "apiKey": "k",
                  "model": "m",
                  "smallFastModel": null,
                  "authHeaderStyle": "ApiKey",
                  "createdAt": 1,
                  "updatedAt": 2
                }
            """.trimIndent()

            val decoded = ProviderProfileJson
                .decodeFromString(ProviderProfileDto.serializer(), withExplicitNull)
                .toDomain()

            decoded.smallFastModel shouldBe null
            decoded.presetReference shouldBe PresetReference.Custom
        }

        it("tolerates missing smallFastModel key on decode") {
            val withMissingKey = """
                {
                  "profileId": "id-miss",
                  "displayName": "Miss",
                  "presetReference": { "type": "custom" },
                  "baseUrl": "https://api.anthropic.com",
                  "apiKey": "k",
                  "model": "m",
                  "authHeaderStyle": "ApiKey",
                  "createdAt": 1,
                  "updatedAt": 2
                }
            """.trimIndent()

            val decoded = ProviderProfileJson
                .decodeFromString(ProviderProfileDto.serializer(), withMissingKey)
                .toDomain()

            decoded.smallFastModel shouldBe null
        }

        it("rejects an unknown authHeaderStyle on decode") {
            val withBadStyle = """
                {
                  "profileId": "id-bad",
                  "displayName": "Bad",
                  "presetReference": { "type": "custom" },
                  "baseUrl": "https://api.anthropic.com",
                  "apiKey": "k",
                  "model": "m",
                  "authHeaderStyle": "NotAStyle",
                  "createdAt": 1,
                  "updatedAt": 2
                }
            """.trimIndent()

            shouldThrow<IllegalArgumentException> {
                ProviderProfileJson
                    .decodeFromString(ProviderProfileDto.serializer(), withBadStyle)
                    .toDomain()
            }
        }
    }

    describe("PresetReference mappers") {

        it("maps Preset(presetId) both directions") {
            val domain = PresetReference.Preset("glm_coding_plan")

            val dto = domain.toDto()
            dto shouldBe PresetReferenceDto.Preset("glm_coding_plan")
            dto.toDomain() shouldBe domain
        }

        it("maps Custom both directions") {
            val domain = PresetReference.Custom

            val dto = domain.toDto()
            dto shouldBe PresetReferenceDto.Custom
            dto.toDomain() shouldBe domain
        }
    }
})
