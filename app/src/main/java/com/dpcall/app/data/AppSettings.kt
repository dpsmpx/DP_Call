package com.dpcall.app.data

import android.content.Context
import com.dpcall.app.signaling.relay.RelayPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Адрес вида `turn:host:3478` или `turns:host:5349?transport=tcp`. */
data class TurnConfig(val url: String = "", val username: String = "", val password: String = "") {
    val isValid: Boolean get() = url.startsWith("turn:") || url.startsWith("turns:")
}

/** Простые настройки приложения (SharedPreferences). */
class AppSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("call_settings", Context.MODE_PRIVATE)

    private val _backgroundListening = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND, true))
    /** Держать связь с релеями в фоне, чтобы звонки доходили при закрытом приложении. */
    val backgroundListening: StateFlow<Boolean> = _backgroundListening.asStateFlow()

    private val _contactsOnly = MutableStateFlow(prefs.getBoolean(KEY_CONTACTS_ONLY, false))
    /** Принимать звонки только от тех, кто есть в контактах. */
    val contactsOnly: StateFlow<Boolean> = _contactsOnly.asStateFlow()

    private val _onboardingShown = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING, false))
    /** Вводная инструкция уже показана при первом запуске. */
    val onboardingShown: StateFlow<Boolean> = _onboardingShown.asStateFlow()

    fun setOnboardingShown() {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).apply()
        _onboardingShown.value = true
    }

    private val _relays = MutableStateFlow(loadRelays())
    /** Релеи сигнального канала (wss://…). */
    val relays: StateFlow<List<String>> = _relays.asStateFlow()

    private fun loadRelays(): List<String> =
        prefs.getString(KEY_RELAYS, null)?.let(::parseRelays)?.takeIf { it.isNotEmpty() }
            ?: RelayPool.DEFAULT_RELAYS

    /** @return false, если в списке нет ни одного корректного адреса (настройка не меняется). */
    fun setRelays(text: String): Boolean {
        val parsed = parseRelays(text)
        if (parsed.isEmpty()) return false
        prefs.edit().putString(KEY_RELAYS, parsed.joinToString("\n")).apply()
        _relays.value = parsed
        return true
    }

    fun resetRelays() {
        prefs.edit().remove(KEY_RELAYS).apply()
        _relays.value = RelayPool.DEFAULT_RELAYS
    }

    /** Свой TURN-сервер (необязательно). Пустой адрес — используется только публичный. */
    var turn: TurnConfig
        get() = TurnConfig(
            url = prefs.getString(KEY_TURN_URL, "").orEmpty(),
            username = prefs.getString(KEY_TURN_USER, "").orEmpty(),
            password = prefs.getString(KEY_TURN_PASSWORD, "").orEmpty()
        )
        set(value) = prefs.edit()
            .putString(KEY_TURN_URL, value.url.trim())
            .putString(KEY_TURN_USER, value.username.trim())
            .putString(KEY_TURN_PASSWORD, value.password)
            .apply()

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
        private const val KEY_ONBOARDING = "onboarding_shown"
        private const val KEY_RELAYS = "relays"
        private const val KEY_TURN_URL = "turn_url"
        private const val KEY_TURN_USER = "turn_user"
        private const val KEY_TURN_PASSWORD = "turn_password"
        private const val MAX_RELAYS = 12

        /** Разбирает список адресов: по одному в строке, только wss://. */
        fun parseRelays(text: String): List<String> =
            text.split('\n', ',', ' ')
                .map { it.trim().trimEnd('/') }
                .filter { it.startsWith("wss://") && it.length > 8 }
                .distinct()
                .take(MAX_RELAYS)

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
