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
    const val LISTEN_NOTIFICATION_ID = 1003

    private const val CHANNEL_INCOMING = "incoming_calls"
    private const val CHANNEL_ONGOING = "ongoing_calls"
    private const val CHANNEL_MISSED = "missed_calls"
    private const val CHANNEL_LISTEN = "listening"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val incoming = NotificationChannel(
            CHANNEL_INCOMING, context.getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_incoming_desc)
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
            CHANNEL_ONGOING, context.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_ongoing_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(
            CHANNEL_MISSED, context.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT
        )
        val listen = NotificationChannel(
            CHANNEL_LISTEN, context.getString(R.string.channel_listen), NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.channel_listen_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(incoming, ongoing, missed, listen))
    }

    fun canPostNotifications(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Уведомление, соответствующее текущему состоянию звонка. */
    fun build(context: Context, state: CallUiState): Notification =
        if (state.state == CallState.RINGING) incoming(context, state) else ongoing(context, state)

    private fun person(context: Context, state: CallUiState): Person =
        Person.Builder().setName(state.contactName.ifBlank { context.getString(R.string.peer_default_name) }).setImportant(true).build()

    private fun incoming(context: Context, state: CallUiState): Notification {
        val content = activityIntent(context, 1, answer = false)
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(state.contactName.ifBlank { context.getString(R.string.peer_default_name) })
            .setContentText(context.getString(R.string.incoming_call_dpcall))
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
                    person(context, state),
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
            state.isOnHold -> context.getString(R.string.on_hold)
            connected && state.isReconnecting -> context.getString(R.string.status_reconnecting)
            connected && state.isMuted -> context.getString(R.string.notif_in_call_muted)
            connected -> context.getString(R.string.notif_in_call)
            state.signalToCopy != null -> state.statusMessage
            else -> state.statusMessage.ifBlank { context.getString(R.string.status_connecting) }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(state.contactName.ifBlank { context.getString(R.string.peer_default_name) })
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
                    person(context, state),
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
                    if (state.isMuted) context.getString(R.string.unmute) else context.getString(R.string.mute),
                    broadcast(context, CallActionReceiver.ACTION_TOGGLE_MUTE)
                )
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    /** Постоянное уведомление сервиса ожидания звонков. */
    fun listening(context: Context, status: RelayStatus): Notification =
        NotificationCompat.Builder(context, CHANNEL_LISTEN)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(if (status.online) context.getString(R.string.listen_ready) else context.getString(R.string.listen_offline))
            .setContentText(
                if (status.online) context.getString(R.string.relays_connected, status.connected, status.total)
                else context.getString(R.string.listen_connecting)
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(activityIntent(context, 4, answer = false))
            .build()

    /**
     * Показывает уведомление звонка напрямую, без foreground-сервиса: запасной
     * путь, когда система не разрешила запустить [CallService] из фона.
     */
    fun notifyDirect(context: Context, state: CallUiState) {
        try {
            NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, build(context, state))
        } catch (e: SecurityException) {
            Log.w(TAG, "Call notification not allowed", e)
        }
    }

    fun cancelDirect(context: Context) {
        NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
    }

    fun showMissedCall(context: Context, name: String) {
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.missed_call))
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
            if (answer) putExtra(MainActivity.EXTRA_ANSWER_TOKEN, CallManager.answerToken)
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
