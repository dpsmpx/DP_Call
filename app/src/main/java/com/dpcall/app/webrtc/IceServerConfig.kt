package com.dpcall.app.webrtc

import com.dpcall.app.data.TurnConfig
import org.webrtc.PeerConnection

object IceServerConfig {

    private val STUN_SERVERS = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302"
    )

    private val TURN_SERVERS = listOf(
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

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
