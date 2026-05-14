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
import com.claudemobile.core.domain.repository.CredentialStore
import javax.inject.Inject

/**
 * Spawns the Claude CLI process inside the proot environment for a given
 * workspace path. If the bridge already has a running process, this is a no-op.
 *
 * The use case:
 * 1. Checks if the bridge already has a running process (idempotent).
 * 2. Retrieves the API key from the credential store.
 * 3. Builds a [SpawnConfig] that launches proot → claude inside the rootfs.
 * 4. Calls [CliBridge.spawn] to fork the process via the JNI PTY interface.
 *
 * Requirements: 2.1, 2.2, 2.3, 6.4
 */
public class SpawnCliUseCase @Inject constructor(
    private val cliBridge: CliBridge,
    private val credentialStore: CredentialStore,
    private val prootEnvironmentProvider: ProotEnvironmentProvider,
) {
    /**
     * Spawns the Claude CLI for the given [workspacePath].
     *
     * @param workspacePath Absolute path on the host filesystem to bind-mount
     *                      as `/workspace` inside proot.
     * @return [AppResult.Success] with the [ProcessHandle] if spawned (or already running),
     *         or [AppResult.Failure] if the API key is missing or spawn fails.
     */
    public suspend operator fun invoke(workspacePath: String): AppResult<ProcessHandle> {
        // Idempotent: if already running, return immediately.
        if (cliBridge.processState.value == ProcessState.RUNNING) {
            return ProcessHandle(pid = -1, startedAt = java.time.Instant.now()).asSuccess()
        }

        val apiKey = credentialStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return AppError(
                message = "No API key configured. Please add your Anthropic API key in Settings.",
                code = ErrorCode.PERMISSION_DENIED,
            ).asFailure()
        }

        val config = prootEnvironmentProvider.buildSpawnConfig(
            workspacePath = workspacePath,
            apiKey = apiKey,
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
