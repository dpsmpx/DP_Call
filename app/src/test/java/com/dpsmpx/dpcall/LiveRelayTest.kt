package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.signaling.relay.CallMessage
import com.dpsmpx.dpcall.signaling.relay.RelayMessenger
import com.dpsmpx.dpcall.signaling.relay.RelayPool
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
        val candidates = RelayPool.DEFAULT_RELAYS + RelayPool.SPARE_RELAYS + (System.getenv("DPCALL_EXTRA_RELAYS")?.split(',') ?: emptyList())
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

    /**
     * Пул отстраняет релей, который не отвечает OK на отправленные события, — поэтому
     * каждый релей из списков должен их подтверждать.
     */
    @Test
    fun eachRelayConfirmsEvents() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val failed = (RelayPool.DEFAULT_RELAYS + RelayPool.SPARE_RELAYS).filter { relay ->
            val alice = IdentityGenerator.generate()
            val online = CountDownLatch(1)
            val messenger = RelayMessenger(alice, listOf(relay), onMessage = { _, _ -> },
                onStatus = { connected, _ -> if (connected > 0) online.countDown() })
            try {
                messenger.start()
                if (!online.await(15, TimeUnit.SECONDS)) return@filter true
                messenger.send(IdentityGenerator.generate().code, CallMessage(CallMessage.OFFER, "ack", "DPCALL1.O.test"))
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && messenger.relayStates().first().accepted == 0) Thread.sleep(200)
                val accepted = messenger.relayStates().first().accepted
                println("LIVE $relay -> ack ${if (accepted > 0) "OK" else "MISSING"}")
                accepted == 0
            } finally {
                messenger.stop()
            }
        }
        assertTrue("no OK from $failed", failed.isEmpty())
    }

    /**
     * Из России соединение с релеем замирает, когда сервер прислал ~16 КБ, поэтому пул
     * заранее открывает новое. Здесь через одно соединение идёт ~40 КБ: все сообщения
     * должны дойти, а соединение — смениться.
     */
    @Test
    fun manyMessagesSurviveConnectionRotation() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val relay = listOf("wss://relay.snort.social")
        val alice = IdentityGenerator.generate()
        val bob = IdentityGenerator.generate()
        val got = java.util.Collections.synchronizedSet(HashSet<String>())
        val rotations = java.util.concurrent.atomic.AtomicInteger()
        val online = CountDownLatch(2)
        val bobM = RelayMessenger(bob, relay, onMessage = { _, m -> got += m.id },
            onStatus = { c, _ -> if (c > 0) online.countDown() },
            log = { if ("rotating" in it) rotations.incrementAndGet(); println("LIVE   bob $it") })
        val aliceM = RelayMessenger(alice, relay, onMessage = { _, _ -> }, onStatus = { c, _ -> if (c > 0) online.countDown() })
        try {
            bobM.start(); aliceM.start()
            assertTrue(online.await(20, TimeUnit.SECONDS))
            Thread.sleep(1500)
            repeat(8) { n ->
                aliceM.send(bob.code, CallMessage(CallMessage.OFFER, "call-$n", "DPCALL1.O." + "A".repeat(3500)))
                Thread.sleep(1500)
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (got.size < 8 && System.currentTimeMillis() < deadline) Thread.sleep(200)
            println("LIVE rotation -> received ${got.size}/8, rotations=${rotations.get()}")
            assertTrue("received ${got.size}/8", got.size == 8)
            assertTrue("no rotation happened", rotations.get() >= 1)
        } finally {
            bobM.stop(); aliceM.stop()
        }
    }

    @Test
    fun poolDeliversThroughAllRelaysAtOnce() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val ms = deliver(RelayPool.DEFAULT_RELAYS)
        println("LIVE pool -> ${ms?.let { "delivered in $it ms" } ?: "FAILED"}")
        assertTrue(ms != null)
    }

    /**
     * Отметка о пропущенном: отправлена, пока адресата нет на связи, и получена им
     * при подключении — то есть релей её сохранил.
     */
    private fun leaveMissedNote(relays: List<String>): Boolean {
        val alice = IdentityGenerator.generate()
        val bob = IdentityGenerator.generate()
        val aliceOnline = CountDownLatch(1)
        val aliceMessenger = RelayMessenger(alice, relays, onMessage = { _, _ -> },
            onStatus = { connected, _ -> if (connected > 0) aliceOnline.countDown() }, log = { println("LIVE   alice " + it) })
        try {
            aliceMessenger.start()
            if (!aliceOnline.await(15, TimeUnit.SECONDS)) return false
            if (aliceMessenger.send(bob.code, CallMessage(CallMessage.MISSED, "call-missed")) == 0) return false
            Thread.sleep(2000) // событие должно успеть сохраниться
        } finally {
            aliceMessenger.stop()
        }

        val received = CountDownLatch(1)
        var from: String? = null
        val bobMessenger = RelayMessenger(bob, relays, receiveMissed = true, onMessage = { code, message ->
            if (message.t == CallMessage.MISSED && message.id == "call-missed") {
                from = code
                received.countDown()
            }
        }, log = { println("LIVE   bob " + it) })
        try {
            bobMessenger.start()
            if (!received.await(15, TimeUnit.SECONDS)) return false
            check(from == alice.code) { "sender mismatch" }
            return true
        } finally {
            bobMessenger.stop()
        }
    }

    @Test
    fun defaultRelaysKeepMissedCallNotes() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val results = RelayPool.DEFAULT_RELAYS.associateWith { relay ->
            try {
                leaveMissedNote(listOf(relay))
            } catch (e: Throwable) {
                println("LIVE $relay error: $e")
                false
            }
        }
        results.forEach { (relay, ok) -> println("LIVE $relay -> missed note ${if (ok) "kept" else "NOT KEPT"}") }
        // Достаточно одного общего релея, который хранит отметку; требуем запас.
        assertTrue("only ${results.count { it.value }} relays keep missed call notes", results.count { it.value } >= 2)
    }
}
