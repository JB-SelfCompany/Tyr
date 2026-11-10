package com.jbselfcompany.tyr.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerInfoTest {
    @Test
    fun peerUrlPortIsReadFromTheAuthorityOnly() {
        assertTrue(PeerInfo.isValidPeerUrl("tcp://example.org:8080"))
        assertTrue("pinned key in the query", PeerInfo.isValidPeerUrl("tcp://example.org:8080?key=000000000143db657d1d"))
        assertTrue(PeerInfo.isValidPeerUrl("tls://[2001:db8::1]:443?sni=example.org"))
        assertTrue("socks target after the proxy", PeerInfo.isValidPeerUrl("socks://127.0.0.1:9050/example.org:8080"))
        assertFalse("no port", PeerInfo.isValidPeerUrl("tcp://example.org?key=00"))
        assertFalse("port out of range", PeerInfo.isValidPeerUrl("tcp://example.org:70000"))
        assertFalse("injection", PeerInfo.isValidPeerUrl("tcp://example.org:8080;rm"))
    }
}
