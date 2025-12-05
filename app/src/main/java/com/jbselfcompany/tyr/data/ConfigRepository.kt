package com.jbselfcompany.tyr.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.jbselfcompany.tyr.utils.SecurePreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * Repository for managing application configuration stored in SharedPreferences.
 * Handles storage of password (encrypted), peer list, and service state.
 */
class ConfigRepository(private val context: Context) {

    companion object {
        private const val TAG = "ConfigRepository"
        private const val PREFS_NAME = "tyr_config"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_PASSWORD_HASH = "password_hash" // Legacy key for migration
        private const val KEY_PASSWORD_ENCRYPTED = "password_encrypted" // New secure key
        private const val KEY_PEERS = "peers"
        private const val KEY_PEERS_V2 = "peers_v2" // New format with enabled/disabled state
        private const val KEY_USE_DEFAULT_PEERS = "use_default_peers"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_MULTICAST_ENABLED = "multicast_enabled"
        private const val KEY_MAIL_ADDRESS = "mail_address"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_LOG_COLLECTION_ENABLED = "log_collection_enabled"

        // Default Yggdrasil peers
        val DEFAULT_PEERS = listOf(
            "tcp://bra.zbin.eu:7743"
        )

        // Language options
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_RUSSIAN = "ru"

        // Theme options
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Migrate existing plaintext passwords to encrypted storage
        migratePlaintextPassword()
        // Migrate old peer format to new format
        migratePeersToV2()
    }

    /**
     * Migrate existing plaintext password to encrypted storage
     */
    private fun migratePlaintextPassword() {
        try {
            // Check if there's a plaintext password that needs migration
            val plaintextPassword = prefs.getString(KEY_PASSWORD_HASH, null)
            if (!plaintextPassword.isNullOrEmpty() && !SecurePreferences.contains(context, KEY_PASSWORD_ENCRYPTED)) {
                Log.i(TAG, "Migrating plaintext password to encrypted storage")

                // Save to encrypted storage
                SecurePreferences.putString(context, KEY_PASSWORD_ENCRYPTED, plaintextPassword)

                // Remove from plaintext storage
                prefs.edit { remove(KEY_PASSWORD_HASH) }

                Log.i(TAG, "Password migration completed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during password migration", e)
        }
    }

    /**
     * Migrate old peer format (simple strings) to new format (PeerInfo with enabled/disabled)
     */
    private fun migratePeersToV2() {
        try {
            // Check if already migrated
            if (prefs.contains(KEY_PEERS_V2)) {
                return
            }

            // Get old peers
            val oldPeersString = prefs.getString(KEY_PEERS, null)
            if (!oldPeersString.isNullOrEmpty()) {
                Log.i(TAG, "Migrating peers to v2 format")

                val oldPeers = oldPeersString.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                // Convert to new format (all enabled, all custom)
                val newPeers = oldPeers.map { PeerInfo(it, isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM) }

                savePeersV2(newPeers)

                Log.i(TAG, "Peer migration completed successfully: ${newPeers.size} peers")
            } else {
                // No old peers, just mark as migrated with empty list
                savePeersV2(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during peer migration", e)
        }
    }

    /**
     * Check if onboarding wizard has been completed
     */
    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /**
     * Mark onboarding as completed
     */
    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETED, completed) }
    }

    /**
     * Save password (for DeltaChat/IMAP authentication)
     * Password is encrypted using Android Keystore
     */
    fun savePassword(password: String) {
        try {
            SecurePreferences.putString(context, KEY_PASSWORD_ENCRYPTED, password)
            Log.d(TAG, "Password saved securely")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving password", e)
            throw e
        }
    }

