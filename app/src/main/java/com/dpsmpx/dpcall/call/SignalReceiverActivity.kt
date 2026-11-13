package com.dpsmpx.dpcall.call

import com.dpsmpx.dpcall.R
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.dpsmpx.dpcall.signaling.SignalCodec
import com.dpsmpx.dpcall.ui.MainActivity
import com.dpsmpx.dpcall.util.CodeValidator

/**
 * Невидимая точка входа для «Поделиться → DP Call» и выделения текста.
 *
 * Получив приглашение (Offer), запускает входящий звонок: пользователь остаётся
 * в мессенджере и видит стандартное всплывающее уведомление «Принять / Отклонить».
 * Получив ответ (Answer) на наш звонок — применяет его и открывает экран звонка.
 * Получив код собеседника — открывает «Новый контакт» с уже вставленным кодом.
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

        // Сначала приглашение / ответ; если их нет — возможно, прислали код контакта.
        val contactCode = if (text.length <= SignalCodec.MAX_SIGNAL_CHARS * 2 && SignalCodec.extract(text) == null) {
            CodeValidator.extract(text)
        } else {
            null
        }
        if (text.isBlank() || text.length > SignalCodec.MAX_SIGNAL_CHARS * 2) {
            toast(getString(R.string.share_no_code))
        } else if (contactCode != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_ADD_CONTACT)
                    .putExtra(MainActivity.EXTRA_CODE, contactCode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } else {
            when (val result = CallManager.handleSignal(text)) {
                SignalResult.Ringing -> {
                    // Без права на уведомления всплывающего окна не будет — показываем экран.
                    if (!CallNotifications.canPostNotifications(this)) openCallScreen()
                }
                SignalResult.AnswerApplied -> openCallScreen()
                SignalResult.Ignored -> openCallScreen()
                SignalResult.Own -> toast(getString(R.string.share_own_code))
                SignalResult.Busy -> toast(getString(R.string.finish_current_call))
                SignalResult.Unexpected -> toast(getString(R.string.share_unexpected_answer))
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
