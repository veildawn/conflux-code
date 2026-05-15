package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether the Claude CLI binary is installed within the rootfs.
 *
 * Handles edge cases around symlinks and hardlinks that can occur in
 * Android's private storage after tar extraction — in particular, dangling
 * symlinks where `File.exists()` returns `false` even though the link itself
 * is present and the target can be resolved within the rootfs tree.
 */
@Singleton
public class ClaudeCliDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    internal val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * Candidate relative paths where the Claude CLI binary may reside
     * inside the rootfs. Checked in order; the first valid hit wins.
     */
    internal val candidatePaths: List<String> = listOf(
        "usr/bin/claude",
        "usr/local/bin/claude",
        "usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe",
        "usr/local/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe",
    )

    /**
     * Returns `true` if any candidate path resolves to a valid Claude CLI
     * binary (regular file or valid symlink with size > 0).
     */
    public fun isInstalled(): Boolean {
        for (rel in candidatePaths) {
            val path = File(rootfsDir, rel).toPath()
            if (checkBinaryExists(path)) return true
        }
        return false
    }

    /**
     * Checks whether a binary exists at [path], correctly handling symlinks.
     *
     * - Regular file or valid symlink: `Files.exists()` && size > 0
     * - Dangling symlink: reads the link target and re-resolves within rootfs
     */
    internal fun checkBinaryExists(path: Path): Boolean {
        try {
            if (Files.exists(path)) {
                // File or valid symlink — check size
                val size = Files.size(path)
                return size > 0L
            }
            // Check if it's a dangling symlink
            if (Files.isSymbolicLink(path)) {
                // Try to resolve within rootfs
                val target = Files.readSymbolicLink(path)
                val resolvedInRootfs = rootfsDir.toPath().resolve(
                    target.toString().removePrefix("/")
                )
                return Files.exists(resolvedInRootfs) &&
                    Files.size(resolvedInRootfs) > 0L
            }
        } catch (_: Exception) {
            // Fall through to false
        }
        return false
    }

    /**
     * Returns a diagnostic string describing the status of each candidate
     * path (exists / missing / dangling symlink / error).
     */
    public fun diagnose(): String {
        return candidatePaths.joinToString(separator = "\n") { rel ->
            val path = File(rootfsDir, rel).toPath()
            val status = try {
                when {
                    java.nio.file.Files.exists(path) -> {
                        val size = java.nio.file.Files.size(path)
                        "exists (size=$size bytes)"
                    }
                    java.nio.file.Files.isSymbolicLink(path) -> {
                        val target = java.nio.file.Files.readSymbolicLink(path)
                        "dangling symlink -> $target"
                    }
                    else -> "not found"
                }
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            "$rel: $status"
        }
    }
}
