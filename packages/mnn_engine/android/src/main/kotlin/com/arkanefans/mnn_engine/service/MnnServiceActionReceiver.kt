package com.arkanefans.mnn_engine.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

class MnnServiceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        when (intent.action) {
            ACTION_COPY_URL -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MNN API URL", url))
                Toast.makeText(context, "MNN API URL copied", Toast.LENGTH_SHORT).show()
            }
            ACTION_TEST_PAGE -> {
                val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(open)
            }
        }
    }

    companion object {
        const val ACTION_COPY_URL = "com.arkanefans.mnn_engine.COPY_URL"
        const val ACTION_TEST_PAGE = "com.arkanefans.mnn_engine.TEST_PAGE"
        const val EXTRA_URL = "url"
    }
}
