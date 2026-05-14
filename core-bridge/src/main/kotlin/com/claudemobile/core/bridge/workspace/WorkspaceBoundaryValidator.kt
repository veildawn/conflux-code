package com.claudemobile.core.bridge.workspace

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates that filesystem paths are within the configured workspace boundary.
 *
 * This validator is used to enforce workspace boundary enforcement at the proot mount level.
 * It uses canonical path resolution to prevent path traversal attacks (e.g., ../../).
 *
 * The proot mount configuration restricts Claude_CLI from accessing paths outside
 * the workspace directory. This validator implements the same logic for use in
 * path validation before mount configuration and for testing purposes.
 *
 * Requirements satisfied:
 * - Req 8.6: Restricts access at proot bind-mount boundary
 */
@Singleton
public class WorkspaceBoundaryValidator @Inject constructor() {

    /**
     * The predictable mount point inside proot where the workspace is exposed.
     * This matches the mount point used in [com.claudemobile.core.bridge.cli.ProotConfig].
     */
    public companion object {
        public const val PROOT_MOUNT_POINT: String = "/workspace"
    }

    /**
     * Checks whether the given [targetPath] is within the [workspacePath] boundary.
     *
     * Uses canonical path resolution to normalize both paths, which resolves:
     * - Relative path components (. and ..)
     * - Symbolic links
     * - Redundant separators
     *
     * @param workspacePath The root workspace directory that defines the boundary.
     * @param targetPath The path to validate against the workspace boundary.
     * @return `true` if [targetPath] is the workspace itself or a descendant of it,
     *         `false` otherwise.
     */
    public fun isWithinWorkspace(workspacePath: String, targetPath: String): Boolean {
        if (workspacePath.isBlank() || targetPath.isBlank()) {
            return false
        }

        val canonicalWorkspace = normalizeWorkspacePath(workspacePath)
        val canonicalTarget = normalizePath(targetPath, canonicalWorkspace)

        // The target must either be the workspace itself or start with workspace + separator
        return canonicalTarget == canonicalWorkspace ||
            canonicalTarget.startsWith(canonicalWorkspace + File.separator)
    }

    /**
     * Checks whether the given [targetPath] is within the proot mount point boundary.
     *
     * This validates paths as they appear inside the proot environment (i.e., relative
     * to [PROOT_MOUNT_POINT]). Used to verify that Claude CLI file operations stay
     * within the mounted workspace.
     *
     * @param targetPath The path inside proot to validate.
     * @return `true` if [targetPath] is within the proot workspace mount point.
     */
    public fun isWithinProotMountBoundary(targetPath: String): Boolean {
        if (targetPath.isBlank()) return false

        val normalizedTarget = normalizePath(targetPath, PROOT_MOUNT_POINT)
        val normalizedMount = PROOT_MOUNT_POINT

        return normalizedTarget == normalizedMount ||
            normalizedTarget.startsWith(normalizedMount + File.separator)
    }

    /**
     * Generates the proot bind mount arguments that expose the workspace directory
     * at the predictable [PROOT_MOUNT_POINT] inside the proot environment.
     *
     * The returned arguments configure proot to bind-mount the host workspace directory
     * to `/workspace` inside proot, preventing access to paths outside it.
     *
     * @param workspacePath The host workspace directory to expose inside proot.
     * @return List of proot command-line arguments for bind mounting the workspace.
     */
    public fun generateMountArgs(workspacePath: String): List<String> {
        val canonicalWorkspace = normalizeWorkspacePath(workspacePath)
        return listOf("-b", "$canonicalWorkspace:$PROOT_MOUNT_POINT")
    }

    /**
     * Validates that a workspace path meets the minimum requirements for use:
     * - Non-blank
     * - Absolute path
     * - The directory exists (or can be created)
     *
     * @param workspacePath The path to validate.
     * @return A [ValidationResult] indicating whether the path is valid.
     */
    public fun validateWorkspacePath(workspacePath: String): ValidationResult {
        if (workspacePath.isBlank()) {
            return ValidationResult.Invalid("Workspace path must not be blank.")
        }

        val file = File(workspacePath)
        if (!file.isAbsolute) {
            return ValidationResult.Invalid(
                "Workspace path must be absolute. Got: $workspacePath"
            )
        }

        if (!file.exists()) {
            // Try to create the directory
            return if (file.mkdirs()) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid(
                    "Workspace directory does not exist and could not be created: $workspacePath"
                )
            }
        }

        if (!file.isDirectory) {
            return ValidationResult.Invalid(
                "Workspace path exists but is not a directory: $workspacePath"
            )
        }

        if (!file.canRead()) {
            return ValidationResult.Invalid(
                "Workspace directory is not readable: $workspacePath"
            )
        }

        return ValidationResult.Valid
    }

    /**
     * Normalizes the workspace path to its canonical form.
     * Ensures the path ends without a trailing separator for consistent comparison.
     */
    private fun normalizeWorkspacePath(workspacePath: String): String {
        val canonical = File(workspacePath).canonicalPath
        return canonical.trimEnd(File.separatorChar)
    }

    /**
     * Normalizes a target path to its canonical form.
     * If the target is a relative path, it is resolved against the workspace.
     */
    private fun normalizePath(targetPath: String, resolvedWorkspace: String): String {
        val file = File(targetPath)
        val resolved = if (file.isAbsolute) {
            file
        } else {
            File(resolvedWorkspace, targetPath)
        }
        return resolved.canonicalPath.trimEnd(File.separatorChar)
    }

    /**
     * Result of workspace path validation.
     */
    public sealed interface ValidationResult {
        /** The workspace path is valid and usable. */
        public data object Valid : ValidationResult

        /** The workspace path is invalid with a diagnostic reason. */
        public data class Invalid(val reason: String) : ValidationResult
    }
}
