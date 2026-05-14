package com.claudemobile.core.bridge.bootstrap

import java.io.File

/**
 * Extracts the Embedded_Prefix from APK assets into the application's private storage.
 *
 * The Embedded_Prefix is a minimal Termux-compatible Linux filesystem containing
 * essential binaries (sh, ls, cat, etc.), shared libraries, and configuration files
 * needed to run proot and the Ubuntu rootfs.
 */
public interface PrefixExtractor {

    /**
     * Extracts the Embedded_Prefix from APK assets to the target directory.
     *
     * @param targetDir The target directory (app internal storage) where the prefix will be extracted
     * @param onProgress Progress callback emitting values from 0.0 to 1.0
     * @return Result containing the extracted [PrefixVersion] on success, or an error on failure
     */
    public suspend fun extract(
        targetDir: File,
        onProgress: (Float) -> Unit
    ): Result<PrefixVersion>

    /**
     * Checks whether the prefix needs to be upgraded by comparing the currently
     * installed version against the version bundled in the APK.
     *
     * @param currentVersion The currently installed version (from .version file)
     * @param bundledVersion The version bundled in the current APK
     * @return true if an upgrade is needed
     */
    public fun needsUpgrade(currentVersion: PrefixVersion, bundledVersion: PrefixVersion): Boolean

    /**
     * Returns the version bundled in the current APK assets.
     *
     * @return The bundled [PrefixVersion], or null if not available
     */
    public fun getBundledVersion(): PrefixVersion?
}
