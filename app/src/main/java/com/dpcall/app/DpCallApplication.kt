package com.dpcall.app

import android.app.Application
import com.dpcall.app.call.CallManager
import com.dpcall.app.call.CallNotifications

class DpCallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallNotifications.ensureChannels(this)
        CallManager.init(this)
    }
}
