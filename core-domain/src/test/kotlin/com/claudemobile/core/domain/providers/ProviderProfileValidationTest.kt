package com.claudemobile.core.domain.providers

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [ProviderProfile.validate], covering every rule and
 * boundary case spelled out in design §2's validation table.
 *
 * Structure mirrors the table one-to-one:
 *   - `displayName`: non-blank, ≤ 80 chars
 *   - `baseUrl`: well-formed `https://…` URL
 *   - `baseUrl` (preset mode): must equal `preset.baseUrl` (R4.4)
 *   - `apiKey`: length ≥ 1
 *   - `model`: trimmed non-blank
 *   - `smallFastModel`: null/empty allowed, never a blocking error
 *   - multi-field error aggregation (R3.3's independence)
 *
 * Validates: Requirements 2.4, 3.2, 3.3, 3.4, 3.5, 3.6, 4.4.
 */
class ProviderProfileValidationTest : DescribeSpec({

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** A draft that passes every rule; individual tests mutate one field. */
    fun validDraft(): ProviderProfileDraft = ProviderProfileDraft(
        displayName = "My Provider",
        baseUrl = "https://api.example.com",
        apiKey = "k",
        model = "some-model",
        smallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.ApiKey,
        presetReference = PresetReference.Custom,
    )

    /** Minimal in-memory registry for preset-mode tests. */
    fun registryWith(vararg presets: ProviderPreset): ProviderRegistry =
        object : ProviderRegistry {
            private val map = presets.associateBy { it.presetId }
            override fun allPresets(): List<ProviderPreset> = presets.toList()
            override fun findById(presetId: String): ProviderPreset? = map[presetId]
        }

    val glmPreset = ProviderPreset(
        presetId = "glm_coding_plan",
        displayNameResId = 0, // not read by validator
        baseUrl = "https://open.bigmodel.cn/api/anthropic",
        defaultModel = "glm-4.6",
        defaultSmallFastModel = null,
        authHeaderStyle = AuthHeaderStyle.AuthToken,
    )

    // ---------------------------------------------------------------------
    // Baseline: a valid draft produces VALID.
    // ---------------------------------------------------------------------

    describe("valid drafts") {

        it("every rule satisfied → VALID singleton") {
            val result = ProviderProfile.validate(validDraft())
            result shouldBe ValidationResult.VALID
            result.isValid.shouldBeTrue()
            result.errors.shouldBeEmpty()
        }

        it("smallFastModel = null is allowed") {
            val result = ProviderProfile.validate(validDraft().copy(smallFastModel = null))
            result.isValid.shouldBeTrue()
        }

        it("smallFastModel = empty string is allowed") {
            val result = ProviderProfile.validate(validDraft().copy(smallFastModel = ""))
            result.isValid.shouldBeTrue()
        }

        it("smallFastModel = whitespace-only is allowed (warning-only field)") {
            val result = ProviderProfile.validate(validDraft().copy(smallFastModel = "   "))
            result.isValid.shouldBeTrue()
        }

        it("smallFastModel = non-blank value is allowed") {
            val result = ProviderProfile.validate(
                validDraft().copy(smallFastModel = "small-fast-1")
            )
            result.isValid.shouldBeTrue()
        }
    }

    // ---------------------------------------------------------------------
    // Rule: displayName non-blank, ≤ 80 chars (Requirements 2.4, 3.2).
    // ---------------------------------------------------------------------

    describe("displayName rule") {

        it("empty string → DisplayNameBlank") {
            val r = ProviderProfile.validate(validDraft().copy(displayName = ""))
            r.errorFor(ValidationField.DisplayName) shouldBe ValidationError.DisplayNameBlank
            r.isValid.shouldBeFalse()
        }

        it("whitespace-only → DisplayNameBlank (trim applied)") {
            val r = ProviderProfile.validate(validDraft().copy(displayName = "   \t\n"))
            r.errorFor(ValidationField.DisplayName) shouldBe ValidationError.DisplayNameBlank
        }

        it("exactly 1 char → valid (lower boundary)") {
            val r = ProviderProfile.validate(validDraft().copy(displayName = "a"))
            r.errorFor(ValidationField.DisplayName).shouldBeNull()
        }

        it("exactly 80 chars → valid (upper boundary)") {
            val name = "x".repeat(80)
            val r = ProviderProfile.validate(validDraft().copy(displayName = name))
            r.errorFor(ValidationField.DisplayName).shouldBeNull()
        }

        it("81 chars → DisplayNameTooLong (upper boundary + 1)") {
            val name = "x".repeat(81)
            val r = ProviderProfile.validate(validDraft().copy(displayName = name))
            r.errorFor(ValidationField.DisplayName) shouldBe ValidationError.DisplayNameTooLong
        }

        it("length measured on raw string, not trimmed") {
            // 80 spaces + a name → raw length 84, trimmed length 4.
            // Rule: raw length ≤ 80 → should fail TooLong.
            val raw = " ".repeat(80) + "name"
            val r = ProviderProfile.validate(validDraft().copy(displayName = raw))
            r.errorFor(ValidationField.DisplayName) shouldBe ValidationError.DisplayNameTooLong
        }

        it("blank takes precedence over TooLong when both apply") {
            // 81 spaces → both blank (trimmed empty) and too long. Design §2
            // lists "non-blank" before the length rule; the blank error wins.
            val raw = " ".repeat(81)
            val r = ProviderProfile.validate(validDraft().copy(displayName = raw))
            r.errorFor(ValidationField.DisplayName) shouldBe ValidationError.DisplayNameBlank
        }
    }

    // ---------------------------------------------------------------------
    // Rule: baseUrl well-formed https URL (Requirements 3.4).
    // ---------------------------------------------------------------------

    describe("baseUrl rule — syntactic") {

        it("https://host → valid") {
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "https://api.example.com")
            )
            r.errorFor(ValidationField.BaseUrl).shouldBeNull()
        }

        it("https://host/path → valid") {
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "https://open.bigmodel.cn/api/anthropic")
            )
            r.errorFor(ValidationField.BaseUrl).shouldBeNull()
        }

        it("empty string → BaseUrlInvalid") {
            val r = ProviderProfile.validate(validDraft().copy(baseUrl = ""))
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("http (non-https) → BaseUrlInvalid") {
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "http://api.example.com")
            )
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("ftp scheme → BaseUrlInvalid") {
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "ftp://api.example.com")
            )
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("mixed-case HTTPS scheme → BaseUrlInvalid (canonical form only)") {
            // java.net.URI preserves scheme case; our check is strict.
            // Real users typing "HTTPS://" is rare; when it happens, the
            // UI layer can lowercase before validating.
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "HTTPS://api.example.com")
            )
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("no scheme at all → BaseUrlInvalid") {
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "api.example.com")
            )
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("https:// with no host → BaseUrlInvalid") {
            val r = ProviderProfile.validate(validDraft().copy(baseUrl = "https://"))
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("malformed URI (unbalanced brackets) → BaseUrlInvalid, no throw") {
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "https://[not-an-ipv6")
            )
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("opaque URI (mailto:…) → BaseUrlInvalid") {
            val r = ProviderProfile.validate(
                validDraft().copy(baseUrl = "mailto:ops@example.com")
            )
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }
    }

    // ---------------------------------------------------------------------
    // Rule: baseUrl must equal preset.baseUrl when preset-derived (R4.4).
    // ---------------------------------------------------------------------

    describe("baseUrl rule — preset locking (R4.4)") {

        val registry = registryWith(glmPreset)

        it("preset draft with matching baseUrl → valid") {
            val draft = validDraft().copy(
                baseUrl = glmPreset.baseUrl,
                presetReference = PresetReference.Preset(glmPreset.presetId),
            )
            val r = ProviderProfile.validate(draft, registry)
            r.errorFor(ValidationField.BaseUrl).shouldBeNull()
            r.isValid.shouldBeTrue()
        }

        it("preset draft with different (but well-formed) baseUrl → BaseUrlPresetLocked") {
            val draft = validDraft().copy(
                baseUrl = "https://other.example.com",
                presetReference = PresetReference.Preset(glmPreset.presetId),
            )
            val r = ProviderProfile.validate(draft, registry)
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlPresetLocked
        }

        it("preset draft with malformed baseUrl → BaseUrlInvalid (syntax check wins)") {
            // Syntactic rule runs first; preset-lock is only consulted when
            // the URL is otherwise well-formed, so the user sees the more
            // specific "invalid URL" message rather than "preset locked".
            val draft = validDraft().copy(
                baseUrl = "not-a-url",
                presetReference = PresetReference.Preset(glmPreset.presetId),
            )
            val r = ProviderProfile.validate(draft, registry)
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
        }

        it("unknown presetId (preset removed in later build) → lock check skipped") {
            // Design: if findById returns null, we treat the draft as
            // Custom for baseUrl purposes. Any well-formed https URL passes.
            val draft = validDraft().copy(
                baseUrl = "https://new-backend.example.com",
                presetReference = PresetReference.Preset("removed_preset"),
            )
            val r = ProviderProfile.validate(draft, registry)
            r.errorFor(ValidationField.BaseUrl).shouldBeNull()
        }

        it("Custom draft is never subject to preset lock") {
            val draft = validDraft().copy(
                baseUrl = "https://free-choice.example.com",
                presetReference = PresetReference.Custom,
            )
            val r = ProviderProfile.validate(draft, registry)
            r.errorFor(ValidationField.BaseUrl).shouldBeNull()
        }

        it("no registry passed → preset lock not enforced (pure-syntactic overload)") {
            val draft = validDraft().copy(
                baseUrl = "https://other.example.com",
                presetReference = PresetReference.Preset(glmPreset.presetId),
            )
            val r = ProviderProfile.validate(draft) // 1-arg overload
            r.errorFor(ValidationField.BaseUrl).shouldBeNull()
            r.isValid.shouldBeTrue()
        }
    }

    // ---------------------------------------------------------------------
    // Rule: apiKey non-empty (Requirements 2.4, 3.5).
    // ---------------------------------------------------------------------

    describe("apiKey rule") {

        it("empty string → ApiKeyEmpty") {
            val r = ProviderProfile.validate(validDraft().copy(apiKey = ""))
            r.errorFor(ValidationField.ApiKey) shouldBe ValidationError.ApiKeyEmpty
        }

        it("single character → valid (lower boundary, length = 1)") {
            val r = ProviderProfile.validate(validDraft().copy(apiKey = "x"))
            r.errorFor(ValidationField.ApiKey).shouldBeNull()
        }

        it("whitespace-only key → valid (rule is length ≥ 1, not 'non-blank')") {
            // Design §2 explicitly specifies "apiKey ≥ 1 character", so a
            // single space is a length-1 key and must pass. Defends against
            // the common mistake of over-trimming what might be a valid
            // token with leading/trailing whitespace in some provider.
            val r = ProviderProfile.validate(validDraft().copy(apiKey = " "))
            r.errorFor(ValidationField.ApiKey).shouldBeNull()
        }

        it("long key → valid (no upper bound)") {
            val r = ProviderProfile.validate(
                validDraft().copy(apiKey = "sk-ant-" + "a".repeat(200))
            )
            r.errorFor(ValidationField.ApiKey).shouldBeNull()
        }
    }

    // ---------------------------------------------------------------------
    // Rule: model non-blank after trim (Requirements 3.6).
    // ---------------------------------------------------------------------

    describe("model rule") {

        it("empty → ModelBlank") {
            val r = ProviderProfile.validate(validDraft().copy(model = ""))
            r.errorFor(ValidationField.Model) shouldBe ValidationError.ModelBlank
        }

        it("whitespace-only → ModelBlank (trim applied)") {
            val r = ProviderProfile.validate(validDraft().copy(model = "\t  \n"))
            r.errorFor(ValidationField.Model) shouldBe ValidationError.ModelBlank
        }

        it("non-blank with surrounding whitespace → valid") {
            // The raw form is preserved; we only apply trim for the
            // emptiness check. Normalization (trim on persist) is the
            // data layer's responsibility, not the validator's.
            val r = ProviderProfile.validate(validDraft().copy(model = "  glm-4.6 "))
            r.errorFor(ValidationField.Model).shouldBeNull()
        }

        it("single char → valid (lower boundary)") {
            val r = ProviderProfile.validate(validDraft().copy(model = "m"))
            r.errorFor(ValidationField.Model).shouldBeNull()
        }
    }

    // ---------------------------------------------------------------------
    // Cross-field: independence of field-level errors (R3.3).
    // ---------------------------------------------------------------------

    describe("error independence across fields (R3.3)") {

        it("all fields invalid → every expected error is reported") {
            val draft = ProviderProfileDraft(
                displayName = "",
                baseUrl = "not-a-url",
                apiKey = "",
                model = "",
                smallFastModel = null,
                authHeaderStyle = AuthHeaderStyle.ApiKey,
                presetReference = PresetReference.Custom,
            )
            val r = ProviderProfile.validate(draft)

            r.isValid.shouldBeFalse()
            r.errors.keys shouldBe setOf(
                ValidationField.DisplayName,
                ValidationField.BaseUrl,
                ValidationField.ApiKey,
                ValidationField.Model,
            )
            r.errorFor(ValidationField.DisplayName) shouldBe ValidationError.DisplayNameBlank
            r.errorFor(ValidationField.BaseUrl) shouldBe ValidationError.BaseUrlInvalid
            r.errorFor(ValidationField.ApiKey) shouldBe ValidationError.ApiKeyEmpty
            r.errorFor(ValidationField.Model) shouldBe ValidationError.ModelBlank
            r.errorFor(ValidationField.SmallFastModel).shouldBeNull()
        }

        it("fixing one field does not change another field's verdict") {
            val bad = validDraft().copy(apiKey = "", model = "")
            val beforeFix = ProviderProfile.validate(bad)
            val afterFix = ProviderProfile.validate(bad.copy(apiKey = "now-valid"))

            // apiKey error cleared; model error unchanged.
            beforeFix.errorFor(ValidationField.ApiKey) shouldBe ValidationError.ApiKeyEmpty
            afterFix.errorFor(ValidationField.ApiKey).shouldBeNull()
            afterFix.errorFor(ValidationField.Model) shouldBe ValidationError.ModelBlank
        }
    }

    // ---------------------------------------------------------------------
    // Purity: same input → same output (no cached state, no randomness).
    // ---------------------------------------------------------------------

    describe("purity") {

        it("two calls with equal drafts return equal results") {
            val draft = validDraft().copy(displayName = " ".repeat(81))
            val a = ProviderProfile.validate(draft)
            val b = ProviderProfile.validate(draft)
            a shouldBe b
        }

        it("ValidationResult value-equality holds") {
            val r1 = ProviderProfile.validate(validDraft().copy(apiKey = ""))
            val r2 = ProviderProfile.validate(validDraft().copy(apiKey = ""))
            r1 shouldBe r2
            r1.hashCode() shouldBe r2.hashCode()
        }
    }
})
