package com.dpcall.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.dpcall.app.R
import com.dpcall.app.ui.MainActivity
import com.dpcall.app.webrtc.CallState

/** Каналы и уведомления звонка. */
object CallNotifications {
    private const val TAG = "CallNotifications"

    const val CALL_NOTIFICATION_ID = 1001
    private const val MISSED_NOTIFICATION_ID = 1002

    private const val CHANNEL_INCOMING = "incoming_calls"
    private const val CHANNEL_ONGOING = "ongoing_calls"
    private const val CHANNEL_MISSED = "missed_calls"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val incoming = NotificationChannel(
            CHANNEL_INCOMING, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Всплывающее уведомление с кнопками «Принять» и «Отклонить»"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 900, 700, 900)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING, "Текущий звонок", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывается, пока идёт звонок; позволяет завершить его"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(
            CHANNEL_MISSED, "Пропущенные звонки", NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannels(listOf(incoming, ongoing, missed))
    }

    fun canPostNotifications(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Уведомление, соответствующее текущему состоянию звонка. */
    fun build(context: Context, state: CallUiState): Notification =
        if (state.state == CallState.RINGING) incoming(context, state) else ongoing(context, state)

    private fun person(state: CallUiState): Person =
        Person.Builder().setName(state.contactName.ifBlank { "Собеседник" }).setImportant(true).build()

    private fun incoming(context: Context, state: CallUiState): Notification {
        val content = activityIntent(context, 1, answer = false)
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(state.contactName.ifBlank { "Собеседник" })
            .setContentText("Входящий звонок DP Call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(content)
            .setFullScreenIntent(content, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(state),
                    broadcast(context, CallActionReceiver.ACTION_DECLINE),
                    activityIntent(context, 2, answer = true)
                )
            )
            .build()
        // Мелодия звучит по кругу, пока уведомление не заменено или не снято.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notification
    }

    private fun ongoing(context: Context, state: CallUiState): Notification {
        val connected = state.state == CallState.CONNECTED
        val text = when {
            state.isOnHold -> "На удержании"
            connected && state.isReconnecting -> "Связь прервалась, восстанавливаем…"
            connected && state.isMuted -> "Идёт звонок · микрофон выключен"
            connected -> "Идёт звонок"
            state.signalToCopy != null -> state.statusMessage
            else -> state.statusMessage.ifBlank { "Соединяемся…" }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(state.contactName.ifBlank { "Собеседник" })
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setContentIntent(activityIntent(context, 1, answer = false))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person(state),
                    broadcast(context, CallActionReceiver.ACTION_HANGUP)
                )
            )
        if (connected) {
            builder
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - state.connectedAt))
                .addAction(
                    0,
                    if (state.isMuted) "Включить микрофон" else "Выключить микрофон",
                    broadcast(context, CallActionReceiver.ACTION_TOGGLE_MUTE)
                )
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    fun showMissedCall(context: Context, name: String) {
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Пропущенный звонок")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, 3, answer = false))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(MISSED_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missed call notification not allowed", e)
        }
    }

    /** Открывает приложение (например, чтобы запросить доступ к микрофону при ответе с гарнитуры). */
    fun openApp(context: Context, answer: Boolean) {
        try {
            context.startActivity(mainIntent(context, answer))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open app from background", e)
        }
    }

    private fun mainIntent(context: Context, answer: Boolean): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = if (answer) MainActivity.ACTION_ANSWER else MainActivity.ACTION_SHOW_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private fun activityIntent(context: Context, requestCode: Int, answer: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, mainIntent(context, answer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun broadcast(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
