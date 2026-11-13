package com.dpsmpx.dpcall.data

import android.content.Context
import com.dpsmpx.dpcall.signaling.relay.RelayPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Адрес вида `turn:host:3478` или `turns:host:5349?transport=tcp`. */
data class TurnConfig(val url: String = "", val username: String = "", val password: String = "") {
    val isValid: Boolean get() = url.startsWith("turn:") || url.startsWith("turns:")
}

/** Новая версия приложения: номер и адрес, откуда её скачать. */
data class UpdateInfo(val version: String, val url: String)

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

    // ------------------------------------------------------ пропущенные звонки

    private val _missedNotices = MutableStateFlow(prefs.getBoolean(KEY_MISSED_NOTICES, true))
    /**
     * Оставлять на релеях отметку о звонке, если собеседник был не в сети, и получать
     * такие отметки самому. Отметка зашифрована и хранится ограниченное время.
     */
    val missedNotices: StateFlow<Boolean> = _missedNotices.asStateFlow()

    fun setMissedNotices(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MISSED_NOTICES, enabled).apply()
        _missedNotices.value = enabled
    }

    private val _unseenMissed = MutableStateFlow(prefs.getInt(KEY_UNSEEN_MISSED, 0))
    /** Пропущенные звонки, которых пользователь ещё не видел в журнале. */
    val unseenMissed: StateFlow<Int> = _unseenMissed.asStateFlow()

    fun addUnseenMissed() = setUnseenMissed(_unseenMissed.value + 1)

    fun clearUnseenMissed() {
        if (_unseenMissed.value != 0) setUnseenMissed(0)
    }

    private fun setUnseenMissed(count: Int) {
        prefs.edit().putInt(KEY_UNSEEN_MISSED, count).apply()
        _unseenMissed.value = count
    }

    private val seenCallIds = LinkedHashSet(
        prefs.getString(KEY_SEEN_CALLS, "").orEmpty().split('\n').filter { it.isNotBlank() }
    )

    /**
     * Запоминает идентификатор звонка, о котором пользователь уже узнал (телефон звонил
     * или пришла отметка о пропущенном). Переживает перезапуск: отметка хранится на
     * релеях и приходит заново при каждом подключении.
     *
     * @return true, если такого звонка ещё не было.
     */
    @Synchronized
    fun markCallSeen(callId: String): Boolean {
        if (callId.isBlank() || callId.length > MAX_CALL_ID_LENGTH || '\n' in callId) return false
        if (!seenCallIds.add(callId)) return false
        while (seenCallIds.size > MAX_SEEN_CALLS) seenCallIds.iterator().let { it.next(); it.remove() }
        prefs.edit().putString(KEY_SEEN_CALLS, seenCallIds.joinToString("\n")).apply()
        return true
    }

    // --------------------------------------------------------------- обновления

    private val _updateCheck = MutableStateFlow(prefs.getBoolean(KEY_UPDATE_CHECK, true))
    /** Раз в сутки спрашивать у GitHub, не вышла ли новая версия. */
    val updateCheck: StateFlow<Boolean> = _updateCheck.asStateFlow()

    fun setUpdateCheck(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_CHECK, enabled).apply()
        _updateCheck.value = enabled
    }

    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECKED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECKED_AT, value).apply()

    private val _availableUpdate = MutableStateFlow(loadUpdate())
    /** Найденная новая версия; null — обновлений нет или пользователь скрыл сообщение. */
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate.asStateFlow()

    private fun loadUpdate(): UpdateInfo? {
        val version = prefs.getString(KEY_UPDATE_VERSION, null) ?: return null
        val url = prefs.getString(KEY_UPDATE_URL, null) ?: return null
        return UpdateInfo(version, url).takeIf { version != prefs.getString(KEY_UPDATE_DISMISSED, null) }
    }

    /** @param showAgain пользователь сам запросил проверку — показываем и скрытую ранее версию. */
    fun setAvailableUpdate(update: UpdateInfo?, showAgain: Boolean = false) {
        val editor = prefs.edit().putString(KEY_UPDATE_VERSION, update?.version).putString(KEY_UPDATE_URL, update?.url)
        if (showAgain) editor.remove(KEY_UPDATE_DISMISSED)
        editor.apply()
        _availableUpdate.value = loadUpdate()
    }

    /** «Не сейчас»: об этой версии больше не напоминаем, о следующей — напомним. */
    fun dismissUpdate() {
        val version = _availableUpdate.value?.version ?: return
        prefs.edit().putString(KEY_UPDATE_DISMISSED, version).apply()
        _availableUpdate.value = null
    }

    /**
     * Секрет для действий из собственных уведомлений («Перезвонить»): главный экран
     * экспортирован, и без него любое приложение могло бы заставить нас позвонить.
     */
    val intentToken: String by lazy {
        prefs.getString(KEY_INTENT_TOKEN, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INTENT_TOKEN, it).apply()
        }
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
        private const val KEY_MISSED_NOTICES = "missed_notices"
        private const val KEY_UNSEEN_MISSED = "unseen_missed"
        private const val KEY_SEEN_CALLS = "seen_call_ids"
        private const val KEY_UPDATE_CHECK = "update_check"
        private const val KEY_UPDATE_CHECKED_AT = "update_checked_at"
        private const val KEY_UPDATE_VERSION = "update_version"
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_UPDATE_DISMISSED = "update_dismissed"
        private const val KEY_INTENT_TOKEN = "intent_token"
        private const val MAX_RELAYS = 12
        private const val MAX_SEEN_CALLS = 300
        private const val MAX_CALL_ID_LENGTH = 64

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
