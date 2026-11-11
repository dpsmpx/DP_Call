package com.dpcall.app.call

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dpcall.app.data.AppSettings
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
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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

        /** Запускает или останавливает сервис в соответствии с настройкой. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ListenService::class.java)
            try {
                if (AppSettings.get(app).backgroundListening.value) {
                    ContextCompat.startForegroundService(app, intent)
                } else {
                    app.stopService(intent)
                }
            } catch (e: Exception) {
                // Из фона система может не разрешить запуск — повторим, когда приложение откроют.
                Log.w(TAG, "Cannot sync listen service", e)
            }
        }
    }
}
