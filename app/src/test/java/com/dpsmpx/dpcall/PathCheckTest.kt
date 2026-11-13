package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.webrtc.PathCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathCheckTest {
    private val host = "candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host generation 0" to null
    private val srflx = "candidate:2 1 udp 1686052607 203.0.113.7 50000 typ srflx raddr 192.168.1.5 rport 50000" to "stun:stun.l.google.com:19302"
    private val publicRelay = "candidate:3 1 udp 41885439 198.51.100.9 60000 typ relay raddr 203.0.113.7 rport 50000" to
        "turn:staticauth.openrelay.metered.ca:80?transport=udp"
    private val ownRelay = "candidate:4 1 udp 41885439 192.0.2.10 61000 typ relay raddr 203.0.113.7 rport 50000" to
        "turns:turn.example.org:443?transport=tcp"

    @Test
    fun onlyLocalAddressesMeansBlocked() {
        val check = PathCheck.classify(listOf(host), null, 1)
        assertFalse(check.direct)
        assertFalse(check.turn)
        assertTrue(check.blocked)
        assertNull(check.ownTurn)
    }

    @Test
    fun stunAndPublicTurn() {
        val check = PathCheck.classify(listOf(host, srflx, publicRelay), "turn:turn.example.org:3478", 1)
        assertTrue(check.direct)
        assertTrue(check.turn)
        assertFalse(check.blocked)
        assertEquals(false, check.ownTurn)
    }

    @Test
    fun ownTurnRecognisedByHost() {
        val check = PathCheck.classify(listOf(host, ownRelay), "turn:TURN.example.org:3478", 1)
        assertFalse(check.direct)
        assertTrue(check.turn)
        assertEquals(true, check.ownTurn)
    }

    @Test
    fun hostParsing() {
        assertEquals("turn.example.org", PathCheck.hostOf("turns:turn.example.org:443?transport=tcp"))
        assertEquals("turn.example.org", PathCheck.hostOf("turn:turn.example.org"))
        assertEquals("2001:db8::1", PathCheck.hostOf("turn:[2001:db8::1]:3478"))
    }
}
