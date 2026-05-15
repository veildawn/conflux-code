package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured result of a rootfs integrity check.
 *
 * Each field represents a critical aspect of the rootfs environment.
 * The environment is considered complete only when all three checks pass.
 */
public data class IntegrityResult(
    /** Whether the core directory structure (/etc, /bin, /usr) exists. */
    val directoryStructureValid: Boolean,
    /** Whether a Node.js binary is present. */
    val nodeInstalled: Boolean,
    /** Whether the Claude CLI binary is present. */
    val claudeCliInstalled: Boolean,
) {
    /** `true` only when all integrity checks pass. */
    val isComplete: Boolean
        get() = directoryStructureValid && nodeInstalled && claudeCliInstalled
}

/**
 * Performs integrity checks on the rootfs environment.
 *
 * When the version marker file is missing but the rootfs directory exists,
 * this checker determines whether the environment is actually complete and
 * usable — avoiding an unnecessary full re-extraction that takes minutes.
 */
@Singleton
public class RootfsIntegrityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val claudeCliDetector: ClaudeCliDetector,
) {

    public val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * Executes a full integrity check of the rootfs environment.
     *
     * Verifies:
     * - Core directory structure (`etc`, `bin`, `usr`)
     * - Node.js binary availability
     * - Claude CLI binary availability (delegated to [ClaudeCliDetector])
     *
     * @return an [IntegrityResult] describing the state of each component
     */
    public fun check(): IntegrityResult {
        val hasEtc = File(rootfsDir, "etc").isDirectory
        val hasBin = File(rootfsDir, "bin").isDirectory
        val hasUsr = File(rootfsDir, "usr").isDirectory
        val hasNode = File(rootfsDir, "usr/bin/node").exists() ||
            File(rootfsDir, "usr/local/bin/node").exists()
        val hasClaude = claudeCliDetector.isInstalled()

        return IntegrityResult(
            directoryStructureValid = hasEtc && hasBin && hasUsr,
            nodeInstalled = hasNode,
            claudeCliInstalled = hasClaude,
        )
    }

    /**
     * Recovers the version marker file when integrity check passes but the
     * marker is missing.
     *
     * Writes a marker with `version=unknown-recovered` and
     * `source=integrity-check` to distinguish recovered state from a
     * normal installation.
     */
    public fun recoverVersionMarker() {
        val marker = File(rootfsDir, ".claudemobile-bundled-version")
        marker.writeText("version=unknown-recovered\nsource=integrity-check\n")
    }
}
