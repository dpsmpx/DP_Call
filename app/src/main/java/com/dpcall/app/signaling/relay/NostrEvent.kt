package com.dpcall.app.signaling.relay

import com.dpcall.app.signaling.relay.RelayCrypto.hexToBytes
import com.dpcall.app.signaling.relay.RelayCrypto.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Событие Nostr (NIP-01). */
class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        putJsonArray("tags") { tags.forEach { tag -> add(JsonArray(tag.map { JsonPrimitive(it) })) } }
        put("content", content)
        put("sig", sig)
    }

    fun firstTag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** Проверяет, что id соответствует содержимому, а подпись — ключу автора. */
    fun isValid(): Boolean {
        val hash = computeId(pubkey, createdAt, kind, tags, content)
        if (hash.toHex() != id) return false
        val sigBytes = sig.hexToBytes() ?: return false
        val key = pubkey.hexToBytes() ?: return false
        return sigBytes.size == 64 && key.size == 32 && RelayCrypto.verifySchnorr(sigBytes, hash, key)
    }

    companion object {
        /**
         * Эфемерные события (20000–29999) релеи только пересылают подписчикам
         * и не хранят.
         */
        const val KIND_CALL_SIGNAL = 24242

        private fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): ByteArray {
            val canonical = buildJsonArray {
                add(JsonPrimitive(0))
                add(JsonPrimitive(pubkey))
                add(JsonPrimitive(createdAt))
                add(JsonPrimitive(kind))
                add(JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }))
                add(JsonPrimitive(content))
            }
            return RelayCrypto.sha256(canonical.toString().toByteArray(Charsets.UTF_8))
        }

        fun create(secret: ByteArray, public: ByteArray, kind: Int, tags: List<List<String>>, content: String): NostrEvent {
            val pubkey = public.toHex()
            val createdAt = System.currentTimeMillis() / 1000
            val hash = computeId(pubkey, createdAt, kind, tags, content)
            return NostrEvent(
                id = hash.toHex(),
                pubkey = pubkey,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = RelayCrypto.signSchnorr(hash, secret).toHex()
            )
        }

        fun fromJson(element: JsonElement): NostrEvent? = try {
            val obj = element as JsonObject
            NostrEvent(
                id = obj.getValue("id").jsonPrimitive.content,
                pubkey = obj.getValue("pubkey").jsonPrimitive.content,
                createdAt = obj.getValue("created_at").jsonPrimitive.long,
                kind = obj.getValue("kind").jsonPrimitive.int,
                tags = obj.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
                content = obj.getValue("content").jsonPrimitive.content,
                sig = obj.getValue("sig").jsonPrimitive.content
            )
        } catch (e: Exception) {
            null
        }
    }
}
