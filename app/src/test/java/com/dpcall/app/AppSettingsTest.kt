package com.dpcall.app

import com.dpcall.app.data.AppSettings
import com.dpcall.app.data.TurnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun parsesRelayList() {
        val parsed = AppSettings.parseRelays(
            "wss://a.example/\n  wss://b.example  \nhttps://not-a-relay\nws://insecure\n\nwss://a.example, wss://c.example"
        )
        assertEquals(listOf("wss://a.example", "wss://b.example", "wss://c.example"), parsed)
    }

    @Test
    fun rejectsListWithoutValidRelays() {
        assertTrue(AppSettings.parseRelays("example.org\nhttp://x").isEmpty())
        assertTrue(AppSettings.parseRelays("").isEmpty())
    }

    @Test
    fun limitsRelayCount() {
        val many = (1..40).joinToString("\n") { "wss://relay$it.example" }
        assertEquals(12, AppSettings.parseRelays(many).size)
    }

    @Test
    fun validatesTurnAddress() {
        assertTrue(TurnConfig("turn:example.org:3478").isValid)
        assertTrue(TurnConfig("turns:example.org:5349?transport=tcp").isValid)
        assertFalse(TurnConfig("example.org:3478").isValid)
        assertFalse(TurnConfig("").isValid)
    }
}
