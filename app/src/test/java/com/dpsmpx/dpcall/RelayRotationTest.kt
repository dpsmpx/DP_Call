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
        val nextEvent = NostrEvent.create(keys.secret, keys.public, NostrEvent.KIND_CALL_SIGNAL,
            listOf(listOf("p", "inbox")), "next-body")
        var old: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onOpen(webSocket: WebSocket, response: Response) { old = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Handshake + this response crosses the rotation threshold.
                webSocket.send("[\"NOTICE\",\"" + "x".repeat(5000) + "\"]")
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = "[\"EVENT\",\"dpcall\",${event.toJson()}]"
                old!!.send(message)
                val nextMessage = "[\"EVENT\",\"dpcall\",${nextEvent.toJson()}]"
                webSocket.send(nextMessage)
                webSocket.send(nextMessage)
            }
        }))
        server.start()
        val pool = RelayPool(urls = listOf(server.url("/").toString().replace("http://", "ws://")),
            inboxTag = "inbox", kind = NostrEvent.KIND_CALL_SIGNAL,
            onEvent = { delivered += it }, onStatus = { _, _ -> })
        try {
            pool.start()
            val ids = setOf(delivered.poll(10, TimeUnit.SECONDS)?.id, delivered.poll(10, TimeUnit.SECONDS)?.id)
            assertEquals(setOf(event.id, nextEvent.id), ids)
            assertNull(delivered.poll(200, TimeUnit.MILLISECONDS))
            assertEquals(2, server.requestCount)
        } finally { pool.stop(); old?.close(1000, null); server.shutdown() }
    }
}
