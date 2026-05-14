package com.claudemobile.core.domain.bridge

/**
 * Pure function that classifies a process exit code into an [ExitCause].
 *
 * Classification rules:
 * - Exit code 0 → [ExitCause.NORMAL]
 * - Exit code 130 (SIGINT) → [ExitCause.USER_CANCELLED]
 * - Exit code 137 (SIGKILL, typically OOM) → [ExitCause.KILLED_BY_OS]
 * - Exit code > 128 (killed by signal, other) → [ExitCause.CRASH]
 * - All other non-zero codes → [ExitCause.CRASH]
 *
 * This function is intentionally placed in the domain layer as a pure function
 * so that it can be exercised by property-based tests (Property 3).
 */
public fun classifyExitCause(exitCode: Int): ExitCause = when {
    exitCode == 0 -> ExitCause.NORMAL
    exitCode == 130 -> ExitCause.USER_CANCELLED
    exitCode == 137 -> ExitCause.KILLED_BY_OS
    exitCode > 128 -> ExitCause.CRASH
    else -> ExitCause.CRASH
}
