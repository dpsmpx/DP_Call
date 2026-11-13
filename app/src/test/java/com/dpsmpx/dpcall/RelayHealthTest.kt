package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.signaling.relay.RelayHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHealthTest {

    @Test
    fun repeatedConnectFailuresMakeRelayFailing() {
        val h = RelayHealth()
        repeat(RelayHealth.MAX_FAILURES - 1) { h.onConnectFailure() }
        assertFalse(h.failing)
        h.onConnectFailure()
        assertTrue(h.failing)
    }

    @Test
    fun stableConnectionForgetsEarlierDrops() {
        val h = RelayHealth()
        repeat(RelayHealth.MAX_FAILURES - 1) { h.onConnectFailure() }
        h.onStable()
        h.onConnectFailure()
        assertFalse(h.failing)
    }

    @Test
    fun rejectsAndMissingAcksCount() {
        val rejecting = RelayHealth()
        repeat(RelayHealth.MAX_REJECTS) { rejecting.onAck(false) }
        assertTrue(rejecting.failing)

        val silent = RelayHealth()
        repeat(RelayHealth.MAX_TIMEOUTS) { silent.onAckTimeout() }
        assertTrue(silent.failing)
    }

    @Test
    fun acceptedEventResetsCounters() {
        val h = RelayHealth()
        h.onAck(false)
        h.onAckTimeout()
        h.onAck(true)
        assertFalse(h.failing)
        assertEquals(1, h.accepted)
    }

    @Test
    fun benchDoublesAndExpires() {
        val h = RelayHealth()
        h.bench(0)
        assertTrue(h.isBenched(RelayHealth.BENCH_BASE_MS - 1))
        assertFalse(h.isBenched(RelayHealth.BENCH_BASE_MS))
        assertFalse(h.failing)
        h.bench(0)
        assertTrue(h.isBenched(2 * RelayHealth.BENCH_BASE_MS - 1))
        repeat(10) { h.bench(0) }
        assertEquals(RelayHealth.BENCH_MAX_MS, h.benchedUntil)
        // Принятое событие возвращает обычную длительность отстранения.
        h.onAck(true)
        h.bench(0)
        assertEquals(RelayHealth.BENCH_BASE_MS, h.benchedUntil)
    }

    @Test
    fun sparesFillOnlyMissingPlaces() {
        val primary = listOf("a", "b", "c", "d")
        val spare = listOf("x", "y")
        assertEquals(primary, RelayHealth.choose(primary, spare, 3) { false })
        assertEquals(listOf("c", "d", "x"), RelayHealth.choose(primary, spare, 3) { it == "a" || it == "b" })
        assertEquals(listOf("d", "y"), RelayHealth.choose(primary, spare, 3) { it in setOf("a", "b", "c", "x") })
        // Запасной, совпадающий с основным, не дублируется.
        assertEquals(listOf("a"), RelayHealth.choose(listOf("a"), listOf("a"), 3) { false })
        assertEquals(emptyList<String>(), RelayHealth.choose(primary, emptyList(), 3) { true })
    }
}
