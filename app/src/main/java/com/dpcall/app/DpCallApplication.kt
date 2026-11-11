package com.dpcall.app

import android.app.Application

/**
 * Application entry point.
 * Keep minimal — no heavy initialization here.
 */
class DpCallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: initialize identity, preferences, etc.
    }
}
