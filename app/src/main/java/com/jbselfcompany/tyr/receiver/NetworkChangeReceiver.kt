package com.jbselfcompany.tyr.receiver

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.jbselfcompany.tyr.service.YggmailService
import com.jbselfcompany.tyr.utils.TyrLogger

/**
 * Network callback for detecting network changes (WiFi <-> Mobile Data)
 * Helps maintain stable Yggdrasil connections on mobile devices
 *
 * Battery optimization: Uses 1-second debouncing to prevent rapid reconnection storms
 */
class NetworkChangeReceiver(private val context: Context) : ConnectivityManager.NetworkCallback() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
        private const val RECONNECT_DELAY_MS = 2000L // 2 seconds to let network stabilize
        private const val LOST_CHECK_DELAY_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingNetworkAvailableCheck: Runnable? = null
    private var pendingNetworkLostCheck: Runnable? = null

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        TyrLogger.d(TAG, "Network available: $network")

        // Cancel both pending checks — a new network arrived, no need to handle prior loss
        pendingNetworkAvailableCheck?.let { handler.removeCallbacks(it) }
        pendingNetworkLostCheck?.let { handler.removeCallbacks(it) }

        // Delay to let the network fully establish before reconnecting peers
        pendingNetworkAvailableCheck = Runnable {
            if (YggmailService.isRunning) {
                TyrLogger.i(TAG, "Network available — triggering peer reconnection")
                try {
                    val intent = Intent(context, YggmailService::class.java).apply {
                        action = YggmailService.ACTION_RECONNECT_PEERS
                    }
                    context.startService(intent)
                } catch (e: Exception) {
                    TyrLogger.e(TAG, "Failed to trigger peer reconnection", e)
                }
            }
        }
        handler.postDelayed(pendingNetworkAvailableCheck!!, RECONNECT_DELAY_MS)
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        TyrLogger.d(TAG, "Network lost: $network")

        // Cancel any pending lost check (debounce)
        pendingNetworkLostCheck?.let { handler.removeCallbacks(it) }

        pendingNetworkLostCheck = Runnable {
            if (!hasNetworkConnectivity(context)) {
                TyrLogger.w(TAG, "All networks lost")
                // Service will handle disconnection gracefully
            }
        }
        handler.postDelayed(pendingNetworkLostCheck!!, LOST_CHECK_DELAY_MS)
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

        TyrLogger.d(TAG, "Network capabilities changed: $networkType")
    }

    /**
     * Register network callback for WiFi, Cellular, and Ethernet
     */
    fun register() {
        val connectivityManager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, this)
            TyrLogger.i(TAG, "Network callback registered")
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Unregister network callback
     */
    fun unregister() {
        val connectivityManager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        // Clean up pending callbacks
        pendingNetworkAvailableCheck?.let { handler.removeCallbacks(it) }
        pendingNetworkLostCheck?.let { handler.removeCallbacks(it) }
        pendingNetworkAvailableCheck = null
        pendingNetworkLostCheck = null

        try {
            connectivityManager.unregisterNetworkCallback(this)
            TyrLogger.i(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Failed to unregister network callback", e)
        }
    }

    /**
     * Check if device has any network connectivity
     */
    private fun hasNetworkConnectivity(context: Context): Boolean {
        val connectivityManager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null &&
            (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
    }
}
