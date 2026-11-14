package com.chitragupta.shop.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Shop code", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
