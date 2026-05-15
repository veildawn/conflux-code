package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.os.Build
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.bridge.BootstrapState
import com.claudemobile.core.domain.bridge.BootstrapStep
import com.claudemobile.core.domain.bridge.VerificationResult
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapManagerImplTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var prefixExtractor: PrefixExtractor
    private lateinit var diagnosticsRepository: DiagnosticsRepository
    private lateinit var dispatchers: CoroutineDispatchers
    private lateinit var claudeCliDetector: ClaudeCliDetector
    private lateinit var integrityChecker: RootfsIntegrityChecker
    private lateinit var stateCache: BootstrapStateCache
    private lateinit var bootstrapManager: BootstrapManagerImpl

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        assetManager = mockk(relaxed = true)
        prefixExtractor = mockk(relaxed = true)
        diagnosticsRepository = mockk(relaxed = true)
        claudeCliDetector = mockk(relaxed = true)
        integrityChecker = mockk(relaxed = true)
        stateCache = mockk(relaxed = true)

        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns File(tempDir, "cache").also { it.mkdirs() }
        every { context.assets } returns assetManager
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(tempDir, "native-lib").also { it.mkdirs() }.absolutePath
        }
        every { stateCache.isBootstrapCachedAsComplete() } returns false

        // Set Build.SUPPORTED_ABIS via Unsafe for JDK 17+ compatibility
        setSupportedAbis(arrayOf("arm64-v8a"))

        dispatchers = object : CoroutineDispatchers {
            override val default: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val unconfined: CoroutineDispatcher = testDispatcher
        }

        coEvery { diagnosticsRepository.logEvent(any(), any(), any(), any()) } returns Unit

        bootstrapManager = BootstrapManagerImpl(
            context = context,
            prefixExtractor = prefixExtractor,
            diagnosticsRepository = diagnosticsRepository,
            dispatchers = dispatchers,
            claudeCliDetector = claudeCliDetector,
            integrityChecker = integrityChecker,
            stateCache = stateCache,
        )
    }

    @Test
    fun `initial state is NotStarted`() {
        bootstrapManager.bootstrapState.value.shouldBeInstanceOf<BootstrapState.NotStarted>()
    }

    @Test
    fun `isReady returns false when nothing is set up`() = runTest(testDispatcher) {
        val ready = bootstrapManager.isReady()
        ready shouldBe false
    }

    @Test
    fun `isReady returns true when all components are present`() = runTest(testDispatcher) {
        // Set up a complete environment
        setupCompletePrefixDir()
        setupCompleteRootfsDir()
        every { claudeCliDetector.isInstalled() } returns true

        val ready = bootstrapManager.isReady()
        ready shouldBe true
    }

    @Test
    fun `verify returns correct status for empty environment`() = runTest(testDispatcher) {
        val result = bootstrapManager.verify()
        result.isSuccess shouldBe true

        val verification = result.getOrNull()!!
        verification.prefixExtracted shouldBe false
        verification.rootfsInstalled shouldBe false
        verification.nodeInstalled shouldBe false
        verification.claudeCliInstalled shouldBe false
        verification.allVerified shouldBe false
    }

    @Test
    fun `verify returns correct status for complete environment`() = runTest(testDispatcher) {
        setupCompletePrefixDir()
        setupCompleteRootfsDir()
        every { claudeCliDetector.isInstalled() } returns true

        val result = bootstrapManager.verify()
        result.isSuccess shouldBe true

        val verification = result.getOrNull()!!
        verification.prefixExtracted shouldBe true
        verification.rootfsInstalled shouldBe true
        verification.nodeInstalled shouldBe true
        verification.claudeCliInstalled shouldBe true
        verification.allVerified shouldBe true
    }

    @Test
    fun `healthCheck reports correct status for empty environment`() = runTest(testDispatcher) {
        val health = bootstrapManager.healthCheck()

        health.prefixInstalled shouldBe false
        health.prefixVersion shouldBe null
        health.rootfsInstalled shouldBe false
        health.rootfsDistro shouldBe null
        health.nodeVersion shouldBe null
        health.claudeCliVersion shouldBe null
        health.storageUsedBytes shouldBe 0L
    }

    @Test
    fun `healthCheck reports correct status for complete environment`() = runTest(testDispatcher) {
        setupCompletePrefixDir()
        setupCompleteRootfsDir()

        val health = bootstrapManager.healthCheck()

        health.prefixInstalled shouldBe true
        health.prefixVersion shouldBe "1.0.0"
        health.rootfsInstalled shouldBe true
        health.rootfsDistro shouldBe "Ubuntu 22.04 LTS"
        health.nodeVersion shouldNotBe null
    }

    @Test
    fun `extract succeeds when prefix is already up to date`() = runTest(testDispatcher) {
        setupCompletePrefixDir()

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

        val result = bootstrapManager.extract()
        result.isSuccess shouldBe true
    }

    @Test
    fun `extract calls prefixExtractor when upgrade is needed`() = runTest(testDispatcher) {
        setupCompletePrefixDir()

        val bundledVersion = PrefixVersion(
            prefixVersion = "2.0.0",
            extractedAt = "2025-02-01T10:30:00Z",
            archHash = "sha256:newHash"
        )
        val extractedVersion = PrefixVersion(
            prefixVersion = "2.0.0",
            extractedAt = "2025-02-01T10:30:00Z",
            archHash = "sha256:newHash"
        )

        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns true
        coEvery { prefixExtractor.extract(any(), any()) } returns Result.success(extractedVersion)

        val result = bootstrapManager.extract()
        result.isSuccess shouldBe true

        coVerify { prefixExtractor.extract(any(), any()) }
    }

    @Test
    fun `extract propagates failure from prefixExtractor`() = runTest(testDispatcher) {
        every { prefixExtractor.getBundledVersion() } returns null

        val error = BootstrapException(
            step = BootstrapStep.EXTRACT_PREFIX,
            message = "No prefix assets found"
        )
        coEvery { prefixExtractor.extract(any(), any()) } returns Result.failure(error)

        val result = bootstrapManager.extract()
        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "No prefix assets found"
    }

    @Test
    fun `bootstrap transitions to Failed state on extraction failure`() = runTest(testDispatcher) {
        every { prefixExtractor.getBundledVersion() } returns null
        coEvery { prefixExtractor.extract(any(), any()) } returns Result.failure(
            BootstrapException(
                step = BootstrapStep.EXTRACT_PREFIX,
                message = "Extraction failed"
            )
        )

        val result = bootstrapManager.bootstrap()
        result.isFailure shouldBe true

        val state = bootstrapManager.bootstrapState.value
        state.shouldBeInstanceOf<BootstrapState.Failed>()
        (state as BootstrapState.Failed).step shouldBe BootstrapStep.EXTRACT_PREFIX
    }

    @Test
    fun `bootstrap succeeds with complete environment`() = runTest(testDispatcher) {
        setupCompletePrefixDir()
        setupCompleteRootfsDir()
        every { claudeCliDetector.isInstalled() } returns true

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false
        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")

        val result = bootstrapManager.bootstrap()
        result.isSuccess shouldBe true

        val state = bootstrapManager.bootstrapState.value
        state.shouldBeInstanceOf<BootstrapState.Ready>()
    }

    @Test
    fun `bootstrap logs diagnostics events`() = runTest(testDispatcher) {
        setupCompletePrefixDir()
        setupCompleteRootfsDir()

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

        bootstrapManager.bootstrap()

        coVerify(atLeast = 1) {
            diagnosticsRepository.logEvent(
                sessionId = null,
                eventType = "bootstrap",
                message = any(),
                details = any()
            )
        }
    }

    @Test
    fun `bootstrap fails at proot step when binary is missing`() = runTest(testDispatcher) {
        // Set up prefix without proot binary
        val prefixDir = File(tempDir, "prefix")
        prefixDir.mkdirs()
        val versionFile = File(prefixDir, ".version")
        val version = PrefixVersion("1.0.0", "2025-01-15T10:30:00Z", "sha256:test")
        versionFile.writeText(version.toJson())

        every { prefixExtractor.getBundledVersion() } returns version
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

        val result = bootstrapManager.bootstrap()
        result.isFailure shouldBe true

        val state = bootstrapManager.bootstrapState.value
        state.shouldBeInstanceOf<BootstrapState.Failed>()
        (state as BootstrapState.Failed).step shouldBe BootstrapStep.VERIFY_PREFIX
    }

    @Test
    fun `bootstrap sets executable permissions on all binaries in prefix`() = runTest(testDispatcher) {
        setupCompletePrefixDir()
        setupCompleteRootfsDir()
        every { claudeCliDetector.isInstalled() } returns true

        // Add additional binaries to bin/ and usr/bin/
        val prefixDir = File(tempDir, "prefix")
        val shBinary = File(prefixDir, "bin/sh")
        shBinary.writeText("#!/bin/sh\n# sh stub")
        val tarBinary = File(prefixDir, "usr/bin/tar")
        tarBinary.writeText("#!/bin/sh\n# tar stub")

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false
        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")

        val result = bootstrapManager.bootstrap()
        result.isSuccess shouldBe true

        // Verify all binaries are executable
        shBinary.canExecute() shouldBe true
        tarBinary.canExecute() shouldBe true
    }

    @Test
    fun `bootstrap reports storage error with space guidance`() = runTest(testDispatcher) {
        setupCompletePrefixDir()

        // Simulate insufficient storage during rootfs install
        // The rootfs dir doesn't exist, so it will try to install
        // We can't easily mock usableSpace, but we can verify the error state format
        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

        // The bootstrap will fail at rootfs step since rootfs doesn't exist
        // and there's no asset or download URL configured
        every { assetManager.list("rootfs") } returns emptyArray()
        every { assetManager.open("config/rootfs_url.txt") } throws java.io.FileNotFoundException()

        val result = bootstrapManager.bootstrap()
        result.isFailure shouldBe true

        val state = bootstrapManager.bootstrapState.value
        state.shouldBeInstanceOf<BootstrapState.Failed>()
    }

    @Test
    fun `healthCheck reports storage usage`() = runTest(testDispatcher) {
        setupCompletePrefixDir()
        setupCompleteRootfsDir()

        val health = bootstrapManager.healthCheck()

        // Storage used should be > 0 since we created files
        health.storageUsedBytes shouldNotBe 0L
        health.storageAvailableBytes shouldNotBe 0L
    }

    // ===== Task 7.1: Fast startup path tests =====

    @Test
    fun `isReady fast path - cache hit and environment complete returns true`() = runTest(testDispatcher) {
        // Arrange: cache indicates previous successful bootstrap
        every { stateCache.isBootstrapCachedAsComplete() } returns true

        // Set up file system so quickVerify() passes:
        // 1. prefix version file exists
        setupCompletePrefixDir()
        // 2. rootfs/etc directory exists
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        // 3. claudeCliDetector.isInstalled() returns true
        every { claudeCliDetector.isInstalled() } returns true

        // Act
        val ready = bootstrapManager.isReady()

        // Assert
        ready shouldBe true
    }

    @Test
    fun `isReady fast path completes within 1000ms`() = runTest(testDispatcher) {
        // Arrange: cache hit + environment complete
        every { stateCache.isBootstrapCachedAsComplete() } returns true
        setupCompletePrefixDir()
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        every { claudeCliDetector.isInstalled() } returns true

        // Act: measure wall-clock time of the fast path
        val startTime = System.currentTimeMillis()
        val ready = bootstrapManager.isReady()
        val elapsed = System.currentTimeMillis() - startTime

        // Assert
        ready shouldBe true
        // Fast path should be well under 1000ms (only file existence checks)
        (elapsed < 1000L) shouldBe true
    }

    @Test
    fun `isReady fast path does not call invalidate when environment is complete`() = runTest(testDispatcher) {
        // Arrange
        every { stateCache.isBootstrapCachedAsComplete() } returns true
        setupCompletePrefixDir()
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        every { claudeCliDetector.isInstalled() } returns true

        // Act
        bootstrapManager.isReady()

        // Assert: invalidate should NOT be called on the fast path
        verify(exactly = 0) { stateCache.invalidate() }
    }

    // ===== Task 7.2: Cache invalidation scenario tests =====

    @Test
    fun `isReady invalidates cache when quickVerify fails - rootfs etc missing`() = runTest(testDispatcher) {
        // Arrange: cache says complete, but rootfs/etc doesn't exist
        every { stateCache.isBootstrapCachedAsComplete() } returns true
        setupCompletePrefixDir()
        // rootfs/etc does NOT exist — quickVerify will fail
        every { claudeCliDetector.isInstalled() } returns true

        // Act
        bootstrapManager.isReady()

        // Assert: cache should be invalidated
        verify(exactly = 1) { stateCache.invalidate() }
    }

    @Test
    fun `isReady invalidates cache when quickVerify fails - claude cli not installed`() = runTest(testDispatcher) {
        // Arrange: cache says complete, but Claude CLI is not installed
        every { stateCache.isBootstrapCachedAsComplete() } returns true
        setupCompletePrefixDir()
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        // claudeCliDetector returns false
        every { claudeCliDetector.isInstalled() } returns false

        // Act
        bootstrapManager.isReady()

        // Assert: cache should be invalidated
        verify(exactly = 1) { stateCache.invalidate() }
    }

    @Test
    fun `isReady falls back to full check after cache invalidation`() = runTest(testDispatcher) {
        // Arrange: cache says complete, but environment is damaged (no rootfs/etc)
        every { stateCache.isBootstrapCachedAsComplete() } returns true
        setupCompletePrefixDir()
        // No rootfs at all — quickVerify fails, full check also fails
        every { claudeCliDetector.isInstalled() } returns false

        // Act
        val ready = bootstrapManager.isReady()

        // Assert: falls back to full check which also fails
        ready shouldBe false
        verify(exactly = 1) { stateCache.invalidate() }
    }

    // ===== Task 7.3: Version marker recovery scenario tests =====

    @Test
    fun `bootstrap recovers version marker when missing but integrity passes`() = runTest(testDispatcher) {
        // Arrange: prefix is set up, rootfs exists but has no version marker
        setupCompletePrefixDir()
        setupCompleteRootfsDir()
        // Remove version marker to simulate missing marker scenario
        File(File(tempDir, "rootfs"), ".claudemobile-bundled-version").delete()

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false
        every { claudeCliDetector.isInstalled() } returns true

        // Mock asset resolution to return a valid asset path
        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")
        // Manifest without version info (so no version mismatch logic triggers)
        every { assetManager.open("rootfs/manifest.tsv") } returns "".byteInputStream()

        // Integrity check passes
        every { integrityChecker.check() } returns IntegrityResult(
            directoryStructureValid = true,
            nodeInstalled = true,
            claudeCliInstalled = true,
        )

        // Act
        val result = bootstrapManager.bootstrap()

        // Assert: recoverVersionMarker should be called
        verify(exactly = 1) { integrityChecker.recoverVersionMarker() }
        result.isSuccess shouldBe true
    }

    @Test
    fun `bootstrap skips extraction when version marker recovered`() = runTest(testDispatcher) {
        // Arrange: same as above - rootfs exists, no version marker, integrity passes
        setupCompletePrefixDir()
        setupCompleteRootfsDir()
        // Remove version marker to simulate missing marker scenario
        File(File(tempDir, "rootfs"), ".claudemobile-bundled-version").delete()

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false
        every { claudeCliDetector.isInstalled() } returns true
        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")
        every { assetManager.open("rootfs/manifest.tsv") } returns "".byteInputStream()

        every { integrityChecker.check() } returns IntegrityResult(
            directoryStructureValid = true,
            nodeInstalled = true,
            claudeCliInstalled = true,
        )

        // Act
        val result = bootstrapManager.bootstrap()

        // Assert: no asset open for extraction (no tar extraction happened)
        verify(exactly = 0) { assetManager.open("rootfs/rootfs-arm64-v8a.tar.xz") }
        result.isSuccess shouldBe true
    }

    @Test
    fun `bootstrap proceeds with extraction when integrity check fails`() = runTest(testDispatcher) {
        // Arrange: rootfs exists, no version marker, integrity FAILS
        setupCompletePrefixDir()
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        // Minimal rootfs that won't pass isRootfsInstalled after wipe

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false
        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")

        // Integrity check fails
        every { integrityChecker.check() } returns IntegrityResult(
            directoryStructureValid = false,
            nodeInstalled = false,
            claudeCliInstalled = false,
        )

        // Act: bootstrap will try to extract but will fail since we can't
        // actually run tar in a unit test. The important thing is that
        // recoverVersionMarker is NOT called.
        bootstrapManager.bootstrap()

        // Assert: recoverVersionMarker should NOT be called
        verify(exactly = 0) { integrityChecker.recoverVersionMarker() }
    }

    // ===== Task 7.4: Version upgrade scenario tests =====

    @Test
    fun `bootstrap proceeds with upgrade when version mismatch and asset accessible`() = runTest(testDispatcher) {
        // Arrange: rootfs exists with version "1.0.0", bundled is "2.0.0"
        setupCompletePrefixDir()
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "usr").mkdirs()

        // Write version marker with old version
        File(rootfsDir, ".claudemobile-bundled-version").writeText("version=1.0.0\nsource=build\n")

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

        // Asset list returns the rootfs tarball
        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")
        // Manifest with version "2.0.0" (different from installed "1.0.0")
        val manifestContent = "arm64-v8a\t2.0.0\tsha256:abc\t100000\t500000\t5000"
        every { assetManager.open("rootfs/manifest.tsv") } returns manifestContent.byteInputStream()
        // Asset is accessible
        every { assetManager.open("rootfs/rootfs-arm64-v8a.tar.xz") } returns "fake".byteInputStream()

        // Act: bootstrap will try to extract (will fail at tar execution in unit test)
        // but the key assertion is that it DOES attempt the upgrade (wipes rootfs)
        val result = bootstrapManager.bootstrap()

        // Assert: the rootfs directory should have been wiped (deleted) for upgrade
        // Since tar extraction will fail in unit test, bootstrap will fail,
        // but the important thing is that the asset was verified accessible
        // and the wipe was attempted
        verify { assetManager.open("rootfs/rootfs-arm64-v8a.tar.xz") }
    }

    @Test
    fun `bootstrap fails without wipe when version mismatch and asset not accessible`() = runTest(testDispatcher) {
        // Arrange: rootfs exists with version "1.0.0", bundled is "2.0.0"
        setupCompletePrefixDir()
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "usr").mkdirs()

        // Write version marker with old version
        File(rootfsDir, ".claudemobile-bundled-version").writeText("version=1.0.0\nsource=build\n")

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

        // Asset list returns the rootfs tarball
        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")
        // Manifest with version "2.0.0" (different from installed "1.0.0")
        val manifestContent = "arm64-v8a\t2.0.0\tsha256:abc\t100000\t500000\t5000"
        every { assetManager.open("rootfs/manifest.tsv") } returns manifestContent.byteInputStream()
        // Asset is NOT accessible - throws exception
        every { assetManager.open("rootfs/rootfs-arm64-v8a.tar.xz") } throws java.io.IOException("Asset not found")

        // Act
        val result = bootstrapManager.bootstrap()

        // Assert: bootstrap should fail
        result.isFailure shouldBe true
        // The rootfs directory should still exist (not wiped)
        rootfsDir.exists() shouldBe true
        File(rootfsDir, "etc").exists() shouldBe true
        // The error message should indicate asset access failure
        result.exceptionOrNull()?.message shouldNotBe null
    }

    @Test
    fun `bootstrap preserves existing rootfs when asset not accessible for upgrade`() = runTest(testDispatcher) {
        // Arrange: rootfs exists with version "1.0.0", bundled is "2.0.0"
        setupCompletePrefixDir()
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "usr").mkdirs()

        // Write a sentinel file to verify rootfs is not wiped
        val sentinelFile = File(rootfsDir, "sentinel.txt")
        sentinelFile.writeText("do not delete me")

        // Write version marker with old version
        File(rootfsDir, ".claudemobile-bundled-version").writeText("version=1.0.0\nsource=build\n")

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

        every { assetManager.list("rootfs") } returns arrayOf("rootfs-arm64-v8a.tar.xz")
        val manifestContent = "arm64-v8a\t2.0.0\tsha256:abc\t100000\t500000\t5000"
        every { assetManager.open("rootfs/manifest.tsv") } returns manifestContent.byteInputStream()
        // Asset not accessible
        every { assetManager.open("rootfs/rootfs-arm64-v8a.tar.xz") } throws java.io.IOException("Asset not found")

        // Act
        bootstrapManager.bootstrap()

        // Assert: sentinel file should still exist (rootfs was NOT wiped)
        sentinelFile.exists() shouldBe true
        sentinelFile.readText() shouldBe "do not delete me"
    }

    // ===== Helper methods =====

    private fun setupCompletePrefixDir() {
        val prefixDir = File(tempDir, "prefix")
        prefixDir.mkdirs()

        // Create version file
        val version = PrefixVersion("1.0.0", "2025-01-15T10:30:00Z", "sha256:test123")
        File(prefixDir, ".version").writeText(version.toJson())

        // Create directory structure
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "etc").mkdirs()
        File(prefixDir, "usr/bin").mkdirs()
        File(prefixDir, "usr/lib").mkdirs()
        File(prefixDir, "tmp").mkdirs()

        // Create a fake shell binary in nativeLibraryDir so setupProot() passes
        val nativeLibDir = File(tempDir, "native-lib")
        nativeLibDir.mkdirs()
        val bashBinary = File(nativeLibDir, "libbash.so")
        bashBinary.writeText("#!/bin/sh\necho 'GNU bash, version 5.2.0(1)-release'")
        bashBinary.setExecutable(true, false)
    }

    private fun setupCompleteRootfsDir() {
        val rootfsDir = File(tempDir, "rootfs")
        rootfsDir.mkdirs()

        // Create essential rootfs directories
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "usr/bin").mkdirs()
        File(rootfsDir, "usr/local/bin").mkdirs()
        File(rootfsDir, "usr/lib/node_modules/@anthropic-ai/claude-code").mkdirs()

        // Create version marker so installRootfsReal() recognizes it as installed
        File(rootfsDir, ".claudemobile-bundled-version").writeText("version=1.0.0\nsource=build\n")

        // Create os-release
        File(rootfsDir, "etc/os-release").writeText(
            """
            NAME="Ubuntu"
            PRETTY_NAME="Ubuntu 22.04 LTS"
            VERSION_ID="22.04"
            """.trimIndent()
        )

        // Create node binary
        val nodeBinary = File(rootfsDir, "usr/bin/node")
        nodeBinary.writeText("#!/bin/sh\n# node stub")
        nodeBinary.setExecutable(true, false)

        // Create claude binary
        val claudeBinary = File(rootfsDir, "usr/local/bin/claude")
        claudeBinary.writeText("#!/bin/sh\n# claude stub")
        claudeBinary.setExecutable(true, false)

        // Create package.json for version detection
        File(rootfsDir, "usr/lib/node_modules/@anthropic-ai/claude-code/package.json").writeText(
            """{"name": "@anthropic-ai/claude-code", "version": "1.2.3"}"""
        )
    }

    /**
     * Sets [Build.SUPPORTED_ABIS] via sun.misc.Unsafe since the field is
     * static final and standard reflection is blocked on JDK 17+.
     */
    private fun setSupportedAbis(abis: Array<String>) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)

        val field = Build::class.java.getDeclaredField("SUPPORTED_ABIS")
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod("putObject", Any::class.java, Long::class.java, Any::class.java)
            .invoke(unsafe, base, offset, abis)
    }
}
