package com.jbselfcompany.tyr.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Utility class for secure storage using EncryptedSharedPreferences.
 * Uses Android Keystore System to encrypt keys and values.
 */
object SecurePreferences {

    private const val SECURE_PREFS_NAME = "tyr_secure_prefs"

    /**
     * Get or create encrypted SharedPreferences instance.
     * Uses AES256-GCM for encryption with keys stored in Android Keystore.
     */
    fun getEncryptedPreferences(context: Context): SharedPreferences {
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
     * Securely store a string value.
     */
    fun putString(context: Context, key: String, value: String?) {
        val prefs = getEncryptedPreferences(context)
        prefs.edit().putString(key, value).apply()
    }

    /**
     * Retrieve a securely stored string value.
     */
    fun getString(context: Context, key: String, defaultValue: String? = null): String? {
        val prefs = getEncryptedPreferences(context)
        return prefs.getString(key, defaultValue)
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
