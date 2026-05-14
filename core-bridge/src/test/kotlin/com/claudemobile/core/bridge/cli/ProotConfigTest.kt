package com.claudemobile.core.bridge.cli

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProotConfigTest {

    @Nested
    inner class BuildCommandArgs {

        @Test
        fun `includes rootfs argument`() {
            val config = ProotConfig(
                prootBinaryPath = "/data/data/com.claudemobile/prefix/usr/bin/proot",
                rootfsPath = "/data/data/com.claudemobile/rootfs",
                workspacePath = "/storage/emulated/0/projects/myapp"
            )

            val args = config.buildCommandArgs()

            args shouldContain "--rootfs=/data/data/com.claudemobile/rootfs"
        }

        @Test
        fun `includes default system bind mounts`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host"
            )

            val args = config.buildCommandArgs()

            // Should contain -b /dev:/dev, -b /proc:/proc, -b /sys:/sys
            args.zipWithNext().count { (a, b) -> a == "-b" && b == "/dev:/dev" } shouldBe 1
            args.zipWithNext().count { (a, b) -> a == "-b" && b == "/proc:/proc" } shouldBe 1
            args.zipWithNext().count { (a, b) -> a == "-b" && b == "/sys:/sys" } shouldBe 1
        }

        @Test
        fun `includes workspace bind mount`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/storage/emulated/0/projects/myapp"
            )

            val args = config.buildCommandArgs()

            args.zipWithNext().count { (a, b) ->
                a == "-b" && b == "/storage/emulated/0/projects/myapp:/workspace"
            } shouldBe 1
        }

        @Test
        fun `includes working directory set to mount point`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host"
            )

            val args = config.buildCommandArgs()

            args.zipWithNext().count { (a, b) -> a == "-w" && b == "/workspace" } shouldBe 1
        }

        @Test
        fun `uses custom mount point`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host",
                mountPoint = "/home/user/project"
            )

            val args = config.buildCommandArgs()

            args.zipWithNext().count { (a, b) ->
                a == "-b" && b == "/workspace_host:/home/user/project"
            } shouldBe 1
            args.zipWithNext().count { (a, b) -> a == "-w" && b == "/home/user/project" } shouldBe 1
        }

        @Test
        fun `uses custom additional bind mounts`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host",
                additionalBindMounts = mapOf(
                    "/dev" to "/dev",
                    "/tmp/host" to "/tmp"
                )
            )

            val args = config.buildCommandArgs()

            args.zipWithNext().count { (a, b) -> a == "-b" && b == "/dev:/dev" } shouldBe 1
            args.zipWithNext().count { (a, b) -> a == "-b" && b == "/tmp/host:/tmp" } shouldBe 1
            // /proc and /sys should NOT be present since we overrode additionalBindMounts
            args.zipWithNext().count { (a, b) -> a == "-b" && b == "/proc:/proc" } shouldBe 0
        }

        @Test
        fun `rootfs argument comes first`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host"
            )

            val args = config.buildCommandArgs()

            args.first() shouldBe "--rootfs=/rootfs"
        }

        @Test
        fun `does not include proot binary path in args`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host"
            )

            val args = config.buildCommandArgs()

            args shouldNotContain "/prefix/usr/bin/proot"
        }

        @Test
        fun `handles empty additional bind mounts`() {
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host",
                additionalBindMounts = emptyMap()
            )

            val args = config.buildCommandArgs()

            // Should still have rootfs, workspace bind, and working dir
            args shouldContain "--rootfs=/rootfs"
            args.zipWithNext().count { (a, b) ->
                a == "-b" && b == "/workspace_host:/workspace"
            } shouldBe 1
            args.zipWithNext().count { (a, b) -> a == "-w" && b == "/workspace" } shouldBe 1
        }
    }

    @Nested
    inner class BuildProotEnvironment {

        @Test
        fun `contains all required environment variables`() {
            val env = buildProotEnvironment("sk-ant-api03-test123")

            env shouldContainKey "HOME"
            env shouldContainKey "PATH"
            env shouldContainKey "TERM"
            env shouldContainKey "LANG"
            env shouldContainKey "ANTHROPIC_API_KEY"
        }

        @Test
        fun `has exactly 5 environment variables`() {
            val env = buildProotEnvironment("sk-ant-api03-test123")

            env shouldHaveSize 5
        }

        @Test
        fun `HOME is set to root`() {
            val env = buildProotEnvironment("sk-ant-api03-test123")

            env["HOME"] shouldBe "/root"
        }

        @Test
        fun `PATH contains standard Linux directories`() {
            val env = buildProotEnvironment("sk-ant-api03-test123")

            val path = env["PATH"]!!
            path shouldBe "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        }

        @Test
        fun `TERM is xterm-256color`() {
            val env = buildProotEnvironment("sk-ant-api03-test123")

            env["TERM"] shouldBe "xterm-256color"
        }

        @Test
        fun `LANG is en_US UTF-8`() {
            val env = buildProotEnvironment("sk-ant-api03-test123")

            env["LANG"] shouldBe "en_US.UTF-8"
        }

        @Test
        fun `ANTHROPIC_API_KEY matches provided key`() {
            val apiKey = "sk-ant-api03-secretkey12345"
            val env = buildProotEnvironment(apiKey)

            env["ANTHROPIC_API_KEY"] shouldBe apiKey
        }

        @Test
        fun `all values are non-empty`() {
            val env = buildProotEnvironment("sk-ant-api03-test")

            env.values.forEach { value ->
                assert(value.isNotEmpty()) { "Environment variable value should not be empty" }
            }
        }

        @Test
        fun `API key is not present in toString of config`() {
            // Verify that ProotConfig itself doesn't leak the API key
            val config = ProotConfig(
                prootBinaryPath = "/prefix/usr/bin/proot",
                rootfsPath = "/rootfs",
                workspacePath = "/workspace_host"
            )

            val configString = config.toString()
            configString shouldNotContain "sk-ant-api03"
        }
    }
}
