package com.claudemobile.core.domain.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Property-based test for Exit Code Classification Determinism.
 *
 * **Validates: Requirements 2.10**
 *
 * Property 3: For any integer exit code, `classifyExitCause` returns a valid ExitCause enum value,
 * and satisfies:
 * - exit code 0 → NORMAL
 * - exit code 130 → USER_CANCELLED
 * - exit code 137 → KILLED_BY_OS
 * - other exit codes > 128 → CRASH
 * - all other non-zero → CRASH
 */
class ExitCauseClassifierPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 3: Exit code classification determinism")
    )

    test("Feature: android-claude-termux-client, Property 3: Exit code classification determinism") {
        checkAll(PropTestConfig(iterations = 100), Arb.int()) { exitCode ->
            val result = classifyExitCause(exitCode)

            // Result must always be a valid ExitCause enum value
            result shouldBe when {
                exitCode == 0 -> ExitCause.NORMAL
                exitCode == 130 -> ExitCause.USER_CANCELLED
                exitCode == 137 -> ExitCause.KILLED_BY_OS
                exitCode > 128 -> ExitCause.CRASH
                else -> ExitCause.CRASH
            }
        }
    }
})
