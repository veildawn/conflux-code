package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.bridge.BootstrapManager
import com.claudemobile.core.domain.bridge.BootstrapProgress
import com.claudemobile.core.domain.bridge.BootstrapState
import com.claudemobile.core.domain.bridge.BootstrapStep
import com.claudemobile.core.domain.bridge.HealthCheckResult
import com.claudemobile.core.domain.bridge.VerificationResult
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [BootstrapManager] that orchestrates the setup of the
 * self-contained embedded Linux environment.
 *
 * This implementation is fully self-contained and does not depend on any external
 * application (including Termux). All Linux environment components are bundled
 * in the APK assets — there is no network install path. If the device's ABI
 * does not have a matching pre-baked rootfs tarball in `assets/rootfs/`, the
 * bootstrap fails at [BootstrapStep.INSTALL_ROOTFS] with a clear error.
 *
 * Bootstrap sequence:
 * 1. Extract Embedded_Prefix from APK assets
 * 2. Verify shell binary permissions
 * 3. Copy + extract pre-baked Ubuntu rootfs (already contains Node.js + Claude CLI)
 * 4. Final verification
 */
@Singleton
public class BootstrapManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefixExtractor: PrefixExtractor,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val dispatchers: CoroutineDispatchers,
    private val claudeCliDetector: ClaudeCliDetector,
    private val integrityChecker: RootfsIntegrityChecker,
    private val stateCache: BootstrapStateCache,
) : BootstrapManager {

    private val _bootstrapState: MutableStateFlow<BootstrapState> =
        MutableStateFlow(BootstrapState.NotStarted)

    override val bootstrapState: StateFlow<BootstrapState> = _bootstrapState.asStateFlow()

    private val _progressFlow = MutableSharedFlow<BootstrapProgress>(extraBufferCapacity = 16)
    override val progressFlow: Flow<BootstrapProgress> = _progressFlow.asSharedFlow()

    private val prefixDir: File
        get() = File(context.filesDir, PREFIX_DIR_NAME)

    private val rootfsDir: File
        get() = File(context.filesDir, ROOTFS_DIR_NAME)

    override suspend fun extract(): Result<Unit> = withContext(dispatchers.io) {
        try {
            emitProgress(BootstrapStep.EXTRACT_PREFIX, 0.0f, "Checking prefix version...")

            val currentVersion = PrefixExtractorImpl.readVersionFile(prefixDir)
            val bundledVersion = prefixExtractor.getBundledVersion()

            // Check if extraction is needed
            if (currentVersion != null && bundledVersion != null) {
                if (!prefixExtractor.needsUpgrade(currentVersion, bundledVersion)) {
                    emitProgress(BootstrapStep.EXTRACT_PREFIX, 1.0f, "Prefix is up to date")
                    logDiagnostic("Prefix already up to date: ${currentVersion.prefixVersion}")
                    return@withContext Result.success(Unit)
                }
                logDiagnostic("Upgrading prefix from ${currentVersion.prefixVersion} to ${bundledVersion.prefixVersion}")
            }

            emitProgress(BootstrapStep.EXTRACT_PREFIX, 0.1f, "Extracting prefix files...")

            val result = prefixExtractor.extract(prefixDir) { fraction ->
                emitProgress(
                    BootstrapStep.EXTRACT_PREFIX,
                    fraction,
                    "Extracting prefix files... (${(fraction * 100).toInt()}%)"
                )
            }

            result.fold(
                onSuccess = { version ->
                    logDiagnostic("Prefix extracted successfully: ${version.prefixVersion}")
                    emitProgress(BootstrapStep.EXTRACT_PREFIX, 1.0f, "Prefix extraction complete")
                    Result.success(Unit)
                },
                onFailure = { error ->
                    logDiagnostic("Prefix extraction failed: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            logDiagnostic("Prefix extraction error: ${e.message}")
            Result.failure(
                BootstrapException(
                    step = BootstrapStep.EXTRACT_PREFIX,
                    message = "Failed to extract prefix: ${e.message}",
                    cause = e,
                )
            )
        }
    }

    override suspend fun verify(): Result<VerificationResult> = withContext(dispatchers.io) {
        val prefixExtracted = isPrefixExtracted()
        val rootfsInstalled = isRootfsInstalled()
        val nodeInstalled = isNodeInstalled()
        val claudeCliInstalled = isClaudeCliInstalled()

        val result = VerificationResult(
            prefixExtracted = prefixExtracted,
            rootfsInstalled = rootfsInstalled,
            nodeInstalled = nodeInstalled,
            claudeCliInstalled = claudeCliInstalled,
        )

        Result.success(result)
    }

    override suspend fun healthCheck(): HealthCheckResult = withContext(dispatchers.io) {
        val prefixInstalled = isPrefixExtracted()
        val prefixVersion = PrefixExtractorImpl.readVersionFile(prefixDir)?.prefixVersion
        val rootfsInstalled = isRootfsInstalled()
        val rootfsDistro = if (rootfsInstalled) detectRootfsDistro() else null
        val nodeVersion = if (rootfsInstalled) detectNodeVersion() else null
        val claudeCliVersion = if (rootfsInstalled) detectClaudeCliVersion() else null

        val storageUsedBytes = calculateStorageUsed()
        val storageAvailableBytes = context.filesDir.usableSpace

        HealthCheckResult(
            prefixInstalled = prefixInstalled,
            prefixVersion = prefixVersion,
            rootfsInstalled = rootfsInstalled,
            rootfsDistro = rootfsDistro,
            nodeVersion = nodeVersion,
            claudeCliVersion = claudeCliVersion,
            storageUsedBytes = storageUsedBytes,
            storageAvailableBytes = storageAvailableBytes,
        )
    }

    override suspend fun bootstrap(): Result<Unit> = withContext(dispatchers.io) {
        try {
            _bootstrapState.value = BootstrapState.InProgress(
                step = BootstrapStep.EXTRACT_PREFIX,
                progress = 0.0f,
                message = "Starting bootstrap..."
            )
            logDiagnostic("Bootstrap sequence started")

            // Step 1: Extract prefix
            val extractResult = extractPrefix()
            if (extractResult.isFailure) {
                val error = extractResult.exceptionOrNull()!!
                handleBootstrapFailure(BootstrapStep.EXTRACT_PREFIX, error)
                return@withContext Result.failure(error)
            }

            // Step 2: Verify and set binary permissions (shell + native-dir exec binaries)
            val prootResult = setupProot()
            if (prootResult.isFailure) {
                val error = prootResult.exceptionOrNull()!!
                handleBootstrapFailure(BootstrapStep.VERIFY_PREFIX, error)
                return@withContext Result.failure(error)
            }

            // Step 3: Extract pre-baked Ubuntu rootfs (Node.js + Claude CLI already inside)
            val rootfsResult = installRootfsReal()
            if (rootfsResult.isFailure) {
                val error = rootfsResult.exceptionOrNull()!!
                handleBootstrapFailure(BootstrapStep.INSTALL_ROOTFS, error)
                return@withContext Result.failure(error)
            }

            // Step 4: Final verification
            updateState(BootstrapStep.VERIFY_ALL, 0.5f, "Verifying installation...")
            if (!isPrefixExtracted() || !isShellExecutable() || !isRootfsInstalled() || !isClaudeCliInstalled()) {
                val missing = buildString {
                    if (!isPrefixExtracted()) append("prefix, ")
                    if (!isShellExecutable()) append("shell, ")
                    if (!isRootfsInstalled()) append("rootfs, ")
                    if (!isClaudeCliInstalled()) append("claude-cli, ")
                }.trimEnd(',', ' ')
                val error = BootstrapException(
                    step = BootstrapStep.VERIFY_ALL,
                    message = "Verification failed. Missing: $missing"
                )
                handleBootstrapFailure(BootstrapStep.VERIFY_ALL, error)
                return@withContext Result.failure(error)
            }
            emitProgress(BootstrapStep.VERIFY_ALL, 1.0f, "Installation verified")

            // Success
            _bootstrapState.value = BootstrapState.Ready
            emitProgress(BootstrapStep.COMPLETE, 1.0f, "Bootstrap complete")
            logDiagnostic("Bootstrap completed successfully — Claude CLI ready")

            // Persist successful bootstrap state for fast-path on next launch
            val completedVersion = readInstalledRootfsVersion() ?: "unknown"
            stateCache.markBootstrapComplete(completedVersion)

            Result.success(Unit)
        } catch (e: Exception) {
            val step = (_bootstrapState.value as? BootstrapState.InProgress)?.step
                ?: BootstrapStep.EXTRACT_PREFIX
            handleBootstrapFailure(step, e)
            Result.failure(e)
        }
    }

    override suspend fun isReady(): Boolean = withContext(dispatchers.io) {
        val startTime = System.currentTimeMillis()

        // Fast path: check cached state first
        if (stateCache.isBootstrapCachedAsComplete()) {
            val quickValid = quickVerify()
            val elapsed = System.currentTimeMillis() - startTime
            logDiagnostic("isReady() fast path: valid=$quickValid, elapsed=${elapsed}ms")
            if (quickValid) return@withContext true
            // Cache is stale — invalidate and fall through to full check
            stateCache.invalidate()
        }

        // Slow path: full verification
        val result = isPrefixExtracted() && isShellExecutable() &&
            isRootfsInstalled() && isClaudeCliInstalled()
        val elapsed = System.currentTimeMillis() - startTime
        logDiagnostic("isReady() full path: ready=$result, elapsed=${elapsed}ms")
        result
    }

    /**
     * Lightweight verification that checks only file existence — no process
     * execution. Used as the fast path when the state cache indicates a
     * previous successful bootstrap.
     *
     * Checks:
     * 1. Prefix version file exists (proves prefix was extracted)
     * 2. rootfs/etc directory exists (proves rootfs was installed)
     * 3. Claude CLI binary exists (via [claudeCliDetector])
     */
    private fun quickVerify(): Boolean {
        val prefixVersionFile = File(prefixDir, PrefixExtractorImpl.VERSION_FILE_NAME)
        val rootfsEtc = File(rootfsDir, "etc")
        return prefixVersionFile.exists() && rootfsEtc.exists() && claudeCliDetector.isInstalled()
    }

    // ===== Internal bootstrap steps =====

    private suspend fun extractPrefix(): Result<Unit> {
        updateState(BootstrapStep.EXTRACT_PREFIX, 0.0f, "Extracting embedded prefix...")
        return extract()
    }

    private suspend fun setupProot(): Result<Unit> {
        updateState(BootstrapStep.VERIFY_PREFIX, 0.0f, "Setting up prefix binaries...")
        emitProgress(BootstrapStep.VERIFY_PREFIX, 0.1f, "Setting permissions on all binaries...")

        // chmod +x every regular file under the prefix's bin/ and usr/bin/.
        // (The canonical exec binaries for milestone 1 — bash/dash — live in
        // nativeLibraryDir and are already executable; we just want to make
        // sure that any supporting prefix tools are runnable too.)
        val binaryDirs = listOf(
            File(prefixDir, "bin"),
            File(prefixDir, "usr/bin"),
        )

        var totalBinaries = 0
        var permissionsSetCount = 0

        for (dir in binaryDirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (!file.isFile) continue
                    totalBinaries++
                    val success = file.setExecutable(true, false) &&
                        file.setReadable(true, false) &&
                        file.setWritable(true, true)
                    if (success) permissionsSetCount++
                }
            }
        }

        emitProgress(BootstrapStep.VERIFY_PREFIX, 0.5f, "Verifying shell binary...")

        // Milestone 1 only requires a working shell. We locate it in the
        // native library dir (exec-allowed) and execute `--version` to prove
        // the dynamic linker can run it.
        val shellProbe = probeShellExecutable()
        if (shellProbe.isFailure) {
            return Result.failure(shellProbe.exceptionOrNull()!!)
        }

        emitProgress(
            BootstrapStep.VERIFY_PREFIX,
            1.0f,
            "Binaries ready ($permissionsSetCount/$totalBinaries) — shell: ${shellProbe.getOrNull()}",
        )
        logDiagnostic(
            "Prefix permissions set $permissionsSetCount/$totalBinaries; " +
                "shell probe returned: ${shellProbe.getOrNull()}"
        )
        return Result.success(Unit)
    }

    private suspend fun installRootfsReal(): Result<Unit> {
        updateState(BootstrapStep.INSTALL_ROOTFS, 0.0f, "Checking Ubuntu rootfs...")

        // The rootfs ships pre-baked in the APK assets. There is no online
        // install path: every supported ABI must have a tarball generated
        // by app/build-support/build-rootfs.sh.
        val bundled = resolveBundledRootfsAsset()
        if (bundled == null) {
            val abis = android.os.Build.SUPPORTED_ABIS.joinToString(", ")
            return Result.failure(
                BootstrapException(
                    step = BootstrapStep.INSTALL_ROOTFS,
                    message = "No pre-baked rootfs ships for this device's ABI(s): $abis. " +
                        "Rebuild the APK with a matching rootfs-<abi>.tar.xz under " +
                        "app/build-support/prebuilt-rootfs/."
                )
            )
        }
        val manifestEntry = readManifestEntry(bundled.substringAfterLast('/'))

        // Reinstall when the on-disk rootfs is from an older bundled version
        // than the APK ships. Otherwise an old rootfs (e.g. with a broken
        // claude.exe symlink shipped before the build-rootfs CI fix) would
        // satisfy isRootfsInstalled() and short-circuit the upgrade.
        val installedVersion = readInstalledRootfsVersion()
        val bundledVersion = manifestEntry?.version
        val versionMismatch =
            bundledVersion != null && installedVersion != null && installedVersion != bundledVersion
        val versionMissing = installedVersion == null && rootfsDir.exists()

        // When version marker is missing but rootfs directory exists, run
        // integrity check before deciding whether to wipe and re-extract.
        if (versionMissing) {
            val integrityResult = integrityChecker.check()
            if (integrityResult.isComplete) {
                logDiagnostic(
                    "Version marker missing but integrity check passed " +
                        "(dirs=${integrityResult.directoryStructureValid}, " +
                        "node=${integrityResult.nodeInstalled}, " +
                        "claude=${integrityResult.claudeCliInstalled}); " +
                        "recovering marker and skipping extraction"
                )
                integrityChecker.recoverVersionMarker()
                emitProgress(BootstrapStep.INSTALL_ROOTFS, 1.0f, "Rootfs verified via integrity check")
                return Result.success(Unit)
            }
            logDiagnostic(
                "Version marker missing and integrity check failed " +
                    "(dirs=${integrityResult.directoryStructureValid}, " +
                    "node=${integrityResult.nodeInstalled}, " +
                    "claude=${integrityResult.claudeCliInstalled}); " +
                    "proceeding with full extraction"
            )
            wipeRootfsDir()
        }

        if (isRootfsInstalled() && isClaudeCliInstalled() && !versionMismatch && !versionMissing) {
            emitProgress(BootstrapStep.INSTALL_ROOTFS, 1.0f, "Rootfs already installed")
            logDiagnostic(
                "Rootfs already installed (version=${installedVersion ?: "unknown"}); " +
                    "skipping extraction"
            )
            return Result.success(Unit)
        }

        if (versionMismatch) {
            logDiagnostic(
                "Rootfs upgrade required: installed=${installedVersion ?: "<missing>"}, " +
                    "bundled=${bundledVersion ?: "<unknown>"}; verifying asset before wipe"
            )
            // Verify the new rootfs asset is accessible before wiping the existing installation.
            // This prevents data loss if the asset is missing or unreadable.
            val assetAccessible = runCatching {
                context.assets.open(bundled).close()
            }.isSuccess
            if (!assetAccessible) {
                logDiagnostic(
                    "Cannot access bundled rootfs asset '$bundled' for upgrade " +
                        "from ${installedVersion ?: "<missing>"} to ${bundledVersion ?: "<unknown>"}; " +
                        "aborting upgrade to preserve existing rootfs"
                )
                return Result.failure(
                    BootstrapException(
                        step = BootstrapStep.INSTALL_ROOTFS,
                        message = "Cannot access bundled rootfs asset before upgrade " +
                            "(installed=${installedVersion ?: "<missing>"}, " +
                            "bundled=${bundledVersion ?: "<unknown>"}); aborting wipe",
                    )
                )
            }
            logDiagnostic(
                "Asset verified accessible; wiping rootfs for upgrade " +
                    "from ${installedVersion ?: "<missing>"} to ${bundledVersion ?: "<unknown>"}"
            )
            wipeRootfsDir()
        }

        val availableSpace = context.filesDir.usableSpace
        if (availableSpace < ROOTFS_MINIMUM_SPACE_BYTES) {
            return Result.failure(
                InsufficientStorageException(
                    requiredBytes = ROOTFS_MINIMUM_SPACE_BYTES,
                    availableBytes = availableSpace,
                )
            )
        }

        return installRootfsFromBundledAsset(bundled, manifestEntry)
    }

    /**
     * Reads `/.claudemobile-bundled-version` written into the rootfs by
     * `build-rootfs.sh` and returns the `version=` line value, or `null`
     * when the marker is absent (e.g. an older rootfs from before this
     * marker existed, or the directory was never extracted).
     */
    private fun readInstalledRootfsVersion(): String? {
        val marker = File(rootfsDir, ".claudemobile-bundled-version")
        if (!marker.exists()) return null
        return runCatching {
            marker.readLines()
                .firstOrNull { it.startsWith("version=") }
                ?.removePrefix("version=")
                ?.trim()
        }.getOrNull()
    }

    /**
     * Recursively deletes [rootfsDir]. Used before extracting an upgraded
     * tarball so file-tree leftovers (e.g. dangling symlinks) from the
     * previous rootfs cannot mask issues in the new one.
     */
    private fun wipeRootfsDir() {
        if (!rootfsDir.exists()) return
        rootfsDir.deleteRecursively()
    }

    /**
     * Extracts the pre-baked rootfs from [assetPath] into [rootfsDir]. The
     * asset is an xz-compressed tar produced by
     * `app/build-support/build-rootfs.sh`.
     *
     * The progress bar is split into three real, observable phases:
     *   - 0%–30%  copy the asset bytes into cacheDir (driven by AssetFileDescriptor length)
     *   - 30%–95% extract the tarball (driven by either the manifest's recorded
     *             file count or, as a fallback, the byte counter from `xz` if a
     *             file count is not available)
     *   - 95%–100% configure DNS and finalize
     */
    private suspend fun installRootfsFromBundledAsset(
        assetPath: String,
        manifestEntry: RootfsManifestEntry?,
    ): Result<Unit> {
        emitProgress(BootstrapStep.INSTALL_ROOTFS, 0.0f, "Preparing bundled rootfs…")
        val tempFile = File(context.cacheDir, "rootfs_bundled.tar.xz")
        try {
            // ---- Phase 1: copy asset to cacheDir (0%–30%) ----
            val totalAssetBytes = runCatching { context.assets.openFd(assetPath).length }
                .getOrDefault(-1L)
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var copied = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (totalAssetBytes > 0) {
                            val pct = (copied.toFloat() / totalAssetBytes.toFloat()).coerceIn(0f, 1f)
                            val pctInt = (pct * 100).toInt()
                            if (pctInt != lastReported) {
                                lastReported = pctInt
                                emitProgress(
                                    BootstrapStep.INSTALL_ROOTFS,
                                    PHASE_COPY_START + (PHASE_COPY_END - PHASE_COPY_START) * pct,
                                    "Copying bundled rootfs… ($pctInt%)"
                                )
                            }
                        }
                    }
                }
            }
            emitProgress(BootstrapStep.INSTALL_ROOTFS, PHASE_COPY_END, "Copy complete; extracting…")

            // ---- Phase 2: extract tarball (30%–95%) ----
            rootfsDir.mkdirs()

            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val tarBin = File(nativeDir, "libtar.so")
            val libPath = "${File(prefixDir, "lib").absolutePath}:${nativeDir.absolutePath}"

            val totalFiles = manifestEntry?.fileCount ?: 0
            val totalBytes = manifestEntry?.uncompressedBytes ?: 0L

            // `tar -v` prints one filename per extracted entry to stdout;
            // `tar --totals=...` would only fire at the very end. The verbose
            // output is the cheapest source of fine-grained progress.
            val pb = ProcessBuilder(
                tarBin.absolutePath,
                "--no-same-owner",
                "-xJvf", tempFile.absolutePath,
                "-C", rootfsDir.absolutePath,
            )
            pb.environment()["LD_LIBRARY_PATH"] = libPath
            // tar shells out to `xz` for -J. The shipped xz ELF lives in
            // nativeLibraryDir as libxz.so; PrefixExtractor symlinks it back
            // to `<prefix>/bin/xz`, so we point PATH at that directory.
            pb.environment()["PATH"] = listOf(
                File(prefixDir, "bin").absolutePath,
                nativeDir.absolutePath,
                "/system/bin",
            ).joinToString(":")
            pb.redirectErrorStream(true)
            val process = pb.start()

            val sink = StringBuilder()
            var entriesSeen = 0
            var lastReportedPct = -1
            // Stream tar's verbose output line-by-line. Each non-blank line
            // is one extracted entry; we use that as the progress denominator.
            process.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    sink.append(raw).append('\n')
                    val name = raw.trim()
                    if (name.isEmpty()) continue
                    entriesSeen++

                    val phaseFraction = if (totalFiles > 0) {
                        (entriesSeen.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
                    } else {
                        // No manifest: fall back to a logarithmic estimate that
                        // approaches but never reaches 1.0 so the bar always
                        // moves forward without overshooting.
                        val estimated = entriesSeen.toFloat() / EXTRACT_ESTIMATED_FILES.toFloat()
                        estimated.coerceIn(0f, 0.99f)
                    }
                    val overall = PHASE_EXTRACT_START +
                        (PHASE_EXTRACT_END - PHASE_EXTRACT_START) * phaseFraction
                    val pctInt = (overall * 100).toInt()

                    // Throttle: only emit when the percentage actually changes.
                    if (pctInt != lastReportedPct) {
                        lastReportedPct = pctInt
                        val message = if (totalFiles > 0) {
                            "Extracting rootfs… ($entriesSeen / $totalFiles)"
                        } else {
                            "Extracting rootfs… ($entriesSeen files)"
                        }
                        emitProgress(BootstrapStep.INSTALL_ROOTFS, overall, message)
                    }
                }
            }

            val exitCode = process.waitFor()
            val output = sink.toString()
            if (exitCode != 0 && !output.contains("Cannot hard link")) {
                return Result.failure(
                    BootstrapException(
                        step = BootstrapStep.INSTALL_ROOTFS,
                        message = "Failed to extract bundled rootfs (exit $exitCode): " +
                            output.lines().takeLast(20).joinToString("\n")
                    )
                )
            }

            if (totalFiles > 0 && entriesSeen < totalFiles) {
                logDiagnostic(
                    "Bundled rootfs extracted with fewer entries than manifest: " +
                        "$entriesSeen < $totalFiles (uncompressed=$totalBytes bytes)"
                )
            }

            // ---- Phase 3: configure (95%–100%) ----
            emitProgress(BootstrapStep.INSTALL_ROOTFS, PHASE_EXTRACT_END, "Configuring rootfs…")
            File(rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
            emitProgress(BootstrapStep.INSTALL_ROOTFS, 1.0f, "Rootfs installed (bundled)")
            logDiagnostic(
                "Bundled rootfs extracted from $assetPath: " +
                    "entries=$entriesSeen, manifestFiles=$totalFiles, " +
                    "manifestBytes=$totalBytes"
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(
                BootstrapException(
                    step = BootstrapStep.INSTALL_ROOTFS,
                    message = "Failed to install bundled rootfs: ${e.message}",
                    cause = e,
                )
            )
        } finally {
            tempFile.delete()
        }
    }

    /**
     * One row of the rootfs manifest copied into `assets/rootfs/manifest.tsv`
     * by `bundlePrebuiltRootfs`. Columns: abi, version, sha256, compressed_size,
     * uncompressed_size, file_count.
     */
    private data class RootfsManifestEntry(
        val abi: String,
        val version: String,
        val sha256: String,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
        val fileCount: Int,
    )

    /**
     * Looks up the manifest row matching [tarballName] (e.g.
     * `rootfs-arm64-v8a.tar.xz`). Returns `null` when the manifest is absent
     * or does not contain a row for this tarball — extraction still works,
     * progress just falls back to the logarithmic estimator.
     */
    private fun readManifestEntry(tarballName: String): RootfsManifestEntry? {
        val abi = tarballName.removePrefix("rootfs-").removeSuffix(".tar.xz")
        return runCatching {
            context.assets.open("$ROOTFS_ASSET_DIR/manifest.tsv").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val parts = raw.trim().split('\t')
                    if (parts.isEmpty() || parts[0] != abi) continue
                    val version = parts.getOrNull(1).orEmpty()
                    val sha = parts.getOrNull(2).orEmpty()
                    val compressed = parts.getOrNull(3)?.toLongOrNull() ?: -1L
                    val uncompressed = parts.getOrNull(4)?.toLongOrNull() ?: -1L
                    val files = parts.getOrNull(5)?.toIntOrNull() ?: -1
                    return@useLines RootfsManifestEntry(
                        abi = abi,
                        version = version,
                        sha256 = sha,
                        compressedBytes = compressed,
                        uncompressedBytes = uncompressed,
                        fileCount = files.coerceAtLeast(0),
                    )
                }
                null
            }
        }.getOrNull()
    }

    /**
     * Returns the `rootfs/rootfs-<abi>.tar.xz` asset path matching the
     * device's primary supported ABI, or `null` when no bundled rootfs ships
     * for any of the device's ABIs. Bootstrap fails immediately when this
     * returns `null` — there is no online install path.
     */
    private fun resolveBundledRootfsAsset(): String? {
        val contents = runCatching { context.assets.list(ROOTFS_ASSET_DIR) }
            .getOrNull()
            ?.toSet()
            ?: return null
        for (abi in android.os.Build.SUPPORTED_ABIS) {
            val candidate = "rootfs-$abi.tar.xz"
            if (candidate in contents) {
                return "$ROOTFS_ASSET_DIR/$candidate"
            }
        }
        return null
    }

    // ===== Verification helpers =====

    private fun isPrefixExtracted(): Boolean {
        val versionFile = File(prefixDir, PrefixExtractorImpl.VERSION_FILE_NAME)
        return prefixDir.exists() && versionFile.exists() &&
                PrefixExtractorImpl.readVersionFile(prefixDir) != null
    }

    /**
     * Returns true if the bash binary shipped in `nativeLibraryDir` can be
     * exec'd. This is the milestone-1 sanity check that exercises the
     * assets → jniLibs → nativeLibraryDir → exec() pipeline end-to-end.
     */
    private fun isShellExecutable(): Boolean {
        return probeShellExecutable().isSuccess
    }

    /**
     * Executes `bash --version` from [nativeLibraryDir] and returns the first
     * line of output. Any failure (missing binary, non-zero exit, I/O error)
     * is wrapped in a [BootstrapException] with enough context to diagnose
     * packaging regressions.
     *
     * The dynamic linker resolves DT_NEEDED names through LD_LIBRARY_PATH,
     * and prefix/lib is populated with symlinks to nativeLibraryDir (see
     * PrefixExtractor.materializeSharedLibraryLinks).
     */
    private fun probeShellExecutable(): Result<String> {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val bash = File(nativeDir, "libbash.so")
        if (!bash.exists()) {
            return Result.failure(
                BootstrapException(
                    step = BootstrapStep.VERIFY_PREFIX,
                    message = "Shell binary not found in nativeLibraryDir: ${bash.absolutePath}"
                )
            )
        }
        val libSearchPath = listOf(
            File(prefixDir, "lib").absolutePath,
            nativeDir.absolutePath,
        ).joinToString(":")

        return try {
            val pb = ProcessBuilder(bash.absolutePath, "--version")
                .redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = libSearchPath
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readLine().orEmpty()
            val exit = process.waitFor()
            if (exit != 0) {
                Result.failure(
                    BootstrapException(
                        step = BootstrapStep.VERIFY_PREFIX,
                        message = "Shell probe '${bash.absolutePath} --version' " +
                            "(LD_LIBRARY_PATH=$libSearchPath) exited $exit; " +
                            "first line: '$output'"
                    )
                )
            } else {
                Result.success(output)
            }
        } catch (e: Exception) {
            Result.failure(
                BootstrapException(
                    step = BootstrapStep.VERIFY_PREFIX,
                    message = "Shell probe failed: ${e.message}",
                    cause = e,
                )
            )
        }
    }

    private fun isRootfsInstalled(): Boolean {
        // Check for essential rootfs markers
        val etcDir = File(rootfsDir, "etc")
        val binDir = File(rootfsDir, "bin")
        val usrDir = File(rootfsDir, "usr")
        return rootfsDir.exists() && etcDir.exists() && binDir.exists() && usrDir.exists()
    }

    private fun isNodeInstalled(): Boolean {
        val nodeBinary = File(rootfsDir, "usr/bin/node")
        val nodeAlternate = File(rootfsDir, "usr/local/bin/node")
        return nodeBinary.exists() || nodeAlternate.exists()
    }

    private fun isClaudeCliInstalled(): Boolean {
        return claudeCliDetector.isInstalled()
    }

    // Kept for reference — original inline detection logic replaced by ClaudeCliDetector
    @Suppress("unused")
    private fun isClaudeCliInstalledLegacy(): Boolean {
        // Native binary that the wrapper symlinks point at. This is the
        // file that build-rootfs.sh strictly validates (>1 MiB), so its
        // existence is the canonical "claude is installed" signal.
        val nativePackages = listOf(
            "usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe",
            "usr/local/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe",
        )
        for (rel in nativePackages) {
            val candidate = File(rootfsDir, rel)
            // exists() returns true even for symlinks if the target is
            // present; a dangling symlink (target missing) returns false.
            // length() > 0 guards against zero-byte placeholders.
            if (candidate.exists() && candidate.length() > 0L) {
                return true
            }
        }
        return false
    }

    // ===== Detection helpers =====

    private fun detectRootfsDistro(): String? {
        val releaseFile = File(rootfsDir, "etc/os-release")
        if (!releaseFile.exists()) return null
        return try {
            releaseFile.readLines()
                .firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter("PRETTY_NAME=")
                ?.trim('"')
        } catch (_: Exception) {
            null
        }
    }

    private fun detectNodeVersion(): String? {
        // Try to read version from a known location
        val versionFile = File(rootfsDir, "usr/bin/node")
        if (!versionFile.exists()) {
            val alternate = File(rootfsDir, "usr/local/bin/node")
            if (!alternate.exists()) return null
        }
        // Version detection would normally run `node --version` via proot
        // For health check purposes, check if the binary exists
        return "installed"
    }

    private fun detectClaudeCliVersion(): String? {
        val packageJson = File(rootfsDir, "usr/lib/node_modules/@anthropic-ai/claude-code/package.json")
        if (!packageJson.exists()) return null
        return try {
            val content = packageJson.readText()
            val versionRegex = """"version"\s*:\s*"([^"]+)"""".toRegex()
            versionRegex.find(content)?.groupValues?.get(1)
        } catch (_: Exception) {
            "installed"
        }
    }

    // ===== Utility methods =====

    private fun calculateStorageUsed(): Long {
        return calculateDirSize(prefixDir) + calculateDirSize(rootfsDir)
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun handleBootstrapFailure(step: BootstrapStep, error: Throwable) {
        val availableSpace = context.filesDir.usableSpace
        val isStorageError = error is InsufficientStorageException

        val errorMessage = if (isStorageError) {
            val storageError = error as InsufficientStorageException
            "Insufficient storage space. Need ${storageError.requiredBytes / (1024 * 1024)} MB " +
                    "but only ${storageError.availableBytes / (1024 * 1024)} MB available. " +
                    "Please free at least ${(storageError.requiredBytes - storageError.availableBytes) / (1024 * 1024)} MB."
        } else {
            error.message ?: "Unknown error"
        }

        _bootstrapState.value = BootstrapState.Failed(
            step = step,
            error = errorMessage,
            requiredSpaceBytes = if (isStorageError) (error as InsufficientStorageException).requiredBytes else null,
            availableSpaceBytes = if (isStorageError) error.availableBytes else availableSpace,
        )
    }

    private fun updateState(step: BootstrapStep, progress: Float, message: String) {
        _bootstrapState.value = BootstrapState.InProgress(
            step = step,
            progress = progress,
            message = message,
        )
    }

    private fun emitProgress(step: BootstrapStep, fraction: Float, message: String) {
        _progressFlow.tryEmit(
            BootstrapProgress(
                step = step,
                fraction = fraction,
                message = message,
            )
        )
    }

    private suspend fun logDiagnostic(message: String) {
        try {
            diagnosticsRepository.logEvent(
                sessionId = null,
                eventType = "bootstrap",
                message = message,
            )
        } catch (_: Exception) {
            // Best effort logging - don't fail bootstrap if diagnostics fails
        }
    }

    internal companion object {
        const val PREFIX_DIR_NAME = "prefix"
        const val ROOTFS_DIR_NAME = "rootfs"
        const val ROOTFS_ASSET_DIR = "rootfs"
        const val ROOTFS_MINIMUM_SPACE_BYTES = 1_000L * 1024 * 1024 // 1 GB minimum for rootfs
        const val DOWNLOAD_BUFFER_SIZE = 32 * 1024

        // Phase boundaries for INSTALL_ROOTFS progress (0..1).
        const val PHASE_COPY_START = 0.0f
        const val PHASE_COPY_END = 0.30f
        const val PHASE_EXTRACT_START = 0.30f
        const val PHASE_EXTRACT_END = 0.95f

        // Used only when the manifest does not declare a file count for the
        // tarball (older builds). Picked so that a typical Ubuntu base + Node
        // + Claude rootfs (~6k entries) lands comfortably below the 0.99 cap.
        const val EXTRACT_ESTIMATED_FILES = 7_000
    }
}

/**
 * Exception thrown when a bootstrap step fails.
 */
public class BootstrapException(
    public val step: BootstrapStep,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
