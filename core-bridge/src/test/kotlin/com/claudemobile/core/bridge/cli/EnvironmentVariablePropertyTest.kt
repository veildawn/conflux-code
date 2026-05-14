package com.claudemobile.core.bridge.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.beEmpty
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based test for environment variable completeness.
 *
 * **Validates: Requirements 2.3**
 *
 * Property 2: For any valid SpawnConfig (with non-empty workspace path and API key),
 * the constructed environment variable mapping should contain HOME, PATH, TERM, LANG,
 * and ANTHROPIC_API_KEY keys, and all values should be non-empty.
 */
class EnvironmentVariablePropertyTest : FunSpec({

    val requiredKeys = listOf("HOME", "PATH", "TERM", "LANG", "ANTHROPIC_API_KEY")

    // Generator for non-empty strings (simulating workspace paths, API keys, etc.)
    val nonEmptyStringArb = Arb.string(minSize = 1, maxSize = 200)
        .filter { it.isNotBlank() }

    test("Feature: android-claude-termux-client, Property 2: Environment variable completeness") {
        checkAll(100, nonEmptyStringArb, nonEmptyStringArb) { workspacePath, apiKey ->
            // Build the environment variable map using the function under test
            val envVars = buildProotEnvironment(apiKey)

            // Verify all required keys are present
            for (key in requiredKeys) {
                envVars shouldContainKey key
            }

            // Verify all values are non-empty
            for (key in requiredKeys) {
                envVars[key]!! shouldNot beEmpty()
            }
        }
    }
})
