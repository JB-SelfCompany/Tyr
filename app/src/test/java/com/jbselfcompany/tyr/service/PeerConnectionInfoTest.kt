package com.jbselfcompany.tyr.service

import com.jbselfcompany.tyr.service.YggmailService.PeerConnectionInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerConnectionInfoTest {
    private fun peer(uri: String) = PeerConnectionInfo(uri, true, false, "", "", 0, 0, 0, 0, 0, 0)

    @Test
    fun hostAndPortComeFromTheAuthorityOnly() {
        assertEquals(
            "yggdrasil.su" to 62586,
            peer("tls://yggdrasil.su:62586?key=00ab").let {
                it.host to
                    it.port
            }
        )
        assertEquals("1.2.3.4" to 7743, peer("tcp://1.2.3.4:7743").let { it.host to it.port })
        assertEquals("::1" to 7743, peer("quic://[::1]:7743").let { it.host to it.port })
        assertEquals(
            "proxy" to 1080,
            peer("socks://proxy:1080/host:9000").let {
                it.host to it.port
            }
        )
        assertEquals("no port", 0, peer("tls://example.org?key=00").port)
    }
}
