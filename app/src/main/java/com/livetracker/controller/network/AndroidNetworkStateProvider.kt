package com.livetracker.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.livetracker.controller.core.tracker.recovery.NetworkState
import com.livetracker.controller.core.tracker.recovery.NetworkStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [NetworkStateProvider] backed by [ConnectivityManager.NetworkCallback].
 * Actively monitors network connectivity transitions, internet validation, and notifies listeners
 * on network restoration to trigger auto-reconnection and fleet hydration.
 */
class AndroidNetworkStateProvider(
    private val context: Context,
    private val onNetworkRestored: (() -> Unit)? = null
) : NetworkStateProvider {

    private val tag = "AndroidNetworkState"
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(NetworkState.UNKNOWN)
    override val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var currentNetwork: Network? = null
    private var wasValidated = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(tag, "Network available: $network")
            currentNetwork = network
            _networkState.value = NetworkState.AVAILABLE
            wasValidated = false
        }

        override fun onLost(network: Network) {
            Log.d(tag, "Network lost: $network")
            if (currentNetwork == network) {
                currentNetwork = null
                wasValidated = false
                _networkState.value = NetworkState.LOST
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

            val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val wasLost = (_networkState.value == NetworkState.LOST)
            if (isValidated && !wasValidated) {
                _networkState.value = NetworkState.AVAILABLE
                if (wasLost) {
                    Log.i(tag, "Internet restored from offline state. Notifying recovery listeners.")
                    onNetworkRestored?.invoke()
                }
            }
            wasValidated = isValidated
        }
    }

    fun start() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)

            val activeNet = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNet)
            if (activeNet != null && caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                _networkState.value = NetworkState.AVAILABLE
                currentNetwork = activeNet
            } else {
                _networkState.value = NetworkState.LOST
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to register network callback: ${e.message}")
            _networkState.value = NetworkState.UNKNOWN
        }
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(tag, "Failed to unregister network callback: ${e.message}")
        }
    }
}
