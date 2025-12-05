package com.jbselfcompany.tyr.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jbselfcompany.tyr.TyrApplication
import kotlin.math.roundToInt

/**
 * Monitor Yggdrasil peer connections and network statistics.
 * Retrieves peer connection information from Yggmail service and tracks network traffic.
 * Only updates when explicitly started to conserve battery.
 */
class NetworkStatsMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStatsMonitor"
        private const val UPDATE_INTERVAL_MS = 3000L // Update every 3 seconds
    }

    interface NetworkStatsListener {
        fun onStatsUpdated(stats: NetworkStats)
    }

    data class PeerInfo(
        val host: String,
        val port: Int,
        val connected: Boolean = false,
        val latencyMs: Long = -1 // -1 means not measured yet
    )

    data class NetworkStats(
        val peers: List<PeerInfo> = emptyList(),
        val connectionType: String = "Unknown",
        val isConnected: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val backgroundHandler = Handler(android.os.HandlerThread("NetworkStatsMonitor").apply { start() }.looper)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var isMonitoring = false
    private var measureLatency = true // Only measure latency when app is active
    private var listener: NetworkStatsListener? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                // Run stats update in background thread
                backgroundHandler.post {
                    updateStats()
                }
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Start monitoring network statistics
     */
    fun start(listener: NetworkStatsListener, enableLatencyMeasurement: Boolean = true) {
        if (isMonitoring) {
            Log.w(TAG, "Already monitoring")
            return
        }

        this.listener = listener
        this.measureLatency = enableLatencyMeasurement
        isMonitoring = true

        // Start periodic updates
        handler.post(updateRunnable)

        Log.d(TAG, "Network monitoring started")
    }

    /**
     * Stop monitoring network statistics
     */
    fun stop() {
        if (!isMonitoring) {
            return
        }

        isMonitoring = false
        handler.removeCallbacks(updateRunnable)
        listener = null

        Log.d(TAG, "Network monitoring stopped")
    }

    /**
     * Shows all configured peers with latency measurements from Yggdrasil
     */
    private fun updateStats() {
        try {
            // Get connection info
            val activeNetwork = connectivityManager.activeNetwork
            val isConnected = activeNetwork != null
            val connectionType = getConnectionType(activeNetwork)

            // Get peer connections from Yggmail service
            val yggmailService = TyrApplication.instance.yggmailServiceBinder?.getService()
            val peers = if (yggmailService != null) {
                val peerConnections = yggmailService.getPeerConnections()
                peerConnections?.map { peerConn ->
                    // Yggdrasil measures actual transport layer latency
                    PeerInfo(
                        host = peerConn.host,
                        port = peerConn.port,
                        connected = peerConn.connected,
                        latencyMs = peerConn.latencyMs
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }

            // Create stats object
            val stats = NetworkStats(
                peers = peers,
                connectionType = connectionType,
                isConnected = isConnected
            )

            // Notify listener on main thread
            handler.post {
                listener?.onStatsUpdated(stats)
            }

            Log.d(TAG, "Updated stats: ${peers.size} peers")

        } catch (e: Exception) {
            Log.e(TAG, "Error updating network stats", e)
        }
    }

    /**
     * Get connection type name
     */
    private fun getConnectionType(network: android.net.Network?): String {
        if (network == null) {
            return "Disconnected"
        }

        return try {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            when {
                capabilities == null -> "Unknown"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connection type", e)
            "Unknown"
        }
    }
}
