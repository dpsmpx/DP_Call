package com.dpcall.app.call

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.dpcall.app.ui.MainActivity

/**
 * Невидимая точка входа для «Поделиться → DP Call» и выделения текста.
 *
 * Получив приглашение (Offer), запускает входящий звонок: пользователь остаётся
 * в мессенджере и видит стандартное всплывающее уведомление «Принять / Отклонить».
 * Получив ответ (Answer) на наш звонок — применяет его и открывает экран звонка.
 */
class SignalReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)

        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }?.toString().orEmpty()

        if (text.isBlank()) {
            toast("В сообщении нет кода DP Call")
        } else {
            when (val result = CallManager.handleSignal(text)) {
                SignalResult.Ringing -> {
                    // Без права на уведомления всплывающего окна не будет — показываем экран.
                    if (!CallNotifications.canPostNotifications(this)) openCallScreen()
                }
                SignalResult.AnswerApplied -> openCallScreen()
                SignalResult.Ignored -> openCallScreen()
                SignalResult.Own -> toast("Это ваш собственный код — отправьте его собеседнику")
                SignalResult.Busy -> toast("Сначала завершите текущий звонок")
                SignalResult.Unexpected -> toast("Это ответ на звонок, но вы сейчас никому не звоните")
                is SignalResult.Invalid -> toast(result.message)
            }
        }
        finish()
    }

    private fun openCallScreen() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
