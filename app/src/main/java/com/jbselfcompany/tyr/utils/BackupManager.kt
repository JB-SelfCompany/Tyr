package com.jbselfcompany.tyr.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.jbselfcompany.tyr.data.ConfigRepository
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages encrypted backup and restore of application configuration.
 * Uses AES-256-GCM with PBKDF2 key derivation for maximum security.
 */
object BackupManager {

    private const val TAG = "BackupManager"

    // Cryptographic constants
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_DERIVATION = "PBKDF2WithHmacSHA256"
    private const val KEY_SIZE = 256
    private const val ITERATION_COUNT = 100000
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 32
    private const val IV_LENGTH = 12

    // Backup format version
    private const val BACKUP_VERSION = 1

    // Backup file extension
    const val BACKUP_FILE_EXTENSION = ".tyrbackup"

    /**
     * Data class representing backup content
     */
    data class BackupData(
        val version: Int,
        val timestamp: Long,
        val password: String?,
        val peers: List<String>,
        val useDefaultPeers: Boolean,
        val autoStart: Boolean,
        val mailAddress: String?,
        val publicKey: String?,
        val includesDatabase: Boolean,
        val databaseData: String? = null,
        val onboardingCompleted: Boolean = true
    )

    /**
     * Create encrypted backup and write to output stream.
     *
     * @param context Application context
     * @param outputStream Output stream to write backup to
     * @param backupPassword Password to encrypt the backup (must be at least 8 characters)
     * @param includeDatabase Whether to include yggmail.db in the backup
     * @return true if backup was successful, false otherwise
     */
    fun createBackup(
        context: Context,
        outputStream: OutputStream,
        backupPassword: String,
        includeDatabase: Boolean = true
    ): Boolean {
        if (backupPassword.length < 8) {
            Log.e(TAG, "Backup password must be at least 8 characters")
            return false
        }

        return try {
            val configRepo = ConfigRepository(context)

            // Read database if requested
            val databaseData = if (includeDatabase) {
                readDatabaseAsBase64(context)
            } else {
                null
            }

            // Create backup data object
            val backupData = BackupData(
                version = BACKUP_VERSION,
                timestamp = System.currentTimeMillis(),
                password = configRepo.getPassword(),
                peers = configRepo.getCustomPeers(),
                useDefaultPeers = configRepo.isUsingDefaultPeers(),
                autoStart = configRepo.isAutoStartEnabled(),
                mailAddress = configRepo.getMailAddress(),
                publicKey = configRepo.getPublicKey(),
                includesDatabase = includeDatabase,
                databaseData = databaseData,
                onboardingCompleted = configRepo.isOnboardingCompleted()
            )

            // Convert to JSON
            val jsonObject = JSONObject().apply {
                put("version", backupData.version)
                put("timestamp", backupData.timestamp)
                put("password", backupData.password ?: "")
                put("peers", backupData.peers.joinToString("\n"))
                put("useDefaultPeers", backupData.useDefaultPeers)
                put("autoStart", backupData.autoStart)
                put("mailAddress", backupData.mailAddress ?: "")
                put("publicKey", backupData.publicKey ?: "")
                put("includesDatabase", backupData.includesDatabase)
                put("onboardingCompleted", backupData.onboardingCompleted)
                if (backupData.databaseData != null) {
                    put("databaseData", backupData.databaseData)
                }
            }

            val plaintext = jsonObject.toString().toByteArray(Charsets.UTF_8)

            // Generate salt and IV
            val salt = ByteArray(SALT_LENGTH)
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().apply {
                nextBytes(salt)
                nextBytes(iv)
            }

            // Derive key from password
            val key = deriveKey(backupPassword, salt)

            // Encrypt data
            val cipher = Cipher.getInstance(ALGORITHM)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            val ciphertext = cipher.doFinal(plaintext)

            // Write to output stream: salt + iv + ciphertext
            outputStream.write(salt)
            outputStream.write(iv)
            outputStream.write(ciphertext)
            outputStream.flush()

            Log.i(TAG, "Backup created successfully (size: ${salt.size + iv.size + ciphertext.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup", e)
            false
        }
    }

