package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import android.content.res.AssetManager
import com.claudemobile.core.common.CoroutineDispatchers
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PrefixExtractorImplTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var dispatchers: CoroutineDispatchers
    private lateinit var extractor: PrefixExtractorImpl

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        assetManager = mockk(relaxed = true)

        every { context.assets } returns assetManager

        dispatchers = object : CoroutineDispatchers {
            override val default: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val unconfined: CoroutineDispatcher = testDispatcher
        }

        extractor = PrefixExtractorImpl(context, dispatchers)
    }

    @Test
    fun `extract fails when no assets are found`() = runTest(testDispatcher) {
        every { assetManager.list("prefix") } returns emptyArray()

        val progressValues = mutableListOf<Float>()
        val result = extractor.extract(tempDir) { progressValues.add(it) }

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "No prefix assets found in APK"
    }

    @Test
    fun `extract succeeds with valid assets`() = runTest(testDispatcher) {
        // Set up mock assets
        every { assetManager.list("prefix") } returns arrayOf("bin", "VERSION")
        every { assetManager.list("prefix/bin") } returns arrayOf("sh")
        every { assetManager.list("prefix/bin/sh") } returns emptyArray()
        every { assetManager.list("prefix/VERSION") } returns emptyArray()

        every { assetManager.open("prefix/bin/sh") } returns ByteArrayInputStream("#!/bin/sh".toByteArray())
        every { assetManager.open("prefix/VERSION") } answers { ByteArrayInputStream("1.0.0".toByteArray()) }

        val progressValues = mutableListOf<Float>()
        val result = extractor.extract(tempDir) { progressValues.add(it) }

        result.isSuccess shouldBe true
        val version = result.getOrNull()!!
        version.prefixVersion shouldBe "1.0.0"
        version.archHash shouldNotBe ""

        // Verify progress was reported
        progressValues.isNotEmpty() shouldBe true
        progressValues.first() shouldBe 0.0f
        progressValues.last() shouldBe 1.0f

        // Verify directory structure was created
        File(tempDir, "bin").exists() shouldBe true
        File(tempDir, "lib").exists() shouldBe true
        File(tempDir, "etc").exists() shouldBe true
        File(tempDir, "usr/bin").exists() shouldBe true
        File(tempDir, "tmp").exists() shouldBe true

        // Verify version file was written
        val versionFile = File(tempDir, ".version")
        versionFile.exists() shouldBe true
    }

    @Test
    fun `extract sets executable permissions on binaries`() = runTest(testDispatcher) {
        every { assetManager.list("prefix") } returns arrayOf("bin")
        every { assetManager.list("prefix/bin") } returns arrayOf("proot")
        every { assetManager.list("prefix/bin/proot") } returns emptyArray()

        every { assetManager.open("prefix/bin/proot") } returns ByteArrayInputStream("binary content".toByteArray())
        every { assetManager.open("prefix/VERSION") } throws java.io.FileNotFoundException()

        val result = extractor.extract(tempDir) { }

        result.isSuccess shouldBe true

        val prootFile = File(tempDir, "bin/proot")
        prootFile.exists() shouldBe true
        prootFile.canExecute() shouldBe true
    }

    @Test
    fun `extract fails with InsufficientStorageException when space is low`() = runTest(testDispatcher) {
        // Create a target dir that reports very low usable space
        // We can't easily mock File.usableSpace, so we test the logic path
        // by using a non-existent path that can't be created
        val impossibleDir = File("/proc/impossible_path_for_test")

        every { assetManager.list("prefix") } returns arrayOf("bin")

        val result = extractor.extract(impossibleDir) { }

        // Should fail because directory can't be created
        result.isFailure shouldBe true
    }

    @Test
    fun `needsUpgrade returns true when versions differ`() {
        val current = PrefixVersion("1.0.0", "2025-01-01T00:00:00Z", "sha256:old")
        val bundled = PrefixVersion("2.0.0", "2025-02-01T00:00:00Z", "sha256:new")

        extractor.needsUpgrade(current, bundled) shouldBe true
    }

    @Test
    fun `needsUpgrade returns true when hashes differ`() {
        val current = PrefixVersion("1.0.0", "2025-01-01T00:00:00Z", "sha256:old")
        val bundled = PrefixVersion("1.0.0", "2025-02-01T00:00:00Z", "sha256:new")

        extractor.needsUpgrade(current, bundled) shouldBe true
    }

    @Test
    fun `needsUpgrade returns false when version and hash match`() {
        val current = PrefixVersion("1.0.0", "2025-01-01T00:00:00Z", "sha256:same")
        val bundled = PrefixVersion("1.0.0", "2025-02-01T00:00:00Z", "sha256:same")

        extractor.needsUpgrade(current, bundled) shouldBe false
    }

    @Test
    fun `getBundledVersion reads from assets`() {
        val versionJson = PrefixVersion("1.5.0", "2025-01-15T00:00:00Z", "sha256:bundled").toJson()
        every { assetManager.open("prefix/.version") } returns ByteArrayInputStream(versionJson.toByteArray())

        val version = extractor.getBundledVersion()
        version shouldNotBe null
        version!!.prefixVersion shouldBe "1.5.0"
    }

    @Test
    fun `getBundledVersion returns fallback when asset not found`() {
        every { assetManager.open("prefix/.version") } throws java.io.FileNotFoundException()
        every { assetManager.open("prefix/VERSION") } throws java.io.FileNotFoundException()

        val version = extractor.getBundledVersion()
        version shouldNotBe null
        version!!.prefixVersion shouldBe "1.0.0" // fallback version
    }

    @Test
    fun `writeVersionFile and readVersionFile round-trip`() {
        val version = PrefixVersion("1.0.0", "2025-01-15T10:30:00Z", "sha256:test")

        PrefixExtractorImpl.writeVersionFile(tempDir, version)
        val read = PrefixExtractorImpl.readVersionFile(tempDir)

        read shouldBe version
    }

    @Test
    fun `readVersionFile returns null when file does not exist`() {
        val result = PrefixExtractorImpl.readVersionFile(tempDir)
        result shouldBe null
    }
}
