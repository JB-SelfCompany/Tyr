package com.jbselfcompany.tyr.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import com.jbselfcompany.tyr.TyrApplication
import com.jbselfcompany.tyr.utils.TyrLogger
import kotlin.math.roundToInt

/**
 * Monitor Yggdrasil peer connections and network statistics.
 * Retrieves peer connection information from Yggmail service and tracks network traffic.
 * Only updates when explicitly started to conserve battery.
 */
class NetworkStatsMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStatsMonitor"
        private const val UPDATE_INTERVAL_MS = 10000L // Update every 10 seconds (battery optimization)
        // Delay for a follow-up poll after monitoring starts, to catch peers that
        // finish reconnecting after a QUIC config change (Doze exit / active state change).
        private const val RECONNECT_CATCHUP_DELAY_MS = 3000L
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
    private var backgroundThread: android.os.HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var isMonitoring = false
    private var measureLatency = true // Only measure latency when app is active
    private var listener: NetworkStatsListener? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                // Run stats update in background thread
                backgroundHandler?.post {
                    updateStats()
                } ?: TyrLogger.w(TAG, "Background handler is null, skipping update")
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Start monitoring network statistics
     */
    fun start(listener: NetworkStatsListener, enableLatencyMeasurement: Boolean = true) {
        if (isMonitoring) {
            // Already monitoring - update the listener and trigger immediate refresh
            TyrLogger.d(TAG, "Already monitoring, updating listener")
            this.listener = listener
            this.measureLatency = enableLatencyMeasurement
            backgroundHandler?.post { updateStats() }
            // Also schedule a follow-up to catch peers still reconnecting
            handler.postDelayed({
                if (isMonitoring) {
                    backgroundHandler?.post { updateStats() }
                }
            }, RECONNECT_CATCHUP_DELAY_MS)
            return
        }

        backgroundThread = android.os.HandlerThread("NetworkStatsMonitor").apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread!!.looper)

        this.listener = listener
        this.measureLatency = enableLatencyMeasurement
        isMonitoring = true

        handler.post(updateRunnable)

        // Schedule a follow-up update to catch peers that finish reconnecting
        // after a QUIC config change (Doze exit / active state transition).
        handler.postDelayed({
            if (isMonitoring) {
                backgroundHandler?.post { updateStats() }
            }
        }, RECONNECT_CATCHUP_DELAY_MS)

        TyrLogger.d(TAG, "Network monitoring started")
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

        backgroundHandler?.removeCallbacksAndMessages(null)
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null

        TyrLogger.d(TAG, "Network monitoring stopped")
    }

    /**
     * Shows all configured peers with latency measurements from Yggdrasil
     */
    private fun updateStats() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val isConnected = activeNetwork != null
            val connectionType = getConnectionType(activeNetwork)

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

            val stats = NetworkStats(
                peers = peers,
                connectionType = connectionType,
                isConnected = isConnected
            )

            handler.post {
                listener?.onStatsUpdated(stats)
            }

        } catch (e: Exception) {
            TyrLogger.e(TAG, "Error updating network stats", e)
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
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> context.getString(com.jbselfcompany.tyr.R.string.connection_type_mobile_data)
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Error getting connection type", e)
            "Unknown"
        }
    }
}
