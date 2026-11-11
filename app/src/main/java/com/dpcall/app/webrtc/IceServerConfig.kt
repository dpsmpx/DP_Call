package com.dpcall.app.webrtc

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

    fun createIceServers(useTurn: Boolean = true): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        STUN_SERVERS.forEach { url ->
            servers += PeerConnection.IceServer.builder(url).createIceServer()
        }
        if (useTurn) {
            servers += TURN_SERVERS
        }
        return servers
    }
}
