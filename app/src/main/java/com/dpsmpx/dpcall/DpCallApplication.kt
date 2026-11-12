package com.dpsmpx.dpcall

import android.app.Application
import com.dpsmpx.dpcall.call.CallManager
import com.dpsmpx.dpcall.call.CallNotifications
import com.dpsmpx.dpcall.call.ListenService
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.util.Diagnostics

class DpCallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        CallNotifications.ensureChannels(this)
        CallManager.init(this)
        // Связь с релеями нужна всегда, пока жив процесс; сервис удерживает его в фоне.
        RelaySignaling.start(this)
        ListenService.sync(this)
    }
}
