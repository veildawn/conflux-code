package com.claudemobile.core.data.diagnostics

import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Example-based tests for [redactProviderSecrets].
 *
 * Complements the property-based tests in
 * `ProviderRedactionPropertyTest` (spec task 3.21 / 3.22, Properties 14
 * and 15) with typical scenarios that are easier to read than the
 * generator-driven equivalents.
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4.
 */
class ProviderRedactionTest : FunSpec({

    val marker = "\u2022\u2022\u2022REDACTED\u2022\u2022\u2022"

    fun profile(
        id: String = "p1",
        apiKey: String,
        baseUrl: String = "https://api.anthropic.com",
        authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.ApiKey,
    ): ProviderProfile = ProviderProfile(
        profileId = id,
        displayName = "test-$id",
        presetReference = PresetReference.Custom,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = "test-model",
        smallFastModel = null,
        authHeaderStyle = authHeaderStyle,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    test("empty text is returned unchanged") {
        val profiles = listOf(profile(apiKey = "sk-ant-secret"))

        redactProviderSecrets("", profiles) shouldBe ""
    }

    test("empty profile list is a no-op") {
        val text = "diagnostics with sk-ant-xxxx embedded"

        redactProviderSecrets(text, profiles = emptyList()) shouldBe text
    }

    test("single apiKey occurrence is replaced with the marker") {
        val key = "sk-ant-abc123"
        val text = "auth header: Authorization: Bearer $key; done"

        val result = redactProviderSecrets(text, listOf(profile(apiKey = key)))

        result shouldNotContain key
        result shouldContain marker
        result shouldContain "auth header: Authorization: Bearer $marker; done"
    }

    test("multiple occurrences of the same apiKey are all replaced") {
        val key = "token-xyz"
        val text = "first=$key mid $key end=$key"

        val result = redactProviderSecrets(text, listOf(profile(apiKey = key)))

        result shouldNotContain key
        // Three occurrences replaced → exactly three markers.
        result.split(marker).size shouldBe 4
    }

    test("distinct apiKeys across multiple profiles are all replaced") {
        val k1 = "key-one"
        val k2 = "key-two-longer"
        val k3 = "k3"
        val text = "p1=$k1 p2=$k2 p3=$k3 tail"

        val result = redactProviderSecrets(
            text,
            listOf(
                profile(id = "a", apiKey = k1),
                profile(id = "b", apiKey = k2),
                profile(id = "c", apiKey = k3),
            ),
        )

        result shouldNotContain k1
        result shouldNotContain k2
        result shouldNotContain k3
    }

    test("empty apiKey is skipped (no runaway marker splicing)") {
        val text = "hello world"

        val result = redactProviderSecrets(text, listOf(profile(apiKey = "")))

        result shouldBe text
    }

    test("baseUrl with userinfo has user:token segment replaced with REDACTED@") {
        val url = "https://alice:s3cret@example.com/v1/messages"
        val text = "outbound request to $url succeeded"

        val result = redactProviderSecrets(
            text,
            listOf(profile(apiKey = "unrelated", baseUrl = url)),
        )

        result shouldNotContain "alice:s3cret@"
        result shouldContain "https://REDACTED@example.com/v1/messages"
    }

    test("baseUrl without userinfo leaves URL text alone") {
        val url = "https://api.anthropic.com/v1/messages"
        val text = "probe $url -> 200"

        val result = redactProviderSecrets(
            text,
            listOf(profile(apiKey = "k", baseUrl = url)),
        )

        result shouldContain url
    }

    test("both rules apply together for a single profile") {
        val key = "bearer-42"
        val url = "https://u:t@provider.example.com/anthropic"
        val text = "POST $url\nauth=$key\nbody=..."

        val result = redactProviderSecrets(
            text,
            listOf(profile(apiKey = key, baseUrl = url)),
        )

        result shouldNotContain key
        result shouldNotContain "u:t@"
        result shouldContain "https://REDACTED@provider.example.com/anthropic"
        result shouldContain marker
    }

    test("URL with path, query and fragment keeps non-userinfo parts intact") {
        val url = "https://carol:tok@example.com/a/b?x=1#frag"
        val text = "see $url for details"

        val result = redactProviderSecrets(
            text,
            listOf(profile(apiKey = "unused", baseUrl = url)),
        )

        result shouldNotContain "carol:tok@"
        result shouldContain "https://REDACTED@example.com/a/b?x=1#frag"
    }

    test("function is deterministic: same inputs yield same output") {
        val text = "k=secret url=https://u:p@h.example.com/x"
        val profiles = listOf(
            profile(apiKey = "secret", baseUrl = "https://u:p@h.example.com/x"),
        )

        val first = redactProviderSecrets(text, profiles)
        val second = redactProviderSecrets(text, profiles)

        first shouldBe second
    }
})
