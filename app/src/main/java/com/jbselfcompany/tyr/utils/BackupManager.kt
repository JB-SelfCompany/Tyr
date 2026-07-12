package com.jbselfcompany.tyr.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import com.jbselfcompany.tyr.utils.TyrLogger
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
    private const val BACKUP_VERSION = 2

    // Backup file extension
    const val BACKUP_FILE_EXTENSION = ".tyrbackup"

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
        val onboardingCompleted: Boolean = true,
        val includesChatDatabase: Boolean = false,
        val chatDatabaseData: String? = null
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
            TyrLogger.e(TAG,"Backup password must be at least 8 characters")
            return false
        }

        return try {
            val configRepo = ConfigRepository(context)

            val databaseData = if (includeDatabase) {
                readDatabaseAsBase64(context)
            } else {
                null
            }
            val chatDatabaseData = readChatDatabaseAsBase64(context)

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
                onboardingCompleted = configRepo.isOnboardingCompleted(),
                includesChatDatabase = chatDatabaseData != null,
                chatDatabaseData = chatDatabaseData
            )

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
                put("includesChatDatabase", backupData.includesChatDatabase)
                if (backupData.chatDatabaseData != null) {
                    put("chatDatabaseData", backupData.chatDatabaseData)
                }
            }

            val plaintext = jsonObject.toString().toByteArray(Charsets.UTF_8)

            val salt = ByteArray(SALT_LENGTH)
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().apply {
                nextBytes(salt)
                nextBytes(iv)
            }

            val key = deriveKey(backupPassword, salt)

            val cipher = Cipher.getInstance(ALGORITHM)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            val ciphertext = cipher.doFinal(plaintext)

            // Write format: salt + iv + ciphertext
            outputStream.write(salt)
            outputStream.write(iv)
            outputStream.write(ciphertext)
            outputStream.flush()

            TyrLogger.i(TAG,"Backup created successfully (size: ${salt.size + iv.size + ciphertext.size} bytes)")
            true
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Failed to create backup", e)
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
            val encryptedData = inputStream.readBytes()

            if (encryptedData.size < SALT_LENGTH + IV_LENGTH + 16) {
                TyrLogger.e(TAG,"Backup file is too small or corrupted")
                return false
            }

            val salt = encryptedData.copyOfRange(0, SALT_LENGTH)
            val iv = encryptedData.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = encryptedData.copyOfRange(SALT_LENGTH + IV_LENGTH, encryptedData.size)

            val key = deriveKey(backupPassword, salt)

            val cipher = Cipher.getInstance(ALGORITHM)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val plaintext = cipher.doFinal(ciphertext)

            val jsonString = String(plaintext, Charsets.UTF_8)
            val jsonObject = JSONObject(jsonString)

            val version = jsonObject.getInt("version")
            if (version > BACKUP_VERSION) {
                TyrLogger.e(TAG,"Backup version $version is not supported (current version: $BACKUP_VERSION)")
                return false
            }

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
                onboardingCompleted = jsonObject.optBoolean("onboardingCompleted", true),
                includesChatDatabase = jsonObject.optBoolean("includesChatDatabase", false),
                chatDatabaseData = jsonObject.optString("chatDatabaseData").takeIf { it.isNotEmpty() }
            )

            val configRepo = ConfigRepository(context)

            if (backupData.password != null) {
                try {
                    configRepo.savePassword(backupData.password)
                } catch (e: Exception) {
                    TyrLogger.e(TAG,"Failed to save password during restore", e)
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

            configRepo.setOnboardingCompleted(backupData.onboardingCompleted)

            if (backupData.includesDatabase && backupData.databaseData != null) {
                writeDatabaseFromBase64(context, backupData.databaseData)
            }

            if (backupData.includesChatDatabase && backupData.chatDatabaseData != null) {
                writeChatDatabaseFromBase64(context, backupData.chatDatabaseData)
            }

            TyrLogger.i(TAG,"Backup restored successfully (timestamp: ${backupData.timestamp})")
            true
        } catch (e: Exception) {
            TyrLogger.e(TAG,"Failed to restore backup", e)
            false
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_SIZE)
        val keyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION)
        return keyFactory.generateSecret(keySpec).encoded
    }

    /**
     * Read yggmail.db and encode as Base64.
     *
     * Runs a WAL checkpoint before reading so that all committed transactions
     * are flushed from the -wal file into the main database file. Without this,
     * a raw file copy of the .db can be missing recent writes that are still
     * only in the write-ahead log.
     */
    private fun readDatabaseAsBase64(context: Context): String? {
        return try {
            val dbFile = File(context.filesDir, "yggmail.db")
            if (!dbFile.exists()) {
                TyrLogger.w(TAG, "Database file does not exist")
                return null
            }

            // Checkpoint and truncate the WAL so all data is in the main file.
            checkpointDatabase(dbFile.absolutePath)

            val bytes = dbFile.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Failed to read database", e)
            null
        }
    }

    /**
     * Read tyr_chat.db and encode as Base64.
     *
     * Runs a WAL checkpoint before reading so that all committed transactions
     * are flushed from the -wal file into the main database file. Without this,
     * a raw file copy of the .db can be missing recent writes that are still
     * only in the write-ahead log — causing the backup to appear empty or
     * out-of-date when restored.
     */
    private fun readChatDatabaseAsBase64(context: Context): String? {
        return try {
            val dbFile = context.getDatabasePath("tyr_chat.db")
            if (!dbFile.exists()) {
                TyrLogger.w(TAG, "Chat database file does not exist")
                return null
            }

            // Checkpoint and truncate the WAL so all data is in the main file.
            checkpointDatabase(dbFile.absolutePath)

            val bytes = dbFile.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Failed to read chat database", e)
            null
        }
    }

    /**
     * Checkpoint a WAL-mode SQLite database, flushing all pending writes into
     * the main database file and truncating the write-ahead log.
     *
     * Opens the database read-write (required for checkpointing), executes
     * PRAGMA wal_checkpoint(TRUNCATE), then closes the connection. This
     * ensures that a subsequent raw file copy of the .db reflects the full
     * committed state.
     */
    private fun checkpointDatabase(absolutePath: String) {
        try {
            SQLiteDatabase.openDatabase(
                absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    // Consume the cursor so the PRAGMA executes to completion.
                    cursor.moveToFirst()
                }
            }
            TyrLogger.d(TAG, "WAL checkpoint completed for $absolutePath")
        } catch (e: Exception) {
            // Non-fatal: the database may not be in WAL mode, or may be
            // temporarily locked. Log and continue — the backup will still
            // include whatever is currently in the main file.
            TyrLogger.w(TAG, "WAL checkpoint failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Write tyr_chat.db from Base64 encoded data.
     *
     * Removes any stale -wal and -shm files after writing the restored
     * database. If leftover WAL files from the old database were present,
     * SQLite would try to apply them on top of the restored data, corrupting
     * or overwriting the restored contents.
     */
    private fun writeChatDatabaseFromBase64(context: Context, base64Data: String): Boolean {
        return try {
            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
            val dbFile = context.getDatabasePath("tyr_chat.db")
            dbFile.parentFile?.mkdirs()
            dbFile.writeBytes(bytes)
            // Remove stale WAL artefacts so SQLite starts clean from the restored file.
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
            TyrLogger.i(TAG, "Chat database restored successfully")
            true
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Failed to write chat database", e)
            false
        }
    }

    /**
     * Write yggmail.db from Base64 encoded data.
     *
     * Removes any stale -wal and -shm files after writing the restored
     * database so SQLite does not apply old WAL entries on top of the
     * restored data.
     */
    private fun writeDatabaseFromBase64(context: Context, base64Data: String): Boolean {
        return try {
            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
            val dbFile = File(context.filesDir, "yggmail.db")
            dbFile.writeBytes(bytes)
            // Remove stale WAL artefacts so SQLite starts clean from the restored file.
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
            TyrLogger.i(TAG, "Database restored successfully")
            true
        } catch (e: Exception) {
            TyrLogger.e(TAG, "Failed to write database", e)
            false
        }
    }

    fun generateBackupFilename(): String {
        val timestamp = System.currentTimeMillis()
        return "tyr_backup_${timestamp}${BACKUP_FILE_EXTENSION}"
    }

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
