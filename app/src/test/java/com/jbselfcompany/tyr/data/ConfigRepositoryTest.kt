package com.jbselfcompany.tyr.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.jbselfcompany.tyr.data.PeerInfo.PeerTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Password storage is not covered: SecurePreferences needs AndroidKeyStore, which Robolectric lacks.
@RunWith(RobolectricTestRunner::class)
class ConfigRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo by lazy { ConfigRepository(context) }

    private fun peers() = repo.getAllPeersInfo().map { it.uri to it.isEnabled }

    @Test
    fun migratesLegacyPeerListToV2() {
        context.getSharedPreferences("tyr_config", Context.MODE_PRIVATE).edit {
            putString("peers", " tcp://a:1 \n\ntcp://b:2\n")
        }
        val migrated = repo.getAllPeersInfo()
        assertEquals(listOf("tcp://a:1" to true, "tcp://b:2" to true), peers())
        assertTrue(migrated.all { it.tag == PeerTag.CUSTOM })
    }

    @Test
    fun enabledPeersFallBackToDefaultsOnlyWhenAllowed() {
        assertEquals(ConfigRepository.DEFAULT_PEERS, repo.getEnabledPeers())

        repo.savePeer(PeerInfo("tcp://a:1", isEnabled = true, tag = PeerTag.CUSTOM))
        assertFalse("a custom peer switches defaults off", repo.isUsingDefaultPeers())
        assertEquals(listOf("tcp://a:1"), repo.getEnabledPeers())

        repo.setPeerEnabled("tcp://a:1", false)
        assertEquals("multicast-only mode", emptyList<String>(), repo.getEnabledPeers())
    }

    @Test
    fun applyPickedPeerKeepsOthersDisabledAndParksBackup() {
        repo.savePeer(PeerInfo("tcp://a:1", isEnabled = true, tag = PeerTag.CUSTOM))
        repo.savePeer(PeerInfo("tcp://b:2", isEnabled = true, tag = PeerTag.CUSTOM))

        repo.applyPickedPeer(winner = "tcp://c:3", backup = "tcp://a:1")
        assertEquals(
            listOf("tcp://c:3" to true, "tcp://a:1" to false, "tcp://b:2" to false),
            peers()
        )
        assertEquals("tcp://a:1", repo.getBackupPeer())

        assertTrue(repo.swapToBackup())
        assertEquals(listOf("tcp://a:1"), repo.getEnabledPeers())
        assertEquals("tcp://c:3", repo.getBackupPeer())
    }

    @Test
    fun freshPickReplacesListAndBackupCannotEqualWinner() {
        repo.savePeer(PeerInfo("tcp://old:1", isEnabled = true, tag = PeerTag.CUSTOM))

        repo.applyPickedPeer(winner = "tcp://x:1", backup = "tcp://y:2", fresh = true)
        assertEquals(listOf("tcp://x:1" to true, "tcp://y:2" to false), peers())

        repo.applyPickedPeer(winner = null, backup = "tcp://x:1")
        assertNull(repo.getBackupPeer())
        assertFalse(repo.swapToBackup())
    }

    @Test
    fun imapUidWatermarkNeverDecreases() {
        repo.advanceLastSeenImapUid(10)
        repo.advanceLastSeenImapUid(3)
        assertEquals(10L, repo.getLastSeenImapUid())
    }
}
