package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.util.NetworkTest
import com.dpsmpx.dpcall.util.NetworkTest.RelayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTestTest {
    @Test
    fun relayVerdicts() {
        assertEquals(RelayResult.NO_CONNECTION, NetworkTest.classifyRelay(connected = false, bytes = 0, completed = false))
        assertEquals(RelayResult.FROZE, NetworkTest.classifyRelay(connected = true, bytes = 12_000, completed = false))
        assertEquals(RelayResult.INCONCLUSIVE, NetworkTest.classifyRelay(connected = true, bytes = 4_000, completed = true))
        assertEquals(RelayResult.INCONCLUSIVE, NetworkTest.classifyRelay(connected = true, bytes = 0, completed = false))
        assertEquals(RelayResult.OK, NetworkTest.classifyRelay(connected = true, bytes = 31_000, completed = false))
    }

    @Test
    fun stunBindingRequestAndResponse() {
        val id = ByteArray(12) { it.toByte() }
        val request = NetworkTest.stunRequest(id)
        assertEquals(20, request.size)
        assertEquals(0x2112A442, java.nio.ByteBuffer.wrap(request, 4, 4).int)
        val response = byteArrayOf(0x01, 0x01, 0x00, 0x00) + request.copyOfRange(4, 20) + ByteArray(12)
        assertTrue(NetworkTest.isStunResponse(response, response.size, id))
        assertFalse(NetworkTest.isStunResponse(response, response.size, ByteArray(12)))
        assertFalse(NetworkTest.isStunResponse(request, request.size, id))
    }
}
