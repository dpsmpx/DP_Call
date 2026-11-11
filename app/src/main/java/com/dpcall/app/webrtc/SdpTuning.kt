package com.dpcall.app.webrtc

/**
 * Настройка Opus через SDP.
 *
 * Параметры fmtp в удалённом SDP говорят нашему кодеру, что «хочет получать» собеседник,
 * поэтому мы правим remote description перед применением: это не требует согласования
 * и безопасно — описание локальной сессии не меняется.
 */
object SdpTuning {

    private val OPUS_PARAMS = linkedMapOf(
        // Голос в полной полосе 48 кГц с запасом по битрейту (по умолчанию ~32 кбит/с).
        "maxaveragebitrate" to "48000",
        "maxplaybackrate" to "48000",
        // Встроенная коррекция потерь пакетов.
        "useinbandfec" to "1",
        // Без DTX: нет «провалов» и обрезанных начал фраз.
        "usedtx" to "0",
        "stereo" to "0"
    )

    private val RTPMAP_OPUS = Regex("^a=rtpmap:(\\d+) opus/48000(/2)?\\s*$", RegexOption.IGNORE_CASE)

    fun tuneOpus(sdp: String): String {
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol).toMutableList()
        val rtpmapIndex = lines.indexOfFirst { RTPMAP_OPUS.matches(it) }
        if (rtpmapIndex < 0) return sdp
        val pt = RTPMAP_OPUS.find(lines[rtpmapIndex])!!.groupValues[1]

        val fmtpPrefix = "a=fmtp:$pt "
        val fmtpIndex = lines.indexOfFirst { it.startsWith(fmtpPrefix) }
        val params = linkedMapOf<String, String>()
        if (fmtpIndex >= 0) {
            lines[fmtpIndex].removePrefix(fmtpPrefix).split(';').forEach { raw ->
                val kv = raw.trim()
                if (kv.isEmpty()) return@forEach
                val eq = kv.indexOf('=')
                if (eq > 0) params[kv.substring(0, eq)] = kv.substring(eq + 1) else params[kv] = ""
            }
        }
        params.putAll(OPUS_PARAMS)
        val fmtpLine = fmtpPrefix + params.entries.joinToString(";") { (k, v) ->
            if (v.isEmpty()) k else "$k=$v"
        }
        if (fmtpIndex >= 0) lines[fmtpIndex] = fmtpLine else lines.add(rtpmapIndex + 1, fmtpLine)
        return lines.joinToString(eol)
    }
}
