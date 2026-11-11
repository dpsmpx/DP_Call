package com.dpcall.app

import com.dpcall.app.domain.IdentityGenerator
import com.dpcall.app.signaling.relay.CallMessage
import com.dpcall.app.signaling.relay.RelayMessenger
import com.dpcall.app.signaling.relay.RelayPool
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Проверка на настоящих релеях: сообщение от одного кода доходит до другого.
 * Требует сети, поэтому запускается только с DPCALL_LIVE_RELAYS=1.
 */
class LiveRelayTest {

    private fun deliver(relays: List<String>, payloadSize: Int = 3000): Long? {
        val alice = IdentityGenerator.generate()
        val bob = IdentityGenerator.generate()
        val received = CountDownLatch(1)
        val bobOnline = CountDownLatch(1)
        val aliceOnline = CountDownLatch(1)
        var from: String? = null
        var got: CallMessage? = null

        val bobMessenger = RelayMessenger(bob, relays, onMessage = { code, message ->
            from = code
            got = message
            received.countDown()
        }, onStatus = { connected, _ -> if (connected > 0) bobOnline.countDown() }, log = { println("LIVE   bob " + it) })
        val aliceMessenger = RelayMessenger(alice, relays, onMessage = { _, _ -> },
            onStatus = { connected, _ -> if (connected > 0) aliceOnline.countDown() }, log = { println("LIVE   alice " + it) })
        try {
            bobMessenger.start()
            aliceMessenger.start()
            if (!bobOnline.await(15, TimeUnit.SECONDS) || !aliceOnline.await(15, TimeUnit.SECONDS)) return null
            Thread.sleep(1500) // подписка должна успеть зарегистрироваться
            val signal = "DPCALL1.O." + "A".repeat(payloadSize)
            val started = System.currentTimeMillis()
            if (aliceMessenger.send(bob.code, CallMessage(CallMessage.OFFER, "call-1", signal)) == 0) return null
            if (!received.await(10, TimeUnit.SECONDS)) return null
            val elapsed = System.currentTimeMillis() - started
            check(from == alice.code) { "sender mismatch" }
            check(got?.signal == signal && got?.id == "call-1") { "payload mismatch" }
            return elapsed
        } finally {
            bobMessenger.stop()
            aliceMessenger.stop()
        }
    }

    @Test
    fun eachDefaultRelayDeliversCalls() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val candidates = RelayPool.DEFAULT_RELAYS + (System.getenv("DPCALL_EXTRA_RELAYS")?.split(',') ?: emptyList())
        val results = candidates.associateWith { relay ->
            try {
                deliver(listOf(relay))
            } catch (e: Throwable) {
                println("LIVE $relay error: $e")
                null
            }
        }
        results.forEach { (relay, ms) -> println("LIVE $relay -> ${ms?.let { "delivered in $it ms" } ?: "FAILED"}") }
        val working = RelayPool.DEFAULT_RELAYS.count { results[it] != null }
        assertTrue("only $working default relays work", working >= RelayPool.DEFAULT_RELAYS.size - 1)
    }

    @Test
    fun poolDeliversThroughAllRelaysAtOnce() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val ms = deliver(RelayPool.DEFAULT_RELAYS)
        println("LIVE pool -> ${ms?.let { "delivered in $it ms" } ?: "FAILED"}")
        assertTrue(ms != null)
    }
}
