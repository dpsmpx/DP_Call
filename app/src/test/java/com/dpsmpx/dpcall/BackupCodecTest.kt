package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.data.BackupCodec
import com.dpsmpx.dpcall.data.BackupFormatException
import com.dpsmpx.dpcall.data.BackupPasswordException
import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.domain.IdentityGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {
    // Мало итераций — только чтобы тесты шли быстро; рабочее значение задано в BackupCodec.
    private val fast = 1_000
    private val password = "correct horse".toCharArray()
    private val keys = IdentityGenerator.generate()
    private val contacts = listOf(
        Contact(code = IdentityGenerator.generate().code, name = "Анна", createdAt = 1L),
        Contact(code = IdentityGenerator.generate().code, name = "Boris", createdAt = 2L)
    )

    @Test
    fun roundTripRestoresKeyAndContacts() {
        val file = BackupCodec.encrypt(keys.secret, contacts, password, fast)
        val restored = BackupCodec.decrypt(file, password)
        assertArrayEquals(keys.secret, restored.keys.secret)
        assertEquals(keys.code, restored.keys.code)
        assertEquals(contacts, restored.contacts)
    }

    @Test
    fun defaultParametersRoundTrip() {
        val restored = BackupCodec.decrypt(BackupCodec.encrypt(keys.secret, emptyList(), password), password)
        assertEquals(keys.code, restored.keys.code)
    }

    @Test
    fun wrongPasswordIsRejected() {
        val file = BackupCodec.encrypt(keys.secret, contacts, password, fast)
        assertThrows(BackupPasswordException::class.java) { BackupCodec.decrypt(file, "wrong password".toCharArray()) }
    }

    @Test
    fun tamperedFileIsRejected() {
        val text = String(BackupCodec.encrypt(keys.secret, contacts, password, fast))
        // Меняем один символ внутри зашифрованных данных.
        val at = text.indexOf("\"data\":\"") + 20
        val tampered = text.substring(0, at) + (if (text[at] == 'A') 'B' else 'A') + text.substring(at + 1)
        assertThrows(BackupPasswordException::class.java) { BackupCodec.decrypt(tampered.toByteArray(), password) }
    }

    @Test
    fun fileHoldsNoPlaintext() {
        val text = String(BackupCodec.encrypt(keys.secret, contacts, password, fast))
        assertFalse(text.contains(contacts[0].code))
        assertFalse(text.contains("Boris"))
    }

    @Test
    fun shortPasswordIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encrypt(keys.secret, contacts, "short".toCharArray(), fast)
        }
    }

    @Test
    fun foreignFilesAreRejected() {
        for (junk in listOf("", "hello", "{}", "{\"format\":\"other\",\"v\":1,\"iterations\":1,\"salt\":\"\",\"data\":\"\"}")) {
            assertThrows(BackupFormatException::class.java) { BackupCodec.decrypt(junk.toByteArray(), password) }
        }
        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decrypt(ByteArray(BackupCodec.MAX_FILE_BYTES + 1), password)
        }
    }

    @Test
    fun absurdIterationCountIsRejectedBeforeAnyWork() {
        val text = String(BackupCodec.encrypt(keys.secret, contacts, password, fast))
            .replace("\"iterations\":$fast", "\"iterations\":2000000000")
        assertThrows(BackupFormatException::class.java) { BackupCodec.decrypt(text.toByteArray(), password) }
    }
}
