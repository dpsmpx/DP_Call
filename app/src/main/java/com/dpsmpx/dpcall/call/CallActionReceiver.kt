package com.dpsmpx.dpcall.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Кнопки уведомления звонка: отклонить, завершить, микрофон. */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        CallManager.init(context)
        when (intent.action) {
            ACTION_DECLINE -> CallManager.declineIncoming()
            ACTION_HANGUP -> CallManager.hangup()
            ACTION_TOGGLE_MUTE -> CallManager.toggleMute()
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.dpsmpx.dpcall.action.DECLINE"
        const val ACTION_HANGUP = "com.dpsmpx.dpcall.action.HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.dpsmpx.dpcall.action.TOGGLE_MUTE"
    }
}
