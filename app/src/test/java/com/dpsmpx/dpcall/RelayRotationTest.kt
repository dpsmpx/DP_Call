package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.signaling.relay.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayRotationTest {
    @Test fun inFlightEventOnRetiringSocketIsDeliveredOnce() {
        val server = MockWebServer()
        val delivered = LinkedBlockingQueue<NostrEvent>()
        val keys = IdentityGenerator.generate()
        val event = NostrEvent.create(keys.secret, keys.public, NostrEvent.KIND_CALL_SIGNAL,
            listOf(listOf("p", "inbox")), "encrypted-body")
        var old: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { old = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Handshake + this response crosses the rotation threshold.
                webSocket.send("[\"NOTICE\",\"" + "x".repeat(5000) + "\"]")
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = "[\"EVENT\",\"dpcall\",${event.toJson()}]"
                old!!.send(message)
                webSocket.send(message)
            }
        }))
        server.start()
        val pool = RelayPool(urls = listOf(server.url("/").toString().replace("http://", "ws://")),
            inboxTag = "inbox", kind = NostrEvent.KIND_CALL_SIGNAL,
            onEvent = { delivered += it }, onStatus = { _, _ -> })
        try {
            pool.start()
            assertEquals(event.id, delivered.poll(10, TimeUnit.SECONDS)?.id)
            assertNull(delivered.poll(200, TimeUnit.MILLISECONDS))
            assertEquals(2, server.requestCount)
        } finally { pool.stop(); old?.cancel(); server.shutdown() }
    }
}
