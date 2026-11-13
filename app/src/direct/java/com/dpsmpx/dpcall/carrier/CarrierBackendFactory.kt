package com.dpsmpx.dpcall.carrier

internal object CarrierBackendFactory {
    fun create(key: ByteArray, first: Boolean, onStatus: (String) -> Unit, onSignal: (ByteArray) -> Unit): CarrierBackend =
        error("Carrier transport is available only in the experimental build")
}
