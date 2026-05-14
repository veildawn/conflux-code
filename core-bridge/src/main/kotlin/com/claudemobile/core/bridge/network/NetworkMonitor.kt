package com.claudemobile.core.bridge.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Monitors device network connectivity state.
 *
 * Exposes a reactive [isConnected] flow that reflects whether the device
 * has an active network connection. The chat UI uses this to display an
 * offline banner when no network is available.
 *
 * Note: Messages are always forwarded to the bridge regardless of network state,
 * since the CLI process reports its own network errors.
 */
public interface NetworkMonitor {

    /**
     * A [StateFlow] that emits `true` when the device has an active network
     * connection and `false` when offline.
     */
    public val isConnected: StateFlow<Boolean>

    /**
     * Starts monitoring network connectivity. Should be called when the
     * application becomes active. Implementations should register a
     * [android.net.ConnectivityManager.NetworkCallback].
     */
    public fun startMonitoring()

    /**
     * Stops monitoring network connectivity. Should be called when the
     * application is no longer active to release system resources.
     */
    public fun stopMonitoring()
}
