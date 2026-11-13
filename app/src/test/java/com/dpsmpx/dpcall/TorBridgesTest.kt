package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.tor.TorBridges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBridgesTest {
    private val obfs4 = "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=abc iat-mode=0"
    private val webtunnel = "webtunnel [2001:db8::1]:443 0123456789ABCDEF0123456789ABCDEF01234567 url=https://example.org/path ver=0.0.1"

    @Test
    fun parsesBotOutputWithNoise() {
        val text = """
            Here are your bridges:

            Bridge $obfs4
            $webtunnel
            $obfs4
            vanilla 1.2.3.4:443 ABC
            # comment
            obfs4 no-port-here
        """.trimIndent()
        assertEquals(listOf(obfs4, webtunnel), TorBridges.parse(text))
    }

    @Test
    fun torrcUsesOnlyTransportsThatStarted() {
        val torrc = TorBridges.torrc(listOf(obfs4, webtunnel), mapOf("obfs4" to 40001L))
        assertTrue(torrc.startsWith("UseBridges 1\n"))
        assertTrue("ClientTransportPlugin obfs4 socks5 127.0.0.1:40001" in torrc)
        assertTrue("Bridge $obfs4" in torrc)
        assertTrue("webtunnel" !in torrc)
        assertEquals("", TorBridges.torrc(listOf(webtunnel), mapOf("obfs4" to 40001L)))
        assertEquals(setOf("obfs4", "webtunnel"), TorBridges.transportsOf(listOf(obfs4, webtunnel, obfs4)))
    }
}
