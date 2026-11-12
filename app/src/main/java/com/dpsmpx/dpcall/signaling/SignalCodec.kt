package com.dpsmpx.dpcall.signaling

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.signaling.relay.RelayCrypto
import com.dpsmpx.dpcall.signaling.relay.RelayCrypto.hexToBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SignalCodec {

    private const val PREFIX = "DPCALL1"
    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Предел размера строки сигнала и распакованных данных (защита от «gzip-бомбы»). */
    const val MAX_SIGNAL_CHARS = 64 * 1024
    private const val MAX_JSON_BYTES = 256 * 1024
    const val MAX_CANDIDATES = 64

    /** Что именно подписывается: все поля, однозначно разделённые. */
    private fun signedDigest(type: SignalType, p: SdpPayload): ByteArray {
        val fields = listOf(type.code.toString(), p.sdp, p.candidates.size.toString()) + p.candidates +
            listOf(p.identity.orEmpty(), p.ts.toString(), p.callId.orEmpty())
        return RelayCrypto.sha256(fields.joinToString("\u0000") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    }

    /**
     * @param secret секретный ключ отправителя: если задан, сигнал подписывается.
     *   payload.identity должен быть кодом, соответствующим этому ключу.
     */
    fun encode(type: SignalType, payload: SdpPayload, secret: ByteArray? = null): String {
        val signed = if (secret != null) {
            payload.copy(sig = with(RelayCrypto) { signSchnorr(signedDigest(type, payload), secret).toHex() })
        } else {
            payload
        }
        val jsonBytes = json.encodeToString(signed).toByteArray(Charsets.UTF_8)
        val compressed = gzip(jsonBytes)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$PREFIX.${type.code}.$b64"
    }

    fun decode(raw: String): Result<DecodedSignal> {
        return try {
            if (raw.length > MAX_SIGNAL_CHARS) {
                return Result.failure(SignalException("Signal is too long"))
            }
            val cleaned = raw.trim().replace(Regex("\\s+"), "")
            val parts = cleaned.split('.', limit = 3)
            if (parts.size != 3) {
                return Result.failure(SignalException("Malformed signal string"))
            }

            val (prefix, typeCode, payloadB64) = parts

            if (prefix != PREFIX) {
                if (prefix.startsWith("DPCALL") && prefix != PREFIX) {
                    return Result.failure(
                        SignalException("Unsupported format version: $prefix")
                    )
                }
                return Result.failure(SignalException("Unknown prefix"))
            }

            val type = SignalType.fromCode(typeCode.firstOrNull() ?: ' ')
                ?: return Result.failure(SignalException("Unknown message type: $typeCode"))

            if (payloadB64.isBlank()) {
                return Result.failure(SignalException("Empty payload"))
            }

            val compressed = Base64.getUrlDecoder().decode(payloadB64.trimEnd('='))
            val jsonBytes = gunzip(compressed)
            val payload = json.decodeFromString<SdpPayload>(
                jsonBytes.toString(Charsets.UTF_8)
            )

            if (payload.sdp.isBlank()) {
                return Result.failure(SignalException("SDP is missing"))
            }
            if (payload.candidates.size > MAX_CANDIDATES) {
                return Result.failure(SignalException("Too many candidates"))
            }

            Result.success(DecodedSignal(type = type, payload = payload, verified = isSignatureValid(type, payload)))
        } catch (e: SignalException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(SignalException("Damaged or invalid code", e))
        }
    }

    private val SIGNAL_REGEX = Regex("DPCALL\\d+\\.[A-Za-z]\\.[A-Za-z0-9_\\-]+")

    /**
     * Достаёт DPCALL-строку из произвольного текста (например, из сообщения
     * мессенджера с подписью). Возвращает null, если её там нет.
     */
    fun extract(text: String): String? {
        if (text.length > MAX_SIGNAL_CHARS * 2) return null
        val direct = SIGNAL_REGEX.find(text)?.value
        if (direct != null && decode(direct).isSuccess) return direct
        // Мессенджер мог разбить длинную строку переносами.
        val squashed = SIGNAL_REGEX.find(text.replace(Regex("\\s+"), ""))?.value
        return squashed ?: direct
    }

    fun looksLikeSignal(raw: String): Boolean {
        val cleaned = raw.trim()
        return cleaned.startsWith("DPCALL") && cleaned.count { it == '.' } >= 2
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun isSignatureValid(type: SignalType, payload: SdpPayload): Boolean {
        val publicKey = payload.identity?.let { IdentityGenerator.publicKeyFromCode(it) } ?: return false
        val signature = with(RelayCrypto) { payload.sig?.hexToBytes() } ?: return false
        if (signature.size != 64) return false
        return RelayCrypto.verifySchnorr(signature, signedDigest(type, payload.copy(sig = null)), publicKey)
    }

    /** Распаковка с жёстким пределом: маленькая строка не должна разворачиваться в гигабайты. */
    private fun gunzip(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_JSON_BYTES) throw SignalException("Decompressed signal is too large")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

data class DecodedSignal(
    val type: SignalType,
    val payload: SdpPayload,
    /** Подпись проверена: сигнал действительно создан владельцем кода payload.identity. */
    val verified: Boolean = false
)

class SignalException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
