package com.matanh.transfer.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.Inet4Address

/**
 * A helper class to monitor network state and provide the current WiFi IP address.
 * It uses ConnectivityManager.NetworkCallback to listen for network changes.
 */
class NetworkHelper(context: Context) {

    private val applicationContext = context.applicationContext
    private val connectivityManager =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _currentIpAddress = MutableStateFlow<String?>(null)
    val currentIpAddress: StateFlow<String?> = _currentIpAddress.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Timber.d("Network available: $network, updating IP.")
            updateIpAddress()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Timber.d("Network lost: $network, updating IP.")
            updateIpAddress()
        }
    }

    init {
        // Get initial IP address on creation
        updateIpAddress()
    }

    /**
     * Registers the network callback to start listening for network changes.
     */
    fun register() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            Timber.d("Network callback registered.")
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to register network callback. Missing ACCESS_NETWORK_STATE permission?")
        }
    }

    /**
     * Unregisters the network callback to stop listening and prevent memory leaks.
     */
    fun unregister() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Timber.d("Network callback unregistered.")
        } catch (e: Exception) {
            // Can throw IllegalArgumentException if not registered, which is safe to ignore.
            Timber.e(e, "Error unregistering network callback.")
        }
    }

    /**
     * Fetches the current local IP address and updates the state flow.
     */
    private fun updateIpAddress() {
        val newIp = getLocalIpAddress()
        if (_currentIpAddress.value != newIp) {
            _currentIpAddress.value = newIp
            Timber.i("IP Address updated to: $newIp")
        }
    }

    /**
     * Scans network interfaces to find the device's local IPv4 address on the WiFi network.
     * @return The IP address as a String, or null if not found or not connected to WiFi.
     */
    private fun getLocalIpAddress(): String? {
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val linkProperties = connectivityManager.getLinkProperties(network)
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val ipAddress = linkAddress.address
                    // We want an IPv4 address that is not a loopback address.
                    if (ipAddress is Inet4Address && !ipAddress.isLoopbackAddress) {
                        return ipAddress.hostAddress
                    }
                }
            }
        }
        return null
    }
}
