package com.jbselfcompany.tyr.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerCatalogTest {
    @Test
    fun countryComesFromTheCatalogFileName() {
        assertEquals("Russia", PeerCatalog.countryName("russia.md"))
        assertEquals("United Kingdom", PeerCatalog.countryName("united-kingdom.md"))
    }

    @Test
    fun onlinePeersComeFirstThenFastest() {
        val e = { uri: String, up: Boolean, ms: Int? -> PeerCatalog.Entry(uri, "X", up, ms) }
        val sorted = PeerCatalog.sorted(
            listOf(e("tcp://down", false, 5), e("tcp://slow", true, 300), e("tcp://unknown", true, null), e("tcp://fast", true, 20))
        )
        assertEquals(listOf("tcp://fast", "tcp://slow", "tcp://unknown", "tcp://down"), sorted.map { it.uri })
    }
}
