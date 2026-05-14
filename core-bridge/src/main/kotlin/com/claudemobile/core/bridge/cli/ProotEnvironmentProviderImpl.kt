package com.claudemobile.core.bridge.cli

import android.content.Context
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.usecase.ProotEnvironmentProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds [SpawnConfig] instances that launch `claude` inside the proot
 * Ubuntu environment. Resolves all filesystem paths from the app's
 * installed state (nativeLibraryDir for exec binaries, filesDir for
 * prefix/rootfs).
 *
 * The resulting command line is equivalent to:
 * ```
 * LD_LIBRARY_PATH=<prefix/lib>:<nativeLibDir> \
 * PROOT_LOADER=<nativeLibDir>/libproot-loader.so \
 * PROOT_LOADER_32=<nativeLibDir>/libproot-loader32.so \
 * PROOT_TMP_DIR=<prefix>/tmp \
 * <nativeLibDir>/libproot.so \
 *   --link2symlink -0 \
 *   --rootfs=<rootfs> \
 *   -b /dev -b /proc -b /sys \
 *   -b <workspace>:/workspace \
 *   -w /workspace \
 *   /usr/local/bin/claude
 * ```
 */
@Singleton
public class ProotEnvironmentProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProotEnvironmentProvider {

    private val nativeDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val prefixDir: File
        get() = File(context.filesDir, "prefix")

    private val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    override fun buildSpawnConfig(workspacePath: String, apiKey: String): SpawnConfig {
        val prootBin = File(nativeDir, "libproot.so")

        val args = mutableListOf(
            "--link2symlink",
            "-0",
            "--rootfs=${rootfsDir.absolutePath}",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$workspacePath:/workspace",
            "-w", "/workspace",
            "/usr/local/bin/claude",
        )

        val envVars = buildMap {
            put("LD_LIBRARY_PATH", "${prefixDir.absolutePath}/lib:${nativeDir.absolutePath}")
            put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
            put("PROOT_LOADER_32", File(nativeDir, "libproot-loader32.so").absolutePath)
            put("PROOT_TMP_DIR", File(prefixDir, "tmp").absolutePath)
            put("HOME", "/root")
            // Override Android's TMPDIR (which points outside the proot mount
            // tree) with /tmp so tools like npm and apt can create temp files.
            put("TMPDIR", "/tmp")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TERM", "xterm-256color")
            put("LANG", "en_US.UTF-8")
            put("ANTHROPIC_API_KEY", apiKey)
        }

        return SpawnConfig(
            command = prootBin.absolutePath,
            args = args,
            envVars = envVars,
            workingDir = workspacePath,
        )
    }
}
