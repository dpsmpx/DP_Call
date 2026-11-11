package com.dpcall.app.signaling

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
        val b64 = Base64.encodeToString(
            compressed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$PREFIX.${type.code}.$b64"
    }

    fun decode(raw: String): Result<DecodedSignal> {
        return try {
            val cleaned = raw.trim().replace(Regex("\\s+"), "")
            val parts = cleaned.split('.', limit = 3)
            if (parts.size != 3) {
                return Result.failure(SignalException("Неверный формат строки"))
            }

            val (prefix, typeCode, payloadB64) = parts

            if (prefix != PREFIX) {
                if (prefix.startsWith("DPCALL") && prefix != PREFIX) {
                    return Result.failure(
                        SignalException("Неподдерживаемая версия формата: $prefix")
                    )
                }
                return Result.failure(SignalException("Неизвестный префикс"))
            }

            val type = SignalType.fromCode(typeCode.firstOrNull() ?: ' ')
                ?: return Result.failure(SignalException("Неизвестный тип сообщения: $typeCode"))

            if (payloadB64.isBlank()) {
                return Result.failure(SignalException("Пустой payload"))
            }

            val compressed = Base64.decode(
                payloadB64,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val jsonBytes = gunzip(compressed)
            val payload = json.decodeFromString<SdpPayload>(
                jsonBytes.toString(Charsets.UTF_8)
            )

            if (payload.sdp.isBlank()) {
                return Result.failure(SignalException("SDP отсутствует"))
            }

            Result.success(DecodedSignal(type = type, payload = payload))
        } catch (e: SignalException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(SignalException("Повреждённый или неверный код", e))
        }
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
