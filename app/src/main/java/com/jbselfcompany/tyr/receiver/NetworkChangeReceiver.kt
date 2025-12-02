package com.jbselfcompany.tyr.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.jbselfcompany.tyr.service.YggmailService

/**
 * Network callback for detecting network changes (WiFi <-> Mobile Data)
 * Helps maintain stable Yggdrasil connections on mobile devices
 *
 * Battery optimization: Uses 1-second debouncing to prevent rapid reconnection storms
 */
class NetworkChangeReceiver(private val context: Context) : ConnectivityManager.NetworkCallback() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
        private const val DEBOUNCE_DELAY_MS = 1000L // 1 second debounce
    }

    private var lastNetworkChangeTime = 0L

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.d(TAG, "Network available: $network")

        // Debounce: prevent multiple rapid calls
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < DEBOUNCE_DELAY_MS) {
            Log.d(TAG, "Network change ignored (debounce)")
            return
        }
        lastNetworkChangeTime = now

        // Delay to allow network to stabilize
        Thread {
            Thread.sleep(DEBOUNCE_DELAY_MS)

            if (YggmailService.isRunning) {
                Log.i(TAG, "Network became available - Yggdrasil will auto-reconnect")
                // Note: Yggdrasil core handles peer reconnection automatically
                // We don't need to manually restart transport (prevents ErrClosed errors)
            }
        }.start()
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.d(TAG, "Network lost: $network")

        // Debounce: prevent multiple rapid calls
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < DEBOUNCE_DELAY_MS) {
            Log.d(TAG, "Network change ignored (debounce)")
            return
        }
        lastNetworkChangeTime = now

        // Check if we still have any network connectivity
        Thread {
            Thread.sleep(DEBOUNCE_DELAY_MS)

            if (!hasNetworkConnectivity(context)) {
                Log.w(TAG, "All networks lost")
                // Service will handle disconnection gracefully
            }
        }.start()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, networkCapabilities)

        val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isEthernet = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        val networkType = when {
            isWifi -> "WiFi"
            isCellular -> "Cellular"
            isEthernet -> "Ethernet"
            else -> "Unknown"
        }

        Log.d(TAG, "Network capabilities changed: $networkType")
    }

    /**
     * Register network callback for WiFi, Cellular, and Ethernet
     */
    fun register() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, this)
            Log.i(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Unregister network callback
     */
    fun unregister() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        try {
            connectivityManager.unregisterNetworkCallback(this)
            Log.i(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    /**
     * Check if device has any network connectivity
     */
    private fun hasNetworkConnectivity(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }
}
