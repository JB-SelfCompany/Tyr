package com.jbselfcompany.tyr.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTest {
    private val min = 60_000L

    @Test
    fun failoverWaitsTwoMinutesAndThrottles() {
        val t = 100 * min
        assertFalse("peer up", YggmailService.failoverDue(0, t, 0))
        assertFalse("a reconnect blip", YggmailService.failoverDue(t - 1 * min, t, 0))
        assertTrue("down 2 min, first failover", YggmailService.failoverDue(t - 2 * min, t, 0))
        assertFalse("swapped 5 min ago", YggmailService.failoverDue(t - 3 * min, t, t - 5 * min))
        assertTrue("swapped 10 min ago", YggmailService.failoverDue(t - 3 * min, t, t - 10 * min))
    }
}
