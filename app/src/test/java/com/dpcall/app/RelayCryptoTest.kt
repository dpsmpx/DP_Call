package com.dpcall.app

import com.dpcall.app.domain.Base32
import com.dpcall.app.domain.IdentityGenerator
import com.dpcall.app.signaling.relay.Envelope
import com.dpcall.app.signaling.relay.NostrEvent
import com.dpcall.app.signaling.relay.RelayCrypto
import com.dpcall.app.signaling.relay.RelayCrypto.hexToBytes
import com.dpcall.app.signaling.relay.RelayCrypto.toHex
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RelayCryptoTest {

    @Test
    fun base32RoundTrip() {
        val random = Random(1)
        repeat(200) {
            val bytes = random.nextBytes(32)
            val text = Base32.encode(bytes)
            assertEquals(52, text.length)
            assertArrayEquals(bytes, Base32.decode(text))
            assertArrayEquals(bytes, Base32.decode(text.lowercase()))
        }
        assertNull(Base32.decode("ILOU"))
    }

    @Test
    fun codeIsPublicKey() {
        val keys = IdentityGenerator.generate()
        assertEquals(IdentityGenerator.CODE_LENGTH, keys.code.length)
        assertArrayEquals(keys.public, IdentityGenerator.publicKeyFromCode(keys.code))
        assertArrayEquals(keys.public, IdentityGenerator.publicKeyFromCode("  ${keys.code.lowercase()} "))
        assertArrayEquals(keys.public, IdentityGenerator.fromSecret(keys.secret)!!.public)
        // Код старого формата (26 символов) и мусор не принимаются.
        assertNull(IdentityGenerator.publicKeyFromCode("0123456789ABCDEFGHJKMNPQRS"))
        assertNull(IdentityGenerator.publicKeyFromCode("Z".repeat(52)))
    }

    /** Официальный тестовый вектор BIP-340 №0: секрет 3, проверяем ключ и подпись. */
    @Test
    fun bip340Vector() {
        val secret = "0000000000000000000000000000000000000000000000000000000000000003".hexToBytes()!!
        val expectedPublic = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        assertEquals(expectedPublic, RelayCrypto.publicKeyOrNull(secret)!!.toHex())
        val message = ByteArray(32)
        val signature = ("e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215" +
            "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0").hexToBytes()!!
        assertTrue(RelayCrypto.verifySchnorr(signature, message, expectedPublic.hexToBytes()!!))
        signature[10] = (signature[10] + 1).toByte()
        assertFalse(RelayCrypto.verifySchnorr(signature, message, expectedPublic.hexToBytes()!!))
    }

    @Test
    fun sharedSecretIsSymmetric() {
        repeat(30) {
            val a = IdentityGenerator.generate()
            val b = IdentityGenerator.generate()
            assertArrayEquals(
                RelayCrypto.sharedSecret(a.secret, b.public),
                RelayCrypto.sharedSecret(b.secret, a.public)
            )
        }
    }

    @Test
    fun envelopeRoundTripAuthenticatesSender() {
        repeat(20) {
            val alice = IdentityGenerator.generate()
            val bob = IdentityGenerator.generate()
            val text = "привет, это звонок №$it".toByteArray()
            val sealed = Envelope.seal(alice.secret, alice.public, bob.public, text)
            val opened = Envelope.open(bob.secret, bob.public, sealed.ephemeralPublic, sealed.content)
            assertNotNull(opened)
            assertArrayEquals(text, opened!!.plaintext)
            assertArrayEquals(alice.public, opened.senderPublic)
        }
    }

    @Test
    fun envelopeRejectsWrongRecipientAndTampering() {
        val alice = IdentityGenerator.generate()
        val bob = IdentityGenerator.generate()
        val eve = IdentityGenerator.generate()
        val sealed = Envelope.seal(alice.secret, alice.public, bob.public, "secret".toByteArray())

        assertNull(Envelope.open(eve.secret, eve.public, sealed.ephemeralPublic, sealed.content))

        val tampered = sealed.content.clone().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(Envelope.open(bob.secret, bob.public, sealed.ephemeralPublic, tampered))

        // Содержимое не раскрывает ни отправителя, ни получателя.
        val hex = sealed.content.toHex()
        assertFalse(hex.contains(alice.public.toHex()))
        assertFalse(hex.contains(bob.public.toHex()))
        assertFalse(Envelope.inboxTag(bob.public).contains(bob.public.toHex()))
    }

    /** Нельзя выдать себя за другого: внутренний слой создаётся ключом отправителя. */
    @Test
    fun envelopeCannotBeForged() {
        val alice = IdentityGenerator.generate()
        val bob = IdentityGenerator.generate()
        val eve = IdentityGenerator.generate()
        // Ева шифрует своим секретом, но подставляет код Алисы.
        val forged = Envelope.seal(eve.secret, alice.public, bob.public, "call".toByteArray())
        assertNull(Envelope.open(bob.secret, bob.public, forged.ephemeralPublic, forged.content))
    }

    @Test
    fun nostrEventSignsAndSurvivesJson() {
        val keys = IdentityGenerator.generate()
        val event = NostrEvent.create(
            keys.secret, keys.public, NostrEvent.KIND_CALL_SIGNAL,
            listOf(listOf("p", "ab".repeat(32))), "AAAA+/=="
        )
        assertTrue(event.isValid())
        val parsed = NostrEvent.fromJson(Json.parseToJsonElement(event.toJson().toString()))!!
        assertTrue(parsed.isValid())
        assertEquals(event.id, parsed.id)
        assertEquals("ab".repeat(32), parsed.firstTag("p"))

        val altered = NostrEvent(event.id, event.pubkey, event.createdAt, event.kind, event.tags, "BBBB", event.sig)
        assertFalse(altered.isValid())
    }
}
