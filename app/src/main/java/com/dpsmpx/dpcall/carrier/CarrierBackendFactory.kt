package com.dpsmpx.dpcall.carrier

import org.dpcall.mobile.Callbacks
import org.dpcall.mobile.Mobile

internal object CarrierBackendFactory {
    fun create(key: ByteArray, first: Boolean, onStatus: (String) -> Unit, onSignal: (ByteArray) -> Unit): CarrierBackend {
        val session = Mobile.newSession(key, first, object : Callbacks {
            override fun status(value: String) = onStatus(value)
            override fun signal(data: ByteArray) = onSignal(data)
        })
        return object : CarrierBackend {
            override val turnUrl get() = session.turnURL()
            override val username get() = session.username()
            override val password get() = session.password()
            override fun start(provider: String, room: String) = session.start(provider, room)
            override fun send(data: ByteArray) = session.sendSignal(data)
            override fun close() = session.close()
        }
    }
}
