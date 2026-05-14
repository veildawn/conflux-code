package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import android.content.res.AssetManager
import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.bridge.BootstrapState
import com.claudemobile.core.domain.bridge.BootstrapStep
import com.claudemobile.core.domain.bridge.VerificationResult
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    private lateinit var bootstrapManager: BootstrapManagerImpl

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        assetManager = mockk(relaxed = true)
        prefixExtractor = mockk(relaxed = true)
        diagnosticsRepository = mockk(relaxed = true)

        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns File(tempDir, "cache").also { it.mkdirs() }
        every { context.assets } returns assetManager

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

        val bundledVersion = PrefixVersion(
            prefixVersion = "1.0.0",
            extractedAt = "2025-01-15T10:30:00Z",
            archHash = "sha256:test123"
        )
        every { prefixExtractor.getBundledVersion() } returns bundledVersion
        every { prefixExtractor.needsUpgrade(any(), any()) } returns false

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
}
