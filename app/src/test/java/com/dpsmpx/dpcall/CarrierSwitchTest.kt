package com.dpsmpx.dpcall

import android.content.Context
import com.dpsmpx.dpcall.carrier.CarrierBackend
import com.dpsmpx.dpcall.carrier.CarrierTransport
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.data.IdentityRepository
import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.signaling.relay.CallMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class CarrierSwitchTest {
    @Test
    fun roomFailureRetryAndLeaveDoNotMixSignalingSessionsOrReplaceIdentity() {
        val context: Context = RuntimeEnvironment.getApplication()
        val keys = IdentityGenerator.generate()
        val peer = IdentityGenerator.generate().code
        // Avoid AndroidKeyStore, which the JVM does not provide. Exercise the real
        // identity repository's cached-key path, not a replacement identity loader.
        val cache = IdentityRepository::class.java.getDeclaredField("cached").apply { isAccessible = true }
        cache.set(null, keys)
        val settings = AppSettings.get(context)
        settings.setTorSignaling(false)
        assertTrue(settings.setRelays("wss://127.0.0.1:9\nwss://127.0.0.1:10"))
        assertFalse(CarrierTransport.active)
        RelaySignaling.restart(context)
        await { RelaySignaling.relayStates().isNotEmpty() }

        val sessions = CopyOnWriteArrayList<FakeBackend>()
        val originalFactory = CarrierTransport.createBackend
        CarrierTransport.createBackend = { _, _, status, _ ->
            assertTrue("previous native session must close before retry", sessions.all { it.closed })
            FakeBackend(status).also { sessions += it }
        }
        try {
            settings.setTorSignaling(true)
            CarrierTransport.connect(context, "https://telemost.yandex.ru/j/test-room", peer)
            await { sessions.size == 1 && sessions[0].started }
            assertTrue(CarrierTransport.active)
            assertTrue("room selection must preserve the user's Tor preference", settings.torSignaling.value)
            assertTrue(RelaySignaling.relayStates().isEmpty())
            assertEquals(1, RelaySignaling.status.value.total)
            val first = sessions[0]
            first.status("peer_ready")
            await { CarrierTransport.ready }
            assertEquals(1, RelaySignaling.send(peer, CallMessage(CallMessage.RINGING, "test")))
            assertEquals(0, RelaySignaling.send(keys.code, CallMessage(CallMessage.RINGING, "test")))
            first.status("failed")
            await { CarrierTransport.status.value.phase == "failed" }
            assertTrue("failure must stay in room mode", CarrierTransport.active)
            assertFalse(CarrierTransport.ready)
            assertEquals(0, RelaySignaling.send(peer, CallMessage(CallMessage.RINGING, "test")))
            assertTrue(RelaySignaling.relayStates().isEmpty())

            CarrierTransport.connect(context, "https://vk.ru/call/join/test-room", peer)
            await { sessions.size == 2 && sessions[1].started }
            assertTrue(first.closed)
            first.status("peer_ready")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("late heartbeat from old room must be ignored", CarrierTransport.ready)
            sessions[1].status("peer_ready")
            await { CarrierTransport.ready }

            // Do not boot native Tor in a JVM; both APKs exercise the real normal
            // relay restart. The saved Tor preference above was not changed by joining.
            settings.setTorSignaling(false)
            CarrierTransport.disconnect(context)
            await { sessions[1].closed && RelaySignaling.relayStates().isNotEmpty() }
            sessions[1].status("failed")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(CarrierTransport.active)
            assertEquals("off", CarrierTransport.status.value.phase)
            assertEquals(2, RelaySignaling.status.value.total)
            assertSame(keys, cache.get(null))
        } finally {
            settings.setTorSignaling(false)
            CarrierTransport.disconnect(context)
            CarrierTransport.createBackend = originalFactory
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("asynchronous transport transition timed out", predicate())
    }

    private class FakeBackend(val status: (String) -> Unit) : CarrierBackend {
        @Volatile var started = false
        @Volatile var closed = false
        override val turnUrl = "turn:127.0.0.1:1?transport=udp"
        override val username = "test"
        override val password = "test"
        override fun start(provider: String, room: String) { started = true }
        override fun send(data: ByteArray) = !closed
        override fun close() { closed = true }
    }
}
