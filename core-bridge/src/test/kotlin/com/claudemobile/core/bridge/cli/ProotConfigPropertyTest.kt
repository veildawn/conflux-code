package com.claudemobile.core.bridge.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property 1: Proot Command Building Correctness
 *
 * **Validates: Requirements 2.2, 8.3, 8.6**
 *
 * For any valid workspace path and SpawnConfig configuration, the generated proot
 * command arguments should contain:
 * (a) `--rootfs` pointing to the correct rootfs path,
 * (b) workspace directory bind mount (`-b workspace:/workspace`),
 * (c) required system bind mounts (`/dev`, `/proc`, `/sys`),
 * (d) working directory set to mount point.
 */
class ProotConfigPropertyTest : FunSpec({

    test("Feature: android-claude-termux-client, Property 1: Proot command building correctness") {
        /**
         * Validates: Requirements 2.2, 8.3, 8.6
         *
         * Generate arbitrary workspace paths, rootfs paths, and mount points,
         * then verify the generated proot command arguments contain all required elements.
         */
        checkAll(100, validPathArb(), validPathArb(), validMountPointArb()) { workspacePath, rootfsPath, mountPoint ->
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = rootfsPath,
                workspacePath = workspacePath,
                mountPoint = mountPoint
            )

            val args = config.buildCommandArgs()

            // (a) --rootfs pointing to the correct rootfs path
            args shouldContain "--rootfs=$rootfsPath"

            // (b) workspace directory bind mount (-b workspace:mountPoint)
            val bindMountPairs = args.zipWithNext()
                .filter { (flag, _) -> flag == "-b" }
                .map { (_, value) -> value }
            bindMountPairs shouldContain "$workspacePath:$mountPoint"

            // (c) required system bind mounts (/dev, /proc, /sys)
            bindMountPairs shouldContain "/dev:/dev"
            bindMountPairs shouldContain "/proc:/proc"
            bindMountPairs shouldContain "/sys:/sys"

            // (d) working directory set to mount point
            val workingDirPairs = args.zipWithNext()
                .filter { (flag, _) -> flag == "-w" }
                .map { (_, value) -> value }
            workingDirPairs shouldContain mountPoint
        }
    }
})

/**
 * Generates arbitrary valid directory paths.
 * Valid paths start with '/' and contain alphanumeric characters, underscores,
 * hyphens, and path separators. They do not contain colons (which would break
 * the bind mount syntax) or whitespace.
 */
private fun validPathArb(): Arb<String> =
    Arb.string(minSize = 1, maxSize = 50)
        .map { raw ->
            "/" + raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '/' }
                .trimStart('/')
                .replace("//", "/")
        }
        .filter { it.length > 1 && !it.endsWith("/") }

/**
 * Generates arbitrary valid mount points (absolute paths inside proot).
 * Mount points start with '/' and contain only safe path characters.
 */
private fun validMountPointArb(): Arb<String> =
    Arb.string(minSize = 1, maxSize = 30)
        .map { raw ->
            "/" + raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '/' }
                .trimStart('/')
                .replace("//", "/")
        }
        .filter { it.length > 1 && !it.endsWith("/") }
