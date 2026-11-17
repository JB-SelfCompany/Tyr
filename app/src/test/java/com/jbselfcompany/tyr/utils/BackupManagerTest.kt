package com.jbselfcompany.tyr.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.jbselfcompany.tyr.data.ConfigRepository
import com.jbselfcompany.tyr.data.PeerInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// yggmail.db is absent here, so the native exportIdentityDb path is never hit;
// the password field stays empty because SecurePreferences needs AndroidKeyStore.
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct horse"

    private fun backup(password: String = pass, includeChat: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(
            BackupManager.createBackup(
                context,
                out,
                password,
                includeMail = false,
                includeChat = includeChat
            )
        )
        return out.toByteArray()
    }

    @Test
    fun roundTripRestoresConfigAndChatDatabase() {
        ConfigRepository(context).apply {
            savePeer(PeerInfo("tcp://a:1", isEnabled = true, tag = PeerInfo.PeerTag.CUSTOM))
            setAutoStartEnabled(false)
            saveMailAddress("me@yggmail")
            savePublicKey("pubkey")
            setOnboardingCompleted(true)
        }
        val chatDb = context.getDatabasePath("tyr_chat.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(chatDb, null).use { db ->
            db.execSQL("CREATE TABLE m (body TEXT)")
            db.insert("m", null, ContentValues().apply { put("body", "hello") })
        }
        val blob = backup(includeChat = true)

        ConfigRepository(context).clearAll()
        context.deleteDatabase("tyr_chat.db")
        assertTrue(BackupManager.restoreBackup(context, ByteArrayInputStream(blob), pass))

        ConfigRepository(context).apply {
            assertEquals(listOf("tcp://a:1"), getEnabledPeers())
            assertFalse(isAutoStartEnabled())
            assertEquals("me@yggmail", getMailAddress())
            assertEquals("pubkey", getPublicKey())
            assertTrue(isOnboardingCompleted())
        }
        SQLiteDatabase.openDatabase(chatDb.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT body FROM m", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("hello", c.getString(0))
            }
        }
    }

    @Test
    fun wrongPasswordIsRejectedAndChangesNothing() {
        ConfigRepository(context).saveMailAddress("me@yggmail")
        val blob = backup()
        ConfigRepository(context).saveMailAddress("other@yggmail")

        assertFalse(
            BackupManager.verifyBackupPassword(ByteArrayInputStream(blob), "wrong password")
        )
        assertFalse(
            BackupManager.restoreBackup(context, ByteArrayInputStream(blob), "wrong password")
        )
        assertEquals("other@yggmail", ConfigRepository(context).getMailAddress())
        assertTrue(BackupManager.verifyBackupPassword(ByteArrayInputStream(blob), pass))
    }

    @Test
    fun saltAndIvMakeEveryBackupUnique() {
        val a = backup()
        val b = backup()
        assertNotEquals(a.copyOfRange(0, 44).toList(), b.copyOfRange(0, 44).toList())
    }

    @Test
    fun rejectsShortPasswordAndDamagedFiles() {
        val out = ByteArrayOutputStream()
        assertFalse(
            BackupManager.createBackup(
                context,
                out,
                "1234567",
                includeMail = false,
                includeChat = false
            )
        )
        assertArrayEquals("nothing written", ByteArray(0), out.toByteArray())

        val blob = backup()
        val truncated = blob.copyOfRange(0, 50)
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(BackupManager.restoreBackup(context, ByteArrayInputStream(truncated), pass))
        assertFalse(BackupManager.restoreBackup(context, ByteArrayInputStream(tampered), pass))
    }
}
