package com.dpsmpx.dpcall.data

import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.domain.IdentityKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Содержимое резервной копии после расшифровки. */
class BackupContent(val keys: IdentityKeys, val contacts: List<Contact>)

/** Неверный пароль или повреждённый файл: по шифру их не различить. */
class BackupPasswordException : Exception("Wrong password or damaged backup")

/** Файл не является резервной копией DP Call или создан более новой версией. */
class BackupFormatException(message: String) : Exception(message)

/**
 * Резервная копия: секретный ключ (то есть код пользователя) и контакты в одном
 * файле, зашифрованном паролем.
 *
 * Ключ шифрования выводится из пароля PBKDF2-HMAC-SHA256 с большим числом
 * итераций — это замедляет перебор пароля, если файл попадёт в чужие руки.
 * Данные шифруются AES-256-GCM. Не зависит от Android.
 */
object BackupCodec {
    const val MIN_PASSWORD_LENGTH = 8
    /** Файл копии мал; всё, что больше, — заведомо не наш файл. */
    const val MAX_FILE_BYTES = 1024 * 1024
    const val FILE_NAME = "dpcall-backup.json"

    private const val FORMAT = "dpcall-backup"
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val MAX_CONTACTS = 5_000
    private val AAD = "dpcall-backup-v1".toByteArray()

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    @Serializable
    private data class Container(
        val format: String,
        val v: Int,
        val kdf: String = KDF,
        val iterations: Int,
        val salt: String,
        val data: String
    )

    @Serializable
    private data class Plain(val secret: String, val contacts: List<StoredContact> = emptyList())

    /** Отдельная модель: у [Contact] есть проверки в init, а чужой файл может их нарушать. */
    @Serializable
    private data class StoredContact(val code: String = "", val name: String = "", val createdAt: Long = 0)

    private const val KDF = "PBKDF2-HMAC-SHA256"

    fun encrypt(secret: ByteArray, contacts: List<Contact>, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Password is too short" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val plain = json.encodeToString(
            Plain(
                secret = Base64.getEncoder().encodeToString(secret),
                contacts = contacts.map { StoredContact(it.code, it.name, it.createdAt) }
            )
        ).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        val container = Container(
            format = FORMAT,
            v = VERSION,
            iterations = iterations,
            salt = Base64.getEncoder().encodeToString(salt),
            data = Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain))
        )
        return json.encodeToString(container).toByteArray(Charsets.UTF_8)
    }

    /**
     * @throws BackupFormatException файл не наш
     * @throws BackupPasswordException пароль не подошёл или файл повреждён
     */
    fun decrypt(file: ByteArray, password: CharArray): BackupContent {
        if (file.size > MAX_FILE_BYTES) throw BackupFormatException("File is too large")
        val container = try {
            json.decodeFromString<Container>(String(file, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupFormatException("Not a backup file")
        }
        if (container.format != FORMAT) throw BackupFormatException("Not a backup file")
        if (container.v != VERSION || container.kdf != KDF) throw BackupFormatException("Unsupported backup version")
        if (container.iterations !in 1..MAX_ITERATIONS) throw BackupFormatException("Bad parameters")
        val salt: ByteArray
        val data: ByteArray
        try {
            salt = Base64.getDecoder().decode(container.salt)
            data = Base64.getDecoder().decode(container.data)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Not a backup file")
        }
        if (salt.size < 8 || data.size <= 12 + 16) throw BackupFormatException("Not a backup file")

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, deriveKey(password, salt, container.iterations), GCMParameterSpec(128, data, 0, 12)
            )
            cipher.updateAAD(AAD)
            json.decodeFromString<Plain>(String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupPasswordException()
        }
        val keys = try {
            IdentityGenerator.fromSecret(Base64.getDecoder().decode(plain.secret))
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw BackupFormatException("Backup holds no valid key")
        // Чужой файл не должен ни уронить приложение, ни завести мусорные контакты.
        val contacts = plain.contacts.asSequence()
            .filter { it.name.isNotBlank() && it.code.isNotBlank() && it.code.length <= 64 }
            .distinctBy { it.code.uppercase() }
            .take(MAX_CONTACTS)
            .map { Contact(code = it.code, name = it.name.take(100), createdAt = it.createdAt) }
            .toList()
        return BackupContent(keys, contacts)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
