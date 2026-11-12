package com.dpsmpx.dpcall.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.dpsmpx.dpcall.call.SignalReceiverActivity

/** Системное «Поделиться» для текста; само приложение из списка исключается. */
fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, title).putExtra(
        Intent.EXTRA_EXCLUDE_COMPONENTS,
        arrayOf(ComponentName(context, SignalReceiverActivity::class.java))
    )
    context.startActivity(chooser)
}
