package com.dpcall.app

import android.app.Application
import com.dpcall.app.call.CallManager
import com.dpcall.app.call.CallNotifications
import com.dpcall.app.call.ListenService
import com.dpcall.app.call.RelaySignaling
import com.dpcall.app.util.Diagnostics

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
