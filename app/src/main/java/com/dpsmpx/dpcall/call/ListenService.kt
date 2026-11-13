package com.dpsmpx.dpcall.call

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dpsmpx.dpcall.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Фоновое ожидание звонков.
 *
 * Без собственных серверов и push-уведомлений Google входящий вызов может
 * прийти только по открытому соединению с релеями. Этот сервис не даёт системе
 * выгрузить процесс, пока приложение свёрнуто. Отключается в настройках.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CallNotifications.ensureChannels(this)
        CallManager.init(this)
        RelaySignaling.start(this)
        scope.launch {
            RelaySignaling.status.drop(1).collect { status ->
                try {
                    NotificationManagerCompat.from(this@ListenService).notify(
                        CallNotifications.LISTEN_NOTIFICATION_ID,
                        CallNotifications.listening(this@ListenService, status)
                    )
                } catch (e: SecurityException) {
                    // Уведомления запрещены — сервис продолжает работать без обновления текста.
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this, CallNotifications.LISTEN_NOTIFICATION_ID,
                CallNotifications.listening(this, RelaySignaling.status.value), type
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AppSettings.get(this).backgroundListening.value) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        applyHardMode()
        return START_STICKY
    }

    /**
     * Постоянная связь: держим процессор, чтобы проверки соединения с релеями шли по
     * расписанию и в Doze, и заводим сторожа, который поднимает ожидание звонков,
     * если оболочка его всё-таки закрыла.
     */
    private fun applyHardMode() {
        if (AppSettings.get(this).hardBackground.value) {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dpcall:listen").also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            // С запасом до следующего срабатывания сторожа, который продлит удержание.
            lock.acquire(WATCHDOG_MS + 5 * 60_000L)
            scheduleWatchdog(this)
        } else {
            releaseWakeLock()
            cancelWatchdog(this)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release", e)
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Сторож постоянной связи: перезапускает сервис и проверяет соединения с релеями. */
    class Watchdog : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!AppSettings.get(context).hardBackground.value || !AppSettings.get(context).backgroundListening.value) return
            sync(context)
            RelaySignaling.start(context)
            RelaySignaling.reconnectNow()
        }
    }

    /** Запуск после перезагрузки телефона и обновления приложения. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> sync(context)
            }
        }
    }

    companion object {
        private const val TAG = "ListenService"
        private const val WATCHDOG_MS = 15 * 60_000L

        private fun watchdogIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, Watchdog::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Неточный будильник: срабатывает и в Doze, но не требует разрешения на точные. */
        private fun scheduleWatchdog(context: Context) {
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WATCHDOG_MS,
                    watchdogIntent(context)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cannot schedule watchdog", e)
            }
        }

        private fun cancelWatchdog(context: Context) {
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(watchdogIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot cancel watchdog", e)
            }
        }

        /** Запускает или останавливает сервис в соответствии с настройкой. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ListenService::class.java)
            try {
                if (AppSettings.get(app).backgroundListening.value) {
                    ContextCompat.startForegroundService(app, intent)
                } else {
                    cancelWatchdog(app)
                    app.stopService(intent)
                }
            } catch (e: Exception) {
                // Из фона система может не разрешить запуск — повторим, когда приложение откроют.
                Log.w(TAG, "Cannot sync listen service", e)
            }
        }
    }
}
