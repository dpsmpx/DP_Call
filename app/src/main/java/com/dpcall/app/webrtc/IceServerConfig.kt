package com.dpcall.app.webrtc

import com.dpcall.app.data.TurnConfig
import org.webrtc.PeerConnection

object IceServerConfig {

    private val STUN_SERVERS = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    )

    // Open Relay Project (Metered), вариант со статическими учётными данными.
    // Прежний адрес openrelay.metered.ca отключён: из-за него сбор маршрутов
    // зависал, а соединение через NAT/VPN не устанавливалось.
    private const val TURN_HOST = "staticauth.openrelay.metered.ca"
    private const val TURN_USER = "openrelayproject"
    private const val TURN_PASSWORD = "openrelayprojectsecret"

    private val TURN_SERVERS = listOf(
        "turn:$TURN_HOST:80",
        "turn:$TURN_HOST:443",
        "turn:$TURN_HOST:80?transport=tcp",
        "turns:$TURN_HOST:443?transport=tcp"
    ).map { url ->
        PeerConnection.IceServer.builder(url)
            .setUsername(TURN_USER)
            .setPassword(TURN_PASSWORD)
            .createIceServer()
    }

    /**
     * @param custom свой TURN-сервер из настроек; добавляется к публичным.
     */
    fun createIceServers(useTurn: Boolean = true, custom: TurnConfig? = null): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        if (custom != null && custom.isValid) {
            try {
                servers += PeerConnection.IceServer.builder(custom.url)
                    .setUsername(custom.username)
                    .setPassword(custom.password)
                    .createIceServer()
            } catch (e: Exception) {
                // Некорректный адрес из настроек не должен ломать звонок.
            }
        }
        STUN_SERVERS.forEach { url ->
            servers += PeerConnection.IceServer.builder(url).createIceServer()
        }
        if (useTurn) {
            servers += TURN_SERVERS
        }
        return servers
    }
}
