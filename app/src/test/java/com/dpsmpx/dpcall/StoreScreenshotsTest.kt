package com.dpsmpx.dpcall

import android.app.Application
import android.content.Context
import android.os.PowerManager
import org.robolectric.Shadows.shadowOf
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dpsmpx.dpcall.call.CallUiState
import com.dpsmpx.dpcall.call.LinkQuality
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.call.RelayStatus
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.data.CallLogEntry
import com.dpsmpx.dpcall.data.CallLogRepository
import com.dpsmpx.dpcall.data.CallOutcome
import com.dpsmpx.dpcall.data.ContactsRepository
import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.ui.call.CallScreen
import com.dpsmpx.dpcall.ui.home.HomeScreen
import com.dpsmpx.dpcall.ui.theme.DPCallTheme
import com.dpsmpx.dpcall.webrtc.CallState
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar

/**
 * Снимает скриншоты для страницы приложения (описание выпуска, README) — настоящие экраны
 * приложения с демонстрационными данными, на JVM, без эмулятора.
 *
 *     ./gradlew testDebugUnitTest -Pscreenshots
 *
 * Файлы попадают в fastlane/metadata/android/<язык>/images/phoneScreenshots/.
 * В обычный прогон тестов этот класс не входит.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Пустой Application: настоящий запускает WebRTC и соединения с релеями.
// SDK 30 — без динамических цветов Android 12+, то есть в фирменной палитре приложения.
@Config(application = Application::class, sdk = [30])
class StoreScreenshotsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var contacts: List<Contact>

    @Before
    fun seed() {
        val russian = context.resources.configuration.locales[0].language == "ru"
        val names = if (russian) {
            listOf("Анна Смирнова", "Борис", "Мама", "Олег Петров", "Соня")
        } else {
            listOf("Anna Smith", "Boris", "Mom", "Oliver Brown", "Sophie")
        }
        contacts = names.map { Contact(code = IdentityGenerator.generate().code, name = it) }
        val me = IdentityGenerator.generate().code

        fun at(daysAgo: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 12 - daysAgo, hour, minute, 0)
        }.timeInMillis

        val log = listOf(
            CallLogEntry(contacts[0].code, contacts[0].name, incoming = false, CallOutcome.ANSWERED, at(0, 18, 42), 754),
            CallLogEntry(contacts[2].code, contacts[2].name, incoming = true, CallOutcome.ANSWERED, at(0, 12, 5), 1327),
            CallLogEntry(contacts[3].code, contacts[3].name, incoming = true, CallOutcome.MISSED, at(1, 21, 17)),
            CallLogEntry(contacts[1].code, contacts[1].name, incoming = false, CallOutcome.NOT_CONNECTED, at(1, 9, 30)),
            CallLogEntry(contacts[4].code, contacts[4].name, incoming = true, CallOutcome.ANSWERED, at(2, 16, 48), 96),
            CallLogEntry(contacts[0].code, contacts[0].name, incoming = true, CallOutcome.DECLINED, at(3, 8, 12)),
        )

        // Свой код кладём готовым: Android Keystore на JVM недоступен.
        store("identity_prefs", "user_identity" to me, "identity_secret" to "demo")
        // Контакты и журнал — через хранилища приложения: они живут весь прогон,
        // поэтому данные предыдущего теста сначала убираем.
        runBlocking {
            val contactsRepo = ContactsRepository(context)
            contactsRepo.contactsFlow.first().forEach { contactsRepo.remove(it.code) }
            contacts.forEach { contactsRepo.add(it) }
            val logRepo = CallLogRepository(context)
            logRepo.clear()
            log.reversed().forEach { logRepo.add(it) }
        }
        AppSettings.get(context).setOnboardingShown()
        // Плашка готовности — зелёная: ограничение батареи снято, как у настроенного приложения.
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)

        // Индикатор связи: как при работающем приложении.
        val status = RelaySignaling::class.java.getDeclaredField("_status").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (status.get(RelaySignaling) as MutableStateFlow<RelayStatus>).value = RelayStatus(connected = 5, total = 5)
    }

    private fun store(name: String, vararg values: Pair<String, String>) = runBlocking {
        val job = Job()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            context.preferencesDataStoreFile(name)
        }
        dataStore.edit { prefs -> values.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value } }
        job.cancelAndJoin() // освобождаем файл для хранилища самого приложения
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val locale = if (context.resources.configuration.locales[0].language == "ru") "ru-RU" else "en-US"
        val dir = File(System.getProperty("user.dir"), "../fastlane/metadata/android/$locale/images/phoneScreenshots")
        captureScreenRoboImage(File(dir, "$name.png").canonicalPath)
    }

    private fun home() = compose.setContent {
        DPCallTheme(dynamicColor = false) {
            HomeScreen(onOnboardingDone = {}, onCall = {}, onAnswerOffer = { _, _ -> })
        }
    }

    private fun call(state: CallUiState) = compose.setContent {
        DPCallTheme(dynamicColor = false) { CallScreen(state = state, onAccept = {}, onMinimize = {}) }
    }

    private fun contactsScreen() {
        home()
        compose.waitUntil(60_000) {
            compose.onNodeWithText(contacts[0].name).runCatching { assertExists() }.isSuccess
        }
        shot("1")
    }

    private fun incomingCall() {
        call(
            CallUiState(
                state = CallState.RINGING, isIncoming = true, viaRelay = true,
                contactName = contacts[0].name, contactCode = contacts[0].code
            )
        )
        shot("2")
    }

    private fun activeCall() {
        call(
            CallUiState(
                state = CallState.CONNECTED, viaRelay = true, quality = LinkQuality.GOOD,
                contactName = contacts[0].name, contactCode = contacts[0].code,
                connectedAt = SystemClock.elapsedRealtime() - 83_000
            )
        )
        shot("3")
    }

    private fun history() {
        home()
        compose.onNodeWithText(context.getString(R.string.call_log)).performClick()
        shot("4")
    }

    @Test @Config(qualifiers = RU) fun ru1Contacts() = contactsScreen()
    @Test @Config(qualifiers = RU) fun ru2Incoming() = incomingCall()
    @Test @Config(qualifiers = RU) fun ru3Active() = activeCall()
    @Test @Config(qualifiers = RU) fun ru4History() = history()

    @Test @Config(qualifiers = EN) fun en1Contacts() = contactsScreen()
    @Test @Config(qualifiers = EN) fun en2Incoming() = incomingCall()
    @Test @Config(qualifiers = EN) fun en3Active() = activeCall()
    @Test @Config(qualifiers = EN) fun en4History() = history()

    private companion object {
        // Экран 1080×2400, как у типичного телефона.
        const val RU = "ru-rRU-w411dp-h914dp-port-420dpi"
        const val EN = "en-rUS-w411dp-h914dp-port-420dpi"
    }
}
