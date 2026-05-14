package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.ProcessHandle
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.bridge.SpawnConfig
import javax.inject.Inject

/**
 * Spawns the Claude CLI process inside the proot environment for a given
 * workspace path. If the bridge already has a running process, this is a no-op.
 *
 * The use case:
 * 1. Checks if the bridge already has a running process (idempotent).
 * 2. Builds a [SpawnConfig] that launches proot → claude inside the rootfs.
 * 3. Calls [CliBridge.spawn] to fork the process via the JNI PTY interface.
 *
 * Note: API key / auth token injection is handled by [SpawnEnvAdapter] inside
 * [CliBridge.spawn], which reads the Active_Profile at spawn time (R6.1).
 * This use case no longer checks credentials itself — if no profile is active,
 * the bridge will emit [BridgeError.NoActiveProfile] (R6.8).
 *
 * Requirements: 2.1, 2.2, 2.3, 6.4
 */
public class SpawnCliUseCase @Inject constructor(
    private val cliBridge: CliBridge,
    private val prootEnvironmentProvider: ProotEnvironmentProvider,
) {
    /**
     * Spawns the Claude CLI for the given [workspacePath].
     *
     * @param workspacePath Absolute path on the host filesystem to bind-mount
     *                      as `/workspace` inside proot.
     * @return [AppResult.Success] with the [ProcessHandle] if spawned (or already running),
     *         or [AppResult.Failure] if no active profile is set or spawn fails.
     */
    public suspend operator fun invoke(workspacePath: String): AppResult<ProcessHandle> {
        // Idempotent: if already running, return immediately.
        if (cliBridge.processState.value == ProcessState.RUNNING) {
            return ProcessHandle(pid = -1, startedAt = java.time.Instant.now()).asSuccess()
        }

        // Build the spawn config. The apiKey placeholder below is overwritten
        // by SpawnEnvAdapter inside CliBridge.spawn() with the real value from
        // the Active_Profile. We pass a non-empty placeholder so the config
        // builder doesn't trip on blank-string guards.
        val config = prootEnvironmentProvider.buildSpawnConfig(
            workspacePath = workspacePath,
            apiKey = "PLACEHOLDER_OVERWRITTEN_BY_SPAWN_ENV_ADAPTER",
        )

        return try {
            val result = cliBridge.spawn(config)
            result.fold(
                onSuccess = { handle -> handle.asSuccess() },
                onFailure = { error ->
                    AppError(
                        message = error.message ?: "Failed to spawn CLI process",
                        code = ErrorCode.PROCESS_ERROR,
                        cause = error,
                    ).asFailure()
                },
            )
        } catch (e: Exception) {
            AppError(
                message = e.message ?: "Failed to spawn CLI process",
                code = ErrorCode.PROCESS_ERROR,
                cause = e,
            ).asFailure()
        }
    }
}

/**
 * Provides the proot environment configuration needed to build a [SpawnConfig].
 * Implemented in the bridge layer where filesystem paths are known.
 */
public interface ProotEnvironmentProvider {
    /**
     * Builds a [SpawnConfig] that will launch `claude` inside the proot
     * Ubuntu environment with the given workspace mounted.
     */
    public fun buildSpawnConfig(workspacePath: String, apiKey: String): SpawnConfig
}
