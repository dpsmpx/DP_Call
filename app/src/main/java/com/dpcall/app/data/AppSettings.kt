package com.dpcall.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Простые настройки приложения (SharedPreferences). */
class AppSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("call_settings", Context.MODE_PRIVATE)

    private val _backgroundListening = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND, true))
    /** Держать связь с релеями в фоне, чтобы звонки доходили при закрытом приложении. */
    val backgroundListening: StateFlow<Boolean> = _backgroundListening.asStateFlow()

    private val _contactsOnly = MutableStateFlow(prefs.getBoolean(KEY_CONTACTS_ONLY, true))
    /** Принимать звонки только от тех, кто есть в контактах. */
    val contactsOnly: StateFlow<Boolean> = _contactsOnly.asStateFlow()

    var noiseSuppression: Boolean
        get() = prefs.getBoolean(KEY_NOISE_SUPPRESSION, true)
        set(value) = prefs.edit().putBoolean(KEY_NOISE_SUPPRESSION, value).apply()

    fun setBackgroundListening(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND, enabled).apply()
        _backgroundListening.value = enabled
    }

    fun setContactsOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTACTS_ONLY, enabled).apply()
        _contactsOnly.value = enabled
    }

    companion object {
        private const val KEY_BACKGROUND = "background_listening"
        private const val KEY_CONTACTS_ONLY = "contacts_only"
        private const val KEY_NOISE_SUPPRESSION = "noise_suppression"

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
