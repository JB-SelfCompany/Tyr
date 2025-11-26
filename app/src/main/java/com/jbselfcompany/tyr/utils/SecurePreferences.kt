package com.jbselfcompany.tyr.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * Utility class for secure storage using EncryptedSharedPreferences.
 * Uses Android Keystore System to encrypt keys and values.
 *
 * Implements automatic recovery for known Android Keystore issues on Samsung and other devices.
 * See: https://github.com/google/tink/issues/535
 */
object SecurePreferences {

    private const val TAG = "SecurePreferences"
    private const val SECURE_PREFS_NAME = "tyr_secure_prefs"
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    /**
     * Get or create encrypted SharedPreferences instance.
     * Uses AES256-GCM for encryption with keys stored in Android Keystore.
     *
     * Implements fallback mechanism for Samsung and other devices with Android Keystore issues:
     * - On first failure, attempts to delete corrupted master key and recreate
     * - This is a known issue with EncryptedSharedPreferences on some devices
     */
    @Synchronized
    fun getEncryptedPreferences(context: Context): SharedPreferences {
        return try {
            createEncryptedPreferences(context)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "GeneralSecurityException during EncryptedSharedPreferences creation. Attempting recovery...", e)
            handleKeystoreException(context, e)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during EncryptedSharedPreferences creation. Attempting recovery...", e)
            handleKeystoreException(context, e)
        }
    }

    /**
     * Create EncryptedSharedPreferences instance
     */
    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Handle Android Keystore exceptions by deleting corrupted data and recreating.
     * This is necessary for Samsung and other devices with Keystore implementation issues.
     *
     * References:
     * - https://github.com/google/tink/issues/535
     * - https://stackoverflow.com/questions/65463893
     */
    private fun handleKeystoreException(context: Context, originalException: Exception): SharedPreferences {
        Log.w(TAG, "Keystore exception detected. Clearing corrupted encrypted preferences and master key...")

        try {
            // Delete corrupted SharedPreferences file
            deleteSharedPreferences(context)

            // Delete corrupted master key from Android Keystore
            deleteMasterKey()

            Log.i(TAG, "Successfully cleared corrupted data. Recreating encrypted preferences...")

            // Recreate encrypted preferences
            return createEncryptedPreferences(context)
        } catch (recoveryException: Exception) {
            Log.e(TAG, "Failed to recover from Keystore exception. Original exception:", originalException)
            Log.e(TAG, "Recovery exception:", recoveryException)
            throw RuntimeException("Unable to create secure storage. This may be a device-specific Keystore issue.", recoveryException)
        }
    }

    /**
     * Delete the encrypted SharedPreferences file
     */
    private fun deleteSharedPreferences(context: Context) {
        try {
            val prefsFile = File(context.applicationInfo.dataDir + "/shared_prefs/${SECURE_PREFS_NAME}.xml")
            if (prefsFile.exists()) {
                val deleted = prefsFile.delete()
                Log.d(TAG, "Encrypted preferences file deleted: $deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting SharedPreferences file", e)
        }
    }

    /**
     * Delete the master key from Android Keystore
     */
    private fun deleteMasterKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
                Log.d(TAG, "Master key deleted from Keystore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting master key from Keystore", e)
        }
    }

    /**
     * Securely store a string value.
     */
    fun putString(context: Context, key: String, value: String?) {
        try {
            val prefs = getEncryptedPreferences(context)
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save string value for key: $key", e)
            throw e
        }
    }

    /**
     * Retrieve a securely stored string value.
     */
    fun getString(context: Context, key: String, defaultValue: String? = null): String? {
        return try {
            val prefs = getEncryptedPreferences(context)
            prefs.getString(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve string value for key: $key", e)
            throw e
        }
    }

    /**
     * Remove a securely stored value.
     */
    fun remove(context: Context, key: String) {
        val prefs = getEncryptedPreferences(context)
        prefs.edit().remove(key).apply()
    }

    /**
     * Check if a key exists in secure storage.
     */
    fun contains(context: Context, key: String): Boolean {
        val prefs = getEncryptedPreferences(context)
        return prefs.contains(key)
    }

    /**
     * Clear all securely stored values.
     */
    fun clear(context: Context) {
        val prefs = getEncryptedPreferences(context)
        prefs.edit().clear().apply()
    }
}