    /**
     * Restore configuration from encrypted backup.
     *
     * @param context Application context
     * @param inputStream Input stream to read backup from
     * @param backupPassword Password to decrypt the backup
     * @return true if restore was successful, false otherwise
     */
    fun restoreBackup(
        context: Context,
        inputStream: InputStream,
        backupPassword: String
    ): Boolean {
        return try {
            // Read encrypted data
            val encryptedData = inputStream.readBytes()

            if (encryptedData.size < SALT_LENGTH + IV_LENGTH + 16) {
                Log.e(TAG, "Backup file is too small or corrupted")
                return false
            }

            // Extract salt, IV, and ciphertext
            val salt = encryptedData.copyOfRange(0, SALT_LENGTH)
            val iv = encryptedData.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = encryptedData.copyOfRange(SALT_LENGTH + IV_LENGTH, encryptedData.size)

            // Derive key from password
            val key = deriveKey(backupPassword, salt)

            // Decrypt data
            val cipher = Cipher.getInstance(ALGORITHM)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val plaintext = cipher.doFinal(ciphertext)

            // Parse JSON
            val jsonString = String(plaintext, Charsets.UTF_8)
            val jsonObject = JSONObject(jsonString)

            // Validate version
            val version = jsonObject.getInt("version")
            if (version > BACKUP_VERSION) {
                Log.e(TAG, "Backup version $version is not supported (current version: $BACKUP_VERSION)")
                return false
            }

            // Create backup data object
            val backupData = BackupData(
                version = version,
                timestamp = jsonObject.getLong("timestamp"),
                password = jsonObject.optString("password").takeIf { it.isNotEmpty() },
                peers = jsonObject.optString("peers", "")
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                useDefaultPeers = jsonObject.getBoolean("useDefaultPeers"),
                autoStart = jsonObject.getBoolean("autoStart"),
                mailAddress = jsonObject.optString("mailAddress").takeIf { it.isNotEmpty() },
                publicKey = jsonObject.optString("publicKey").takeIf { it.isNotEmpty() },
                includesDatabase = jsonObject.optBoolean("includesDatabase", false),
                databaseData = jsonObject.optString("databaseData").takeIf { it.isNotEmpty() },
                onboardingCompleted = jsonObject.optBoolean("onboardingCompleted", true)
            )

            // Restore configuration
            val configRepo = ConfigRepository(context)

            if (backupData.password != null) {
                try {
                    configRepo.savePassword(backupData.password)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save password during restore", e)
                    return false
                }
            }

            if (backupData.peers.isNotEmpty()) {
                configRepo.savePeers(backupData.peers)
            }
            configRepo.setUseDefaultPeers(backupData.useDefaultPeers)
            configRepo.setAutoStartEnabled(backupData.autoStart)

            if (backupData.mailAddress != null) {
                configRepo.saveMailAddress(backupData.mailAddress)
            }
            if (backupData.publicKey != null) {
                configRepo.savePublicKey(backupData.publicKey)
            }

            // Restore onboarding completed flag
            configRepo.setOnboardingCompleted(backupData.onboardingCompleted)

            // Restore database if included
            if (backupData.includesDatabase && backupData.databaseData != null) {
                writeDatabaseFromBase64(context, backupData.databaseData)
            }

            Log.i(TAG, "Backup restored successfully (timestamp: ${backupData.timestamp})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup", e)
            false
        }
    }

    /**
     * Derive encryption key from password using PBKDF2.
     */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_SIZE)
        val keyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION)
        return keyFactory.generateSecret(keySpec).encoded
    }

    /**
     * Read yggmail.db and encode as Base64.
     */
    private fun readDatabaseAsBase64(context: Context): String? {
        return try {
            val dbFile = File(context.filesDir, "yggmail.db")
            if (!dbFile.exists()) {
                Log.w(TAG, "Database file does not exist")
                return null
            }

            val bytes = dbFile.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read database", e)
            null
        }
    }

    /**
     * Write yggmail.db from Base64 encoded data.
     */
    private fun writeDatabaseFromBase64(context: Context, base64Data: String): Boolean {
        return try {
            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
            val dbFile = File(context.filesDir, "yggmail.db")
            dbFile.writeBytes(bytes)
            Log.i(TAG, "Database restored successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write database", e)
            false
        }
    }

    /**
     * Generate default backup filename with timestamp.
     */
    fun generateBackupFilename(): String {
        val timestamp = System.currentTimeMillis()
        return "tyr_backup_${timestamp}${BACKUP_FILE_EXTENSION}"
    }

    /**
     * Verify backup password without full restoration.
     * Useful for validating password before proceeding with restore.
     */
    fun verifyBackupPassword(inputStream: InputStream, password: String): Boolean {
        return try {
            val encryptedData = inputStream.readBytes()

            if (encryptedData.size < SALT_LENGTH + IV_LENGTH + 16) {
                return false
            }

            val salt = encryptedData.copyOfRange(0, SALT_LENGTH)
            val iv = encryptedData.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = encryptedData.copyOfRange(SALT_LENGTH + IV_LENGTH, encryptedData.size)

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.doFinal(ciphertext)

            true
        } catch (e: Exception) {
            false
        }
    }
}
