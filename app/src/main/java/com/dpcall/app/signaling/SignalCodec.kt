package com.dpcall.app.signaling

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    fun encode(type: SignalType, payload: SdpPayload): String {
        val jsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val compressed = gzip(jsonBytes)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$PREFIX.${type.code}.$b64"
    }

    fun decode(raw: String): Result<DecodedSignal> {
        return try {
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

            Result.success(DecodedSignal(type = type, payload = payload))
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

    private fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
}

data class DecodedSignal(
    val type: SignalType,
    val payload: SdpPayload
)

class SignalException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
