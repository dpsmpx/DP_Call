package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.carrier.CarrierRoom
import org.junit.Assert.*
import org.junit.Test

class CarrierRoomTest {
    @Test fun acceptsOnlyGuestLinksAndNormalizesVk() {
        assertEquals("https://vk.ru/call/join/abc_123", CarrierRoom.parse(" https://vk.com/call/join/abc_123 ")?.url)
        assertEquals("telemost", CarrierRoom.parse("https://telemost.yandex.ru/j/123456")?.provider)
        listOf("http://telemost.yandex.ru/j/123", "https://telemost.yandex.ru.evil.test/j/123", "https://vk.ru@evil.test/call/join/abc", "https://evil@vk.ru/call/join/abc", "https://vk.ru:444/call/join/abc", "https://vk.ru/call/join/abc#secret", "https://vk.ru/call/join/abc?redirect=evil", "https://vk.ru/call/join/../abc").forEach { assertNull(it, CarrierRoom.parse(it)) }
    }
}
