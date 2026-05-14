package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import android.os.Build
import com.claudemobile.core.common.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts the Termux bootstrap prefix into the app's private storage.
 *
 * The bootstrap is shipped inside assets as `prefix/bootstrap-<abi>.zip` (wired
 * up by the `prepareTermuxBootstrap` gradle task). Its format is the standard
 * Termux bootstrap:
 *   - regular files and directories under `bin/`, `lib/`, `etc/`, `usr/`, …
 *   - a top-level `SYMLINKS.txt` describing symlinks in `target←linkname`
 *     lines (with a unicode left-arrow separator).
 *
 * A few exec binaries from `bin/` (currently `bash` and `dash`) are NOT
 * extracted from the zip: they have already been placed in the app's native
 * library directory as `lib<name>.so` at build time, because Android 10+
 * blocks exec() on files that live under `/data/data/<pkg>/files/`. The
 * extractor creates symlinks inside the prefix (`bin/bash`, `bin/sh`,
 * `bin/dash`) pointing at `applicationInfo.nativeLibraryDir` so that both
 * runtime paths resolve to the real binary.
 */
@Singleton
internal class PrefixExtractorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : PrefixExtractor {

    override suspend fun extract(
        targetDir: File,
        onProgress: (Float) -> Unit,
    ): Result<PrefixVersion> = withContext(dispatchers.io) {
        try {
            onProgress(0.0f)

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return@withContext Result.failure(
                    BootstrapException(
                        step = com.claudemobile.core.domain.bridge.BootstrapStep.EXTRACT_PREFIX,
                        message = "Failed to create prefix directory: ${targetDir.absolutePath}"
                    )
                )
            }

            val availableSpace = targetDir.usableSpace
            if (availableSpace < MINIMUM_REQUIRED_SPACE_BYTES) {
                return@withContext Result.failure(
                    InsufficientStorageException(
                        requiredBytes = MINIMUM_REQUIRED_SPACE_BYTES,
                        availableBytes = availableSpace,
                    )
                )
            }

            // Clean any previous extraction so we start from a known state.
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
                targetDir.mkdirs()
            }

            val bootstrapAssetPath = resolveBootstrapAssetPath()
                ?: return@withContext Result.failure(
                    BootstrapException(
                        step = com.claudemobile.core.domain.bridge.BootstrapStep.EXTRACT_PREFIX,
                        message = "No Termux bootstrap zip bundled for this device's ABIs " +
                            "(${supportedAbis().joinToString()}). This usually means the " +
                            "`prepareTermuxBootstrap` gradle task did not run at build time."
                    )
                )

            onProgress(0.05f)

            val hashDigest = MessageDigest.getInstance("SHA-256")
            val extractedFiles = mutableListOf<File>()
            var symlinksPayload: String? = null

            // Extract the bootstrap zip. We don't know total entry count up-front
            // without a second pass, so we report progress in a fixed range.
            context.assets.open(bootstrapAssetPath).use { rawInput ->
                ZipInputStream(rawInput).use { zin ->
                    var entry = zin.nextEntry
                    var index = 0
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (entry != null) {
                        val name = entry.name
                        when {
                            entry.isDirectory -> {
                                File(targetDir, name).mkdirs()
                            }
                            name == SYMLINKS_FILENAME -> {
                                val sink = java.io.ByteArrayOutputStream()
                                copyStream(zin, sink, buffer, hashDigest)
                                symlinksPayload = sink.toString(Charsets.UTF_8)
                            }
                            shouldSkipFromZip(name) -> {
                                // Drain the entry but do not materialise it:
                                // the binary lives in nativeLibraryDir and will
                                // be symlinked back into `bin/` below.
                                drainStream(zin, buffer, hashDigest)
                            }
                            else -> {
                                val outFile = File(targetDir, name)
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { output ->
                                    copyStream(zin, output, buffer, hashDigest)
                                }
                                extractedFiles += outFile
                            }
                        }
                        zin.closeEntry()
                        index++
                        if (index % PROGRESS_TICK == 0) {
                            onProgress((0.05f + 0.7f * progressRamp(index)).coerceAtMost(0.75f))
                        }
                        entry = zin.nextEntry
                    }
                }
            }

            onProgress(0.80f)

            // Re-create symlinks captured from SYMLINKS.txt.
            symlinksPayload?.let { applySymlinks(targetDir, it) }

            onProgress(0.88f)

