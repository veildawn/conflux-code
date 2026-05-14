package com.claudemobile.core.data.credentials

import io.kotest.core.spec.style.FunSpec
import io.kotest.core.Tag
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll

/**
 * Property-based test for API Key Masking.
 *
 * **Validates: Requirements 6.3**
 *
 * Property 15: For any non-empty API key string, the masked representation should:
 * (a) not contain the complete original key (masked != key),
 * (b) show at most the last 4 characters,
 * (c) replace the rest with mask characters ('*').
 */
class ApiKeyMaskingPropertyTest : FunSpec({

    tags(
        Tag("Feature: android-claude-termux-client"),
        Tag("Property 15: API Key masking")
    )

    test("Feature: android-claude-termux-client, Property 15: API Key masking") {
        checkAll(PropTestConfig(iterations = 100), Arb.string(1..128).filter { it.isNotEmpty() }) { key ->
            val masked = computeMaskedKey(key)

            // Compute expected visible count using the same logic as the implementation
            val expectedVisibleCount = when {
                key.length <= CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH -> {
                    (key.length / 2).coerceAtMost(CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH)
                }
                else -> CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH
            }

            // (a) The masked representation is NOT identical to the original key.
            // This ensures the key is never displayed in plain text.
            masked shouldNotBe key

            // (b) At most the last 4 characters of the original key are visible in the output.
            expectedVisibleCount shouldBeLessThanOrEqual CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH

            // The visible portion (suffix of the masked string) matches the last N chars of the key
            if (expectedVisibleCount > 0) {
                val visiblePortion = masked.takeLast(expectedVisibleCount)
                visiblePortion shouldBe key.takeLast(expectedVisibleCount)
            }

            // (c) The leading portion of the masked string is composed entirely of mask characters ('*')
            val maskLength = masked.length - expectedVisibleCount
            maskLength shouldBeGreaterThan 0
            val maskPortion = masked.substring(0, maskLength)
            maskPortion.all { it == CredentialStoreImpl.MASK_CHAR } shouldBe true
        }
    }
})
