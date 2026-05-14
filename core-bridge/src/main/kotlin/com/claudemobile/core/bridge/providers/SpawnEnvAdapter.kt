/**
 * # SpawnEnvAdapter — cross-reference note
 *
 * **Supersedes** `android-claude-termux-client` base-spec Requirement 2, AC 3
 * for the Anthropic-related environment variables.
 *
 * The base spec required the Bridge to set a fixed `ANTHROPIC_API_KEY` at
 * spawn time. This file replaces that behaviour: instead of a single
 * hard-coded key, [SpawnEnvAdapterImpl] reads the Active_Profile from
 * [com.claudemobile.core.domain.providers.ProviderProfileStore] at the
 * moment of each spawn and delegates to the pure
 * [com.claudemobile.core.domain.providers.buildClaudeEnv] function to
 * derive the full set of Anthropic-compatible variables
 * (`ANTHROPIC_BASE_URL`, one of `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN`,
 * `ANTHROPIC_MODEL`, and optionally `ANTHROPIC_SMALL_FAST_MODEL`).
 *
 * All other environment variables mandated by base-spec R2 AC3
 * (`HOME`, `PATH`, `TERM`, `LANG`) continue to be supplied by the caller
 * as the `baseEnv` argument and are preserved unchanged by
 * [buildClaudeEnv].
 *
 * **Implements** `ai-provider-presets` Requirement 6 (Environment Injection
 * for Claude_CLI Process), specifically AC 1–8.
 *
 * See also: `core-domain` `EnvBuilder.kt` for the pure function that
 * constructs the env map, and `CliBridgeImpl` for the call site that
 * invokes [SpawnEnvAdapter.prepareEnv].
 */
package com.claudemobile.core.bridge.providers

import com.claudemobile.core.domain.bridge.BridgeError
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.buildClaudeEnv
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge-layer adapter that materializes the spawn environment for a
 * Claude_CLI process from the currently active [com.claudemobile.core.domain.providers.ProviderProfile].
 *
 * The adapter is the single point at which the bridge crosses from
 * "process to spawn" intent into "concrete env map": it reads the
 * Active_Profile via [ProviderProfileStore.getActive] **at the moment of
 * each call** (no cached snapshot, no `StateFlow.value`) and overlays the
 * Anthropic-compatible variables on top of the supplied [baseEnv] using
 * the pure [buildClaudeEnv] function.
 *
 * Reading at call time is the implementation of Property 9 ("each spawn
 * re-reads the Active_Profile") and the spawn-side fulfilment of R5.4 /
 * R6.1 / R11.2: between two consecutive spawns the user may switch
 * providers, and the second spawn must observe the new profile without
 * any cache flushing.
 *
 * When [ProviderProfileStore.getActive] returns `null`, the adapter
 * fails with [BridgeError.NoActiveProfile] (R5.5 / R6.8 / R11.5);
 * upstream callers route the user to the provider-selection screen
 * rather than spawning an unconfigured process.
 *
 * Requirements: 5.4, 6.1, 6.7, 6.8, 11.2.
 * Properties: 9, 12.
 */
public interface SpawnEnvAdapter {

    /**
     * Reads the Active_Profile (fresh on every invocation) and returns a
     * spawn environment that contains every entry of [baseEnv] not
     * overridden by the Anthropic variables, plus the Anthropic
     * variables derived from the active profile via [buildClaudeEnv].
     *
     * The returned [Result] is:
     * - [Result.success] with a fresh, immutable-flavoured map when an
     *   Active_Profile exists.
     * - [Result.failure] carrying [BridgeError.NoActiveProfile] when no
     *   Active_Profile is selected. No partial environment is produced.
     *
     * The function is suspending because [ProviderProfileStore.getActive]
     * is suspending; it performs no I/O of its own beyond that read.
     *
     * @param baseEnv The base environment inherited from the rootfs
     *   (e.g., `HOME`, `PATH`, `TERM`, `LANG`). Never mutated by the
     *   adapter; the returned map is a new instance.
     */
    public suspend fun prepareEnv(baseEnv: Map<String, String>): Result<Map<String, String>>
}

/**
 * Default [SpawnEnvAdapter] backed by [ProviderProfileStore.getActive].
 *
 * The class deliberately holds **no** mutable state — there is no
 * `lastProfile`, no `StateFlow`, no in-memory cache. Every
 * [prepareEnv] call performs a fresh read against the store, which is
 * what Property 9 requires.
 */
@Singleton
public class SpawnEnvAdapterImpl @Inject constructor(
    private val store: ProviderProfileStore,
) : SpawnEnvAdapter {

    override suspend fun prepareEnv(
        baseEnv: Map<String, String>,
    ): Result<Map<String, String>> {
        // Always perform a fresh read: never read from a cached field, a
        // StateFlow.value snapshot, or a `lateinit var`. This is the
        // operative implementation of Property 9.
        val profile = store.getActive()
            ?: return Result.failure(BridgeError.NoActiveProfile)

        val env = buildClaudeEnv(baseEnv, profile)
        return Result.success(env)
    }
}

/**
 * Renders a [SpawnConfig] for diagnostic logging without leaking any
 * environment variable values.
 *
 * The resulting string contains:
 * - the [SpawnConfig.command] path,
 * - the [SpawnConfig.args] list,
 * - the [SpawnConfig.workingDir],
 * - the **keys** of [SpawnConfig.envVars] (sorted for deterministic
 *   output), and
 * - the [SpawnConfig.rows] / [SpawnConfig.cols] dimensions.
 *
 * It deliberately excludes every env value — and therefore excludes
 * `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_BASE_URL`
 * (R6.7) — so log lines containing this string can never substring-leak
 * an API key or a base URL containing user-info credentials. This is
 * the primary in-memory log non-leakage guarantee that underpins
 * Property 12 at the bridge boundary.
 *
 * Pure function: total, deterministic, side-effect-free.
 *
 * Requirements: 6.7, 11.2.
 * Property: 12.
 */
public fun SpawnConfig.toSafeString(): String {
    val keys = envVars.keys.sorted()
    return buildString {
        append("SpawnConfig(")
        append("command=").append(command)
        append(", args=").append(args)
        append(", workingDir=").append(workingDir)
        append(", envKeys=").append(keys)
        append(", rows=").append(rows)
        append(", cols=").append(cols)
        append(')')
    }
}