    /**
     * Get saved password (decrypted)
     */
    fun getPassword(): String? {
        return try {
            SecurePreferences.getString(context, KEY_PASSWORD_ENCRYPTED, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving password", e)
            null
        }
    }

    /**
     * Check if password is set
     */
    fun hasPassword(): Boolean {
        return try {
            SecurePreferences.contains(context, KEY_PASSWORD_ENCRYPTED)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking password", e)
            false
        }
    }

    /**
     * Check if using default peers
     */
    fun isUsingDefaultPeers(): Boolean {
        return prefs.getBoolean(KEY_USE_DEFAULT_PEERS, true)
    }

    /**
     * Set whether to use default peers
     */
    fun setUseDefaultPeers(useDefault: Boolean) {
        prefs.edit { putBoolean(KEY_USE_DEFAULT_PEERS, useDefault) }
    }

    /**
     * Save list of peers with v2 format (with enabled/disabled state)
     */
    private fun savePeersV2(peers: List<PeerInfo>) {
        try {
            val jsonArray = JSONArray()
            peers.forEach { peer ->
                jsonArray.put(peer.toJson())
            }
            prefs.edit { putString(KEY_PEERS_V2, jsonArray.toString()) }
        } catch (e: JSONException) {
            Log.e(TAG, "Error saving peers v2", e)
        }
    }

    /**
     * Get all peers (with enabled/disabled state)
     */
    fun getAllPeersInfo(): List<PeerInfo> {
        return try {
            val peersJson = prefs.getString(KEY_PEERS_V2, null)
            if (peersJson.isNullOrEmpty()) {
                // Return default peer as enabled
                DEFAULT_PEERS.map { PeerInfo(it, isEnabled = true, tag = PeerInfo.PeerTag.DEFAULT) }
            } else {
                val jsonArray = JSONArray(peersJson)
                val peersList = mutableListOf<PeerInfo>()
                for (i in 0 until jsonArray.length()) {
                    peersList.add(PeerInfo.fromJson(jsonArray.getJSONObject(i)))
                }
                // If empty and using defaults, return default peers
                if (peersList.isEmpty() && isUsingDefaultPeers()) {
                    DEFAULT_PEERS.map { PeerInfo(it, isEnabled = true, tag = PeerInfo.PeerTag.DEFAULT) }
                } else {
                    peersList
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting peers v2", e)
            DEFAULT_PEERS.map { PeerInfo(it, isEnabled = true, tag = PeerInfo.PeerTag.DEFAULT) }
        }
    }

    /**
     * Save peer (add new or update existing)
     */
    fun savePeer(peer: PeerInfo) {
        val peers = getAllPeersInfo().toMutableList()
        val existingIndex = peers.indexOfFirst { it.uri == peer.uri }
        if (existingIndex >= 0) {
            peers[existingIndex] = peer
        } else {
            peers.add(peer)
        }
        savePeersV2(peers)
        setUseDefaultPeers(false)
    }

    /**
     * Remove peer by URI
     */
    fun removePeer(uri: String) {
        val peers = getAllPeersInfo().toMutableList()
        peers.removeAll { it.uri == uri }
        savePeersV2(peers)
    }

    /**
     * Update peer enabled state
     */
    fun setPeerEnabled(uri: String, enabled: Boolean) {
        val peers = getAllPeersInfo().toMutableList()
        val index = peers.indexOfFirst { it.uri == uri }
        if (index >= 0) {
            peers[index] = peers[index].copy(isEnabled = enabled)
            savePeersV2(peers)
        }
    }

    /**
     * Legacy method: Save list of custom Yggdrasil peers as strings
     */
    @Deprecated("Use savePeer() instead")
    fun savePeers(peers: List<String>) {
        val peerInfos = peers.map { PeerInfo(it, isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM) }
        savePeersV2(peerInfos)
        setUseDefaultPeers(false)
    }

    /**
     * Legacy method: Get list of Yggdrasil peers (either default or custom)
     * Returns only enabled peers as strings
     */
    @Deprecated("Use getAllPeersInfo() instead")
    fun getPeers(): List<String> {
        return getEnabledPeers()
    }

    /**
     * Get only enabled peers as strings
     */
    fun getEnabledPeers(): List<String> {
        return if (isUsingDefaultPeers()) {
            DEFAULT_PEERS
        } else {
            val enabledPeers = getAllPeersInfo()
                .filter { it.isEnabled }
                .map { it.uri }

            // If no enabled peers and not using defaults, return empty list
            // This allows multicast-only mode
            enabledPeers
        }
    }

    /**
     * Legacy method: Get custom peers (only returns saved custom peers, not defaults)
     */
    @Deprecated("Use getAllPeersInfo() instead")
    fun getCustomPeers(): List<String> {
        return getAllPeersInfo().map { it.uri }
    }

    /**
     * Get enabled peers as comma-separated string (for Yggmail service)
     */
    fun getPeersString(): String {
        return getEnabledPeers().joinToString(",")
    }

    /**
     * Check if service is enabled
     */
    fun isServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_SERVICE_ENABLED, true)
    }

    /**
     * Set service enabled state
     */
    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SERVICE_ENABLED, enabled) }
    }

    /**
     * Check if auto-start on boot is enabled
     */
    fun isAutoStartEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_START, true)
    }

    /**
     * Set auto-start on boot
     */
    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_START, enabled) }
    }

    /**
     * Check if multicast peer discovery is enabled
     */
    fun isMulticastEnabled(): Boolean {
        return prefs.getBoolean(KEY_MULTICAST_ENABLED, true) // Default to enabled
    }

    /**
     * Set multicast peer discovery
     */
    fun setMulticastEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_MULTICAST_ENABLED, enabled) }
    }

    /**
     * Check if log collection is enabled
     */
    fun isLogCollectionEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOG_COLLECTION_ENABLED, false)
    }

    /**
     * Set log collection enabled/disabled
     */
    fun setLogCollectionEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOG_COLLECTION_ENABLED, enabled) }
    }

    /**
     * Save mail address generated by Yggmail service
     */
    fun saveMailAddress(address: String) {
        prefs.edit { putString(KEY_MAIL_ADDRESS, address) }
    }

    /**
     * Get saved mail address
     */
    fun getMailAddress(): String? {
        return prefs.getString(KEY_MAIL_ADDRESS, null)
    }

    /**
     * Save public key generated by Yggmail service
     */
    fun savePublicKey(pubkey: String) {
        prefs.edit { putString(KEY_PUBLIC_KEY, pubkey) }
    }

    /**
     * Get saved public key
     */
    fun getPublicKey(): String? {
        return prefs.getString(KEY_PUBLIC_KEY, null)
    }

    /**
     * Clear mail address and public key (for key regeneration)
     */
    fun clearKeys() {
        prefs.edit {
            remove(KEY_MAIL_ADDRESS)
            remove(KEY_PUBLIC_KEY)
        }
    }

    /**
     * Clear all configuration data
     */
    fun clearAll() {
        prefs.edit { clear() }
        // Also clear encrypted password
        try {
            SecurePreferences.clear(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing secure storage", e)
        }
    }

    /**
     * Get saved language preference
     * Returns LANGUAGE_SYSTEM by default
     */
    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

    /**
     * Set language preference
     */
    fun setLanguage(language: String) {
        prefs.edit { putString(KEY_LANGUAGE, language) }
    }

    /**
     * Get saved theme preference
     * Returns THEME_SYSTEM by default
     */
    fun getTheme(): String {
        return prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    /**
     * Set theme preference
     */
    fun setTheme(theme: String) {
        prefs.edit { putString(KEY_THEME, theme) }
    }
}
