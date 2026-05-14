package com.claudemobile.core.domain.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the bootstrap process for setting up the embedded Linux environment,
 * including extracting the Embedded_Prefix, installing the Ubuntu rootfs,
 * Node.js, and the Claude CLI within the App's private storage.
 */
public interface BootstrapManager {

    /**
     * The current state of the bootstrap process.
     */
    public val bootstrapState: StateFlow<BootstrapState>

    /**
     * A flow of progress updates during bootstrap operations.
     * Emits progress values between 0.0 and 1.0 for the current step.
     */
    public val progressFlow: Flow<BootstrapProgress>

    /**
     * Extracts the Embedded_Prefix from bundled assets or a configured URL
     * into the App's private storage directory.
     *
     * @return Result indicating success or failure with error details
     */
    public suspend fun extract(): Result<Unit>

    /**
     * Verifies that all required components are present and executable:
     * - Embedded_Prefix is extracted with correct permissions
     * - Proot_Env (Ubuntu rootfs) is installed
     * - Node.js and Claude CLI are available inside Proot_Env
     *
     * @return Result indicating verification success or specific failure
     */
    public suspend fun verify(): Result<VerificationResult>

    /**
     * Runs a health check to determine the installation status and version
     * of all components (Embedded_Prefix, Proot_Env, Node.js, Claude CLI).
     */
    public suspend fun healthCheck(): HealthCheckResult

    /**
     * Executes the full bootstrap sequence, reporting progress for each step.
     * Calls extract → verify → install missing components in order.
     */
    public suspend fun bootstrap(): Result<Unit>

    /**
     * Returns true if the environment is fully set up and ready to run Claude CLI.
     */
    public suspend fun isReady(): Boolean
}

/**
 * Progress information for a bootstrap operation.
 */
public data class BootstrapProgress(
    /**
     * The current step being executed.
     */
    val step: BootstrapStep,

    /**
     * Progress fraction for the current step (0.0 to 1.0).
     */
    val fraction: Float,

    /**
     * Human-readable description of current activity.
     */
    val message: String
)

/**
 * The result of a verification check.
 */
public data class VerificationResult(
    val prefixExtracted: Boolean,
    val rootfsInstalled: Boolean,
    val nodeInstalled: Boolean,
    val claudeCliInstalled: Boolean
) {
    /**
     * Returns true if all components are verified.
     */
    public val allVerified: Boolean
        get() = prefixExtracted && rootfsInstalled &&
                nodeInstalled && claudeCliInstalled
}

/**
 * The result of a health check, reporting the status and version of each component.
 */
public data class HealthCheckResult(
    val prefixInstalled: Boolean,
    val prefixVersion: String?,
    val rootfsInstalled: Boolean,
    val rootfsDistro: String?,
    val nodeVersion: String?,
    val claudeCliVersion: String?,
    val storageUsedBytes: Long,
    val storageAvailableBytes: Long
)

/**
 * Individual steps in the bootstrap sequence.
 */
public enum class BootstrapStep {
    EXTRACT_PREFIX,
    VERIFY_PREFIX,
    /**
     * Extracts the pre-baked Ubuntu rootfs (already containing Node.js + Claude CLI)
     * from APK assets into the App's private storage. There is no online install path:
     * if no rootfs tarball ships for the device's ABI, this step fails immediately.
     */
    INSTALL_ROOTFS,
    /** Step 6 (ai-provider-presets R11.4): checks that at least one Provider_Profile exists. */
    CONFIGURE_PROVIDER,
    VERIFY_ALL,
    COMPLETE
}

/**
 * The overall state of the bootstrap process.
 */
public sealed interface BootstrapState {

    /**
     * Bootstrap has not been started.
     */
    public data object NotStarted : BootstrapState

    /**
     * Bootstrap is currently executing a step.
     */
    public data class InProgress(
        val step: BootstrapStep,
        val progress: Float,
        val message: String
    ) : BootstrapState

    /**
     * Bootstrap completed successfully; the environment is ready.
     */
    public data object Ready : BootstrapState

    /**
     * Bootstrap failed at a specific step.
     */
    public data class Failed(
        val step: BootstrapStep,
        val error: String,
        val requiredSpaceBytes: Long? = null,
        val availableSpaceBytes: Long? = null
    ) : BootstrapState
}