            // Wire up the binaries living in nativeLibraryDir so lookups like
            // `<prefix>/bin/bash` still resolve. Android's native library dir
            // allows exec() on API 29+, unlike filesDir.
            installNativeBinarySymlinks(targetDir)

            onProgress(0.90f)

            // Reconstruct versioned shared-library names in prefix/lib/
            // pointing at the sanitized jniLibs files. Without this the
            // dynamic linker cannot satisfy DT_NEEDED entries like
            // `libreadline.so.8` when bash launches.
            materializeSharedLibraryLinks(targetDir)

            onProgress(0.92f)

            ensureDirectoryStructure(targetDir)
            setExecutablePermissions(targetDir)

            val archHash = "sha256:" + hashDigest.digest()
                .joinToString("") { "%02x".format(it) }

            val version = PrefixVersion(
                prefixVersion = getBundledVersionString(),
                extractedAt = Instant.now().toString(),
                archHash = archHash,
            )
            writeVersionFile(targetDir, version)

            onProgress(1.0f)
            Result.success(version)
        } catch (e: InsufficientStorageException) {
            Result.failure(e)
        } catch (e: BootstrapException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                BootstrapException(
                    step = com.claudemobile.core.domain.bridge.BootstrapStep.EXTRACT_PREFIX,
                    message = "Failed to extract prefix: ${e.message}",
                    cause = e,
                )
            )
        }
    }

    override fun needsUpgrade(
        currentVersion: PrefixVersion,
        bundledVersion: PrefixVersion,
    ): Boolean {
        return currentVersion.prefixVersion != bundledVersion.prefixVersion ||
            currentVersion.archHash != bundledVersion.archHash
    }

    override fun getBundledVersion(): PrefixVersion? {
        // We only know the bootstrap version as a plain string at build time;
        // the archive hash is computed post-extraction.
        return PrefixVersion(
            prefixVersion = getBundledVersionString(),
            extractedAt = "",
            archHash = "",
        )
    }

    // ======================================================================
    // Internal helpers
    // ======================================================================

    /**
     * Returns the assets path of the bootstrap zip matching the current
     * device's primary ABI, or `null` if no matching zip is bundled.
     */
    private fun resolveBootstrapAssetPath(): String? {
        val bundled = runCatching { context.assets.list(PREFIX_ASSET_DIR) }
            .getOrNull()
            ?.toSet()
            ?: return null
        for (abi in supportedAbis()) {
            val fileName = "bootstrap-$abi.zip"
            if (fileName in bundled) {
                return "$PREFIX_ASSET_DIR/$fileName"
            }
        }
        return null
    }

    private fun supportedAbis(): List<String> {
        // ABIs are ordered from most preferred to least preferred.
        return Build.SUPPORTED_ABIS.map { abiToBootstrapSuffix(it) }
    }

    /**
     * The Termux bootstrap archives are named with CPU triplet suffixes
     * (aarch64, arm, i686, x86_64) but we write them into assets keyed by the
     * Android ABI string (arm64-v8a, armeabi-v7a, x86, x86_64). This bridges
     * the two.
     */
    private fun abiToBootstrapSuffix(androidAbi: String): String = androidAbi

    private fun shouldSkipFromZip(entryName: String): Boolean {
        // Exec binaries live in nativeLibraryDir as lib<name>.so.
        if (entryName.startsWith("bin/")) {
            val base = entryName.removePrefix("bin/")
            if (base in SHELL_BINARIES_IN_NATIVE_LIB_DIR) return true
        }
        // Shared libraries (lib/*.so, lib/*.so.N, lib/*.so.N.M …) were moved
        // into jniLibs with sanitized names; PrefixExtractor rebuilds the
        // original names as symlinks in prefix/lib/ below.
        if (entryName.startsWith("lib/")) {
            val base = entryName.removePrefix("lib/")
            if (!base.contains('/') && base.contains(".so")) return true
        }
        return false
    }

    private fun applySymlinks(targetDir: File, payload: String) {
        // Format: `target←linkpath` separated by U+2190 (LEFT ARROW).
        for (raw in payload.split('\n')) {
            val line = raw.trim('\r', ' ', '\t')
            if (line.isEmpty()) continue
            val sepIdx = line.indexOf(SYMLINK_SEPARATOR)
            if (sepIdx <= 0 || sepIdx >= line.length - 1) continue
            val target = line.substring(0, sepIdx).trim()
            val linkRel = line.substring(sepIdx + 1).trim()
                .removePrefix("./")
            if (target.isEmpty() || linkRel.isEmpty()) continue

            val linkFile = File(targetDir, linkRel)
            linkFile.parentFile?.mkdirs()
            // Remove any pre-existing file/symlink so we can create ours.
            if (linkFile.exists() || java.nio.file.Files.isSymbolicLink(linkFile.toPath())) {
                try {
                    java.nio.file.Files.delete(linkFile.toPath())
                } catch (_: Exception) { /* best effort */ }
            }
            try {
                java.nio.file.Files.createSymbolicLink(
                    linkFile.toPath(),
                    java.nio.file.Paths.get(target),
                )
            } catch (_: Exception) {
                // If symlink creation fails (rare on Android), fall back to a
                // no-op — downstream components will report missing files.
            }
        }
    }

    /**
     * Creates symlinks inside the prefix that point at the exec ELF binaries
     * shipped in the app's nativeLibraryDir:
     *
     *   prefix/bin/bash          → nativeLibraryDir/libbash.so
     *   prefix/bin/dash          → nativeLibraryDir/libdash.so
     *   prefix/bin/sh            → dash
     *   prefix/bin/proot         → nativeLibraryDir/libproot.so
     *   prefix/libexec/proot/loader    → nativeLibraryDir/libproot-loader.so
     *   prefix/libexec/proot/loader32  → nativeLibraryDir/libproot-loader32.so
     *
     * These are plain absolute symlinks so anything in the prefix that refers
     * to the in-prefix path (such as `<prefix>/bin/proot` invoked by apt,
     * or `<prefix>/libexec/proot/loader` looked up by proot itself) ends up
     * at the real binary on an exec-allowed filesystem.
     */
    private fun installNativeBinarySymlinks(targetDir: File) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val binDir = File(targetDir, "bin").apply { mkdirs() }
        val prootLibexecDir = File(targetDir, "libexec/proot").apply { mkdirs() }

        // bash/dash live in <prefix>/bin
        for (bin in SHELL_BINARIES_IN_NATIVE_LIB_DIR) {
            linkNativeBinary(
                nativeDir = nativeDir,
                jniName = "lib$bin.so",
                linkPath = File(binDir, bin),
            )
        }
        // proot lives in <prefix>/bin/proot
        linkNativeBinary(
            nativeDir = nativeDir,
            jniName = "libproot.so",
            linkPath = File(binDir, "proot"),
        )
        // proot loader stubs live in <prefix>/libexec/proot
        linkNativeBinary(
            nativeDir = nativeDir,
            jniName = "libproot-loader.so",
            linkPath = File(prootLibexecDir, "loader"),
        )
        linkNativeBinary(
            nativeDir = nativeDir,
            jniName = "libproot-loader32.so",
            linkPath = File(prootLibexecDir, "loader32"),
        )

        // sh → dash (matches Termux convention). Done last so it sees the
        // freshly-created dash symlink above.
        val sh = File(binDir, "sh")
        val dash = File(binDir, "dash")
        if (dash.exists() && !sh.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(sh.toPath(), dash.toPath())
            } catch (_: Exception) { /* best effort */ }
        }
    }

    private fun linkNativeBinary(nativeDir: File, jniName: String, linkPath: File) {
        val real = File(nativeDir, jniName)
        if (!real.exists()) return
        linkPath.parentFile?.mkdirs()
        if (linkPath.exists() || java.nio.file.Files.isSymbolicLink(linkPath.toPath())) {
            try { java.nio.file.Files.delete(linkPath.toPath()) } catch (_: Exception) {}
        }
        try {
            java.nio.file.Files.createSymbolicLink(linkPath.toPath(), real.toPath())
        } catch (_: Exception) {
            // Last-resort fallback: hard copy. Will fail for loaders if the
            // filesystem is nosuid/noexec, but keeps the code defensive.
            try { real.copyTo(linkPath, overwrite = true).setExecutable(true, false) }
            catch (_: Exception) {}
        }
    }

    /**
     * Reads the gradle-generated `lib-name-map.txt` and recreates the
     * original (versioned) shared-library names inside `prefix/lib/` as
     * symlinks pointing at the sanitized files in nativeLibraryDir.
     *
     * The map file lives in assets at `$PREFIX_ASSET_DIR/lib-name-map.txt`
     * with TAB-separated lines:
     *     libreadline.so.8.3    libreadline-so-8-3.so
     *
     * This closes the loop between the build-time rename (enforced by the
     * Android packager's `lib*.so` filter) and the runtime linker's search by
     * DT_NEEDED name.
     */
    private fun materializeSharedLibraryLinks(targetDir: File) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libDir = File(targetDir, "lib").apply { mkdirs() }

        val mapStream = try {
            context.assets.open("$PREFIX_ASSET_DIR/$LIB_NAME_MAP_FILE")
        } catch (_: Exception) {
            return
        }

        mapStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val originalName = line.substring(0, tab).trim()
                val jniName = line.substring(tab + 1).trim()
                if (originalName.isEmpty() || jniName.isEmpty()) continue

                val src = File(nativeDir, jniName)
                if (!src.exists()) continue

                val link = File(libDir, originalName)
                link.parentFile?.mkdirs()
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    try { java.nio.file.Files.delete(link.toPath()) } catch (_: Exception) {}
                }
                try {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), src.toPath())
                } catch (_: Exception) {
                    // Last-resort fallback: hard copy (uses ~4× the space but
                    // keeps the linker happy).
                    try { src.copyTo(link, overwrite = true) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun ensureDirectoryStructure(prefixDir: File) {
        val requiredDirs = listOf(
            "bin", "lib", "etc", "usr", "usr/bin", "usr/lib", "tmp", "var", "home"
        )
        for (dir in requiredDirs) {
            File(prefixDir, dir).mkdirs()
        }
    }

    private fun setExecutablePermissions(prefixDir: File) {
        val executableDirs = listOf("bin", "usr/bin", "libexec")
        for (dirName in executableDirs) {
            val dir = File(prefixDir, dirName)
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile) file.setExecutable(true, false)
            }
        }
    }

    private fun getBundledVersionString(): String = FALLBACK_VERSION

    private fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        buffer: ByteArray,
        hash: MessageDigest,
    ) {
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            output.write(buffer, 0, n)
            hash.update(buffer, 0, n)
        }
    }

    private fun drainStream(
        input: java.io.InputStream,
        buffer: ByteArray,
        hash: MessageDigest,
    ) {
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            hash.update(buffer, 0, n)
        }
    }

    /** Maps a rough "entries seen" count to a 0..1 ramp for UI feedback. */
    private fun progressRamp(index: Int): Float {
        // ~3300 entries in the aarch64 bootstrap; cap at 1.0 conservatively.
        val pct = index.toFloat() / 3300f
        return pct.coerceAtMost(1.0f)
    }

    internal companion object {
        const val PREFIX_ASSET_DIR = "prefix"
        const val VERSION_FILE_NAME = ".version"
        const val FALLBACK_VERSION = "termux-bootstrap-2026.05.10"
        const val MINIMUM_REQUIRED_SPACE_BYTES = 200L * 1024 * 1024 // 200 MB

        private const val SYMLINKS_FILENAME = "SYMLINKS.txt"
        private const val SYMLINK_SEPARATOR = '\u2190' // ←
        private const val COPY_BUFFER_SIZE = 32 * 1024
        private const val PROGRESS_TICK = 50
        private const val LIB_NAME_MAP_FILE = "lib-name-map.txt"

        // Must match `execBinariesToExtract` in app/build.gradle.kts — these
        // are the shell binaries the prefix sources from nativeLibraryDir.
        // Proot-related binaries are handled separately via
        // installNativeBinarySymlinks().
        private val SHELL_BINARIES_IN_NATIVE_LIB_DIR: Set<String> = setOf("bash", "dash", "tar", "xz")

        fun writeVersionFile(prefixDir: File, version: PrefixVersion) {
            File(prefixDir, VERSION_FILE_NAME).writeText(version.toJson())
        }

        fun readVersionFile(prefixDir: File): PrefixVersion? {
            val versionFile = File(prefixDir, VERSION_FILE_NAME)
            if (!versionFile.exists()) return null
            return PrefixVersion.fromJson(versionFile.readText())
        }
    }
}

/**
 * Exception thrown when there is insufficient storage space for bootstrap operations.
 */
public class InsufficientStorageException(
    public val requiredBytes: Long,
    public val availableBytes: Long,
) : Exception(
    "Insufficient storage space. Required: ${requiredBytes / (1024 * 1024)} MB, " +
        "Available: ${availableBytes / (1024 * 1024)} MB. " +
        "Please free at least ${(requiredBytes - availableBytes) / (1024 * 1024)} MB to continue."
)
