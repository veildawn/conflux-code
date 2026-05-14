package com.claudemobile.core.bridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [NetworkMonitor] using Android's [ConnectivityManager].
 *
 * Registers a [ConnectivityManager.NetworkCallback] to receive real-time
 * network state changes. The callback tracks available networks and updates
 * [isConnected] accordingly.
 *
 * Lifecycle-aware: call [startMonitoring] when the app becomes active and
 * [stopMonitoring] when it goes to background or is destroyed.
 */
@Singleton
public class NetworkMonitorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkMonitor {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val activeNetworks = mutableSetOf<Network>()
    private var isMonitoring = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(activeNetworks) {
                activeNetworks.add(network)
                _isConnected.value = true
            }
        }

        override fun onLost(network: Network) {
            synchronized(activeNetworks) {
                activeNetworks.remove(network)
                _isConnected.value = activeNetworks.isNotEmpty()
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            val hasInternet = networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
            val isValidated = networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            synchronized(activeNetworks) {
                if (hasInternet && isValidated) {
                    activeNetworks.add(network)
                } else {
                    activeNetworks.remove(network)
                }
                _isConnected.value = activeNetworks.isNotEmpty()
            }
        }
    }

    override fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE missing — leave isConnected at its last value.
            isMonitoring = false
            return
        }

        // Update initial state
        _isConnected.value = checkCurrentConnectivity()
    }

    override fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Callback was not registered — safe to ignore
        }

        synchronized(activeNetworks) {
            activeNetworks.clear()
        }
    }

    /**
     * Checks the current network connectivity state synchronously.
     * Used for initial state and when monitoring is first started.
     *
     * Gracefully falls back to `false` if the permission check fails, so that
     * a missing ACCESS_NETWORK_STATE permission cannot crash app startup.
     */
    private fun checkCurrentConnectivity(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: SecurityException) {
            false
        }
    }
}
