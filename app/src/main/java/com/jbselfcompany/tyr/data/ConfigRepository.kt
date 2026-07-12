package com.jbselfcompany.tyr.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.jbselfcompany.tyr.utils.SecurePreferences
import com.jbselfcompany.tyr.utils.TyrLogger
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
        private const val KEY_MAIL_ADDRESS = "mail_address"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_LOG_COLLECTION_ENABLED = "log_collection_enabled"
        private const val KEY_CACHED_DISCOVERED_PEERS = "cached_discovered_peers"
        private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"

        // Update check settings
        private const val KEY_UPDATE_CHECK_ENABLED = "update_check_enabled"
        private const val KEY_UPDATE_CHECK_INTERVAL_HOURS = "update_check_interval_hours"
        private const val KEY_LAST_UPDATE_CHECK_TIME = "last_update_check_time"
        private const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"

        // Chat settings
        private const val KEY_ACCEPT_MESSAGES_FROM_NON_CONTACTS = "accept_messages_non_contacts"

        // Max message size cache (MB) — mirrors the yggmail quota setting
        private const val KEY_MAX_MESSAGE_SIZE_MB = "max_message_size_mb"

        // Highest IMAP UID ever seen — never decreases even when messages are deleted.
        // Used to prevent re-fetching old messages after a contact is deleted.
        private const val KEY_LAST_SEEN_IMAP_UID = "last_seen_imap_uid"
        const val DEFAULT_MAX_MESSAGE_SIZE_MB = 500L

        // Update check interval options
        const val UPDATE_INTERVAL_ON_START = 0
        const val UPDATE_INTERVAL_DAILY = 24
        const val UPDATE_INTERVAL_WEEKLY = 168

        // Cache TTL for discovered peers (24 hours)
        private const val CACHE_TTL_HOURS = 24

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
        migratePlaintextPassword()
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
                TyrLogger.i(TAG,"Migrating plaintext password to encrypted storage")

                // Save to encrypted storage
                SecurePreferences.putString(context, KEY_PASSWORD_ENCRYPTED, plaintextPassword)

                // Remove from plaintext storage
                prefs.edit { remove(KEY_PASSWORD_HASH) }

                TyrLogger.i(TAG,"Password migration completed successfully")
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error during password migration", e)
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
                TyrLogger.i(TAG,"Migrating peers to v2 format")

                val oldPeers = oldPeersString.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                // Convert to new format (all enabled, all custom)
                val newPeers = oldPeers.map { PeerInfo(it, isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM) }

                savePeersV2(newPeers)

                TyrLogger.i(TAG,"Peer migration completed successfully: ${newPeers.size} peers")
            } else {
                // No old peers, just mark as migrated with empty list
                savePeersV2(emptyList())
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error during peer migration", e)
        }
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

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
            TyrLogger.d(TAG,"Password saved securely")
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error saving password", e)
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
            TyrLogger.e(TAG,"Error retrieving password", e)
            null
        }
    }

    fun hasPassword(): Boolean {
        return try {
            SecurePreferences.contains(context, KEY_PASSWORD_ENCRYPTED)
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error checking password", e)
            false
        }
    }

    fun isUsingDefaultPeers(): Boolean {
        return prefs.getBoolean(KEY_USE_DEFAULT_PEERS, true)
    }

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
            TyrLogger.e(TAG,"Error saving peers v2", e)
        }
    }

    /**
     * Get all peers (with enabled/disabled state)
     * Returns only custom saved peers, does NOT return defaults
     */
    fun getAllPeersInfo(): List<PeerInfo> {
        return try {
            val peersJson = prefs.getString(KEY_PEERS_V2, null)
            if (peersJson.isNullOrEmpty()) {
                // Return empty list - defaults should be handled by getEnabledPeers()
                emptyList()
            } else {
                val jsonArray = JSONArray(peersJson)
                val peersList = mutableListOf<PeerInfo>()
                for (i in 0 until jsonArray.length()) {
                    peersList.add(PeerInfo.fromJson(jsonArray.getJSONObject(i)))
                }
                peersList
            }
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error getting peers v2", e)
            emptyList()
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

        // Only disable default peers if saving a custom peer
        if (peer.tag != PeerInfo.PeerTag.DEFAULT) {
            setUseDefaultPeers(false)
        }
    }

    fun removePeer(uri: String) {
        val peers = getAllPeersInfo().toMutableList()
        peers.removeAll { it.uri == uri }
        savePeersV2(peers)
    }

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
     * Prioritizes custom peers over defaults
     */
    fun getEnabledPeers(): List<String> {
        // First, get all custom saved peers
        val customPeers = getAllPeersInfo()
            .filter { it.isEnabled }
            .map { it.uri }

        // If there are any enabled custom peers, use ONLY them (ignore defaults)
        if (customPeers.isNotEmpty()) {
            return customPeers
        }

        // No custom peers - check if we should use defaults
        return if (isUsingDefaultPeers()) {
            DEFAULT_PEERS
        } else {
            // No custom peers and not using defaults - return empty list
            // This allows multicast-only mode
            emptyList()
        }
    }

    /**
     * Legacy method: Get custom peers (only returns saved custom peers, not defaults)
     */
    @Deprecated("Use getAllPeersInfo() instead")
    fun getCustomPeers(): List<String> {
        return getAllPeersInfo().map { it.uri }
    }

    fun getPeersString(): String {
        return getEnabledPeers().joinToString(",")
    }

    fun isServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_SERVICE_ENABLED, true)
    }

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SERVICE_ENABLED, enabled) }
    }

    fun isAutoStartEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_START, true)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_START, enabled) }
    }

    fun isLogCollectionEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOG_COLLECTION_ENABLED, false)
    }

    /**
     * Set log collection enabled/disabled.
     * Takes effect immediately — no service restart required.
     */
    fun setLogCollectionEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOG_COLLECTION_ENABLED, enabled) }
        TyrLogger.setEnabled(enabled)
    }

    fun getNickname(): String = prefs.getString("my_nickname", "") ?: ""

    fun setNickname(nickname: String) {
        prefs.edit { putString("my_nickname", nickname) }
    }

    fun saveMailAddress(address: String) {
        prefs.edit { putString(KEY_MAIL_ADDRESS, address) }
    }

    fun getMailAddress(): String? {
        return prefs.getString(KEY_MAIL_ADDRESS, null)
    }

    fun savePublicKey(pubkey: String) {
        prefs.edit { putString(KEY_PUBLIC_KEY, pubkey) }
    }

    fun getPublicKey(): String? {
        return prefs.getString(KEY_PUBLIC_KEY, null)
    }

    fun clearKeys() {
        prefs.edit {
            remove(KEY_MAIL_ADDRESS)
            remove(KEY_PUBLIC_KEY)
        }
    }

    fun clearAll() {
        prefs.edit { clear() }
        // Also clear encrypted password
        try {
            SecurePreferences.clear(context)
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Error clearing secure storage", e)
        }
    }

    /**
     * Get saved language preference
     * Returns LANGUAGE_SYSTEM by default
     */
    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

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

    fun setTheme(theme: String) {
        prefs.edit { putString(KEY_THEME, theme) }
    }

    // ============================================
    // Update Check Settings
    // ============================================

    fun isUpdateCheckEnabled(): Boolean =
        prefs.getBoolean(KEY_UPDATE_CHECK_ENABLED, true)

    fun setUpdateCheckEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_UPDATE_CHECK_ENABLED, enabled) }
    }

    /** Returns interval in hours: 0 = on every app start, 24 = daily, 168 = weekly */
    fun getUpdateCheckIntervalHours(): Int =
        prefs.getInt(KEY_UPDATE_CHECK_INTERVAL_HOURS, UPDATE_INTERVAL_DAILY)

    fun setUpdateCheckIntervalHours(hours: Int) {
        prefs.edit { putInt(KEY_UPDATE_CHECK_INTERVAL_HOURS, hours) }
    }

    fun getLastUpdateCheckTime(): Long =
        prefs.getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)

    fun setLastUpdateCheckTime(time: Long) {
        prefs.edit { putLong(KEY_LAST_UPDATE_CHECK_TIME, time) }
    }

    fun getDismissedUpdateVersion(): String? =
        prefs.getString(KEY_DISMISSED_UPDATE_VERSION, null)

    fun setDismissedUpdateVersion(version: String) {
        prefs.edit { putString(KEY_DISMISSED_UPDATE_VERSION, version) }
    }

    /**
     * Returns true if an update check should be performed now,
     * based on enabled flag and the configured interval.
     */
    fun shouldCheckForUpdates(): Boolean {
        if (!isUpdateCheckEnabled()) return false
        val lastCheck = getLastUpdateCheckTime()
        if (lastCheck == 0L) return true
        val intervalMs = getUpdateCheckIntervalHours() * 60L * 60_000L
        if (intervalMs == 0L) return true // always check on start
        return System.currentTimeMillis() - lastCheck > intervalMs
    }

    // ============================================
    // Chat Settings
    // ============================================

    /** If true, messages from senders not in contacts list will be shown with an accept/decline prompt. */
    fun getAcceptMessagesFromNonContacts(): Boolean =
        prefs.getBoolean(KEY_ACCEPT_MESSAGES_FROM_NON_CONTACTS, true)

    fun setAcceptMessagesFromNonContacts(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ACCEPT_MESSAGES_FROM_NON_CONTACTS, enabled) }
    }

    // ============================================
    // Max Message Size Cache
    // ============================================

    /** Returns cached max incoming message size in MB (from yggmail quota setting). */
    fun getCachedMaxMessageSizeMB(): Long =
        prefs.getLong(KEY_MAX_MESSAGE_SIZE_MB, DEFAULT_MAX_MESSAGE_SIZE_MB)

    /** Cache the current yggmail quota (call after setting/loading quota from service). */
    fun cacheMaxMessageSizeMB(sizeMB: Long) {
        prefs.edit { putLong(KEY_MAX_MESSAGE_SIZE_MB, sizeMB) }
    }

    // ============================================
    // IMAP UID watermark
    // ============================================

    /**
     * Returns the highest IMAP UID ever successfully fetched.
     * This value never decreases — it is independent of the messages table,
     * so deleting a contact's messages cannot cause old UIDs to be re-fetched.
     */
    fun getLastSeenImapUid(): Long =
        prefs.getLong(KEY_LAST_SEEN_IMAP_UID, 0L)

    /**
     * Update the watermark. Only advances — never goes backwards.
     */
    fun advanceLastSeenImapUid(uid: Long) {
        if (uid > getLastSeenImapUid()) {
            prefs.edit { putLong(KEY_LAST_SEEN_IMAP_UID, uid) }
        }
    }

}
