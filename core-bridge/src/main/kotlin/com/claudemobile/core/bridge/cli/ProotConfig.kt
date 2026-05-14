package com.claudemobile.core.bridge.cli

/**
 * Configuration for building proot command-line arguments.
 *
 * Generates a command of the form:
 * ```
 * proot --rootfs=/path/to/rootfs \
 *   -b /dev:/dev \
 *   -b /proc:/proc \
 *   -b /sys:/sys \
 *   -b /workspace_host:/workspace \
 *   -w /workspace \
 *   /bin/bash -c "claude ..."
 * ```
 *
 * Requirements satisfied:
 * - Req 2.2: Configures bind mounts for workspace, /dev, /proc, /sys
 * - Req 8.3: Bind-mounts workspace at predictable path (/workspace)
 * - Req 8.6: Restricts access at proot bind-mount boundary
 */
public data class ProotConfig(
    /**
     * Absolute path to the proot binary within the Embedded_Prefix.
     */
    val prootBinaryPath: String,

    /**
     * Absolute path to the Ubuntu rootfs directory.
     */
    val rootfsPath: String,

    /**
     * Absolute path to the host workspace directory to be mounted.
     */
    val workspacePath: String,

    /**
     * The mount point inside the proot environment where the workspace is accessible.
     */
    val mountPoint: String = "/workspace",

    /**
     * Additional bind mounts mapping host paths to guest paths.
     * Defaults include /dev, /proc, and /sys as required by the Ubuntu rootfs.
     */
    val additionalBindMounts: Map<String, String> = mapOf(
        "/dev" to "/dev",
        "/proc" to "/proc",
        "/sys" to "/sys"
    )
) {
    /**
     * Builds the proot command-line arguments list.
     *
     * The returned list does NOT include the proot binary path itself —
     * that should be used as the command in [SpawnConfig.command].
     *
     * @return List of arguments: --rootfs, bind mounts (-b), working directory (-w)
     */
    public fun buildCommandArgs(): List<String> {
        val args = mutableListOf<String>()

        // Root filesystem
        args.add("--rootfs=$rootfsPath")

        // System bind mounts (/dev, /proc, /sys)
        additionalBindMounts.forEach { (host, guest) ->
            args.add("-b")
            args.add("$host:$guest")
        }

        // Workspace bind mount
        args.add("-b")
        args.add("$workspacePath:$mountPoint")

        // Working directory inside proot
        args.add("-w")
        args.add(mountPoint)

        return args
    }
}

/**
 * Builds the environment variable map for a Claude CLI process inside proot.
 *
 * The returned map includes all required environment variables:
 * - HOME: Set to /root (root user home in the proot environment)
 * - PATH: Standard Linux PATH for finding executables
 * - TERM: Terminal type for proper escape sequence handling
 * - LANG: Locale setting for UTF-8 support
 * - ANTHROPIC_API_KEY: The API key from the credential store (NEVER logged or written to files)
 *
 * Requirements satisfied:
 * - Req 2.3: Sets HOME, PATH, TERM, LANG, and ANTHROPIC_API_KEY before exec
 * - Req 6.4: API key passed via environment variable, never written to log or file
 *
 * @param apiKey The Anthropic API key retrieved from the credential store.
 *              This value is passed directly to the environment and must NEVER be logged.
 * @return A map of environment variable names to their values.
 */
public fun buildProotEnvironment(apiKey: String): Map<String, String> {
    return mapOf(
        "HOME" to "/root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
        "ANTHROPIC_API_KEY" to apiKey
    )
}
