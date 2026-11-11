package com.dpcall.app

import com.dpcall.app.domain.IdentityGenerator
import com.dpcall.app.signaling.SdpPayload
import com.dpcall.app.signaling.SignalCodec
import com.dpcall.app.signaling.SignalType
import com.dpcall.app.signaling.relay.CallMessage
import com.dpcall.app.signaling.relay.RelayMessenger
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Полный обмен одного вызова через настоящие релеи, как его ведёт CallManager:
 * offer → ringing → answer → bye, с реальными DPCALL1-строками.
 * Запускается только с DPCALL_LIVE_RELAYS=1.
 */
class LiveCallExchangeTest {

    @Test
    fun offerRingingAnswerBye() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val alice = IdentityGenerator.generate()
        val bob = IdentityGenerator.generate()
        val stranger = IdentityGenerator.generate()
        val aliceInbox = LinkedBlockingQueue<Pair<String, CallMessage>>()
        val bobInbox = LinkedBlockingQueue<Pair<String, CallMessage>>()
        val online = CountDownLatch(3)
        fun messenger(keys: com.dpcall.app.domain.IdentityKeys, inbox: LinkedBlockingQueue<Pair<String, CallMessage>>): RelayMessenger {
            var counted = false
            return RelayMessenger(keys, onMessage = { from, m -> inbox += from to m }, onStatus = { connected, _ ->
                if (connected >= 2 && !counted) { counted = true; online.countDown() }
            }).also { it.start() }
        }
        val a = messenger(alice, aliceInbox)
        val b = messenger(bob, bobInbox)
        val s = messenger(stranger, LinkedBlockingQueue())
        try {
            check(online.await(20, TimeUnit.SECONDS)) { "relays unreachable" }
            Thread.sleep(1500)

            val sdp = "v=0\r\n" + "a=candidate:1 1 udp 2122260223 192.168.1.10 54321 typ host generation 0\r\n".repeat(12)
            val offer = SignalCodec.encode(SignalType.OFFER, SdpPayload(sdp, identity = alice.code, callId = "c1"))
            val answer = SignalCodec.encode(SignalType.ANSWER, SdpPayload(sdp, identity = bob.code, callId = "c1"))

            check(a.send(bob.code, CallMessage(CallMessage.OFFER, "c1", offer)) > 0)
            // Одно событие уходит во все релеи, но получатель видит его один раз.
            val (from1, m1) = bobInbox.poll(10, TimeUnit.SECONDS)!!
            assertEquals(alice.code, from1)
            assertEquals(CallMessage.OFFER, m1.t)
            assertEquals(alice.code, SignalCodec.decode(m1.signal!!).getOrThrow().payload.identity)

            b.send(alice.code, CallMessage(CallMessage.RINGING, "c1"))
            assertEquals(CallMessage.RINGING, aliceInbox.poll(10, TimeUnit.SECONDS)!!.second.t)

            b.send(alice.code, CallMessage(CallMessage.ANSWER, "c1", answer))
            val (from2, m2) = aliceInbox.poll(10, TimeUnit.SECONDS)!!
            assertEquals(bob.code, from2)
            assertEquals(SignalType.ANSWER, SignalCodec.decode(m2.signal!!).getOrThrow().type)

            a.send(bob.code, CallMessage(CallMessage.BYE, "c1"))
            assertEquals(CallMessage.BYE, bobInbox.poll(10, TimeUnit.SECONDS)!!.second.t)

            Thread.sleep(1500)
            // Дубликатов от нескольких релеев нет, посторонний ничего не получил.
            assertEquals(0, bobInbox.size)
            assertEquals(0, aliceInbox.size)
        } finally {
            a.stop(); b.stop(); s.stop()
        }
    }
}
