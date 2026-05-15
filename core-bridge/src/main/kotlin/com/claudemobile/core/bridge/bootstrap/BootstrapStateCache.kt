package com.claudemobile.core.bridge.bootstrap

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences-backed cache for bootstrap completion state.
 *
 * Enables the fast startup path: when the cache indicates a previous
 * successful bootstrap, [BootstrapManagerImpl] can skip the expensive
 * full verification and only perform a lightweight file-existence check.
 */
@Singleton
public class BootstrapStateCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    public fun markBootstrapComplete(version: String?) {
        prefs.edit()
            .putBoolean(KEY_BOOTSTRAP_COMPLETE, true)
            .putLong(KEY_LAST_SUCCESS_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_ROOTFS_VERSION, version)
            .apply()
    }

    public fun isBootstrapCachedAsComplete(): Boolean {
        return prefs.getBoolean(KEY_BOOTSTRAP_COMPLETE, false)
    }

    public fun getCachedRootfsVersion(): String? {
        return prefs.getString(KEY_ROOTFS_VERSION, null)
    }

    public fun invalidate() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "bootstrap_state_cache"
        const val KEY_BOOTSTRAP_COMPLETE = "bootstrap_complete"
        const val KEY_LAST_SUCCESS_TIMESTAMP = "last_success_ts"
        const val KEY_ROOTFS_VERSION = "rootfs_version"
    }
}
