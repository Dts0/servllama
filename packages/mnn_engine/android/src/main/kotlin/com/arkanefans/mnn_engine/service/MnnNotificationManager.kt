package com.arkanefans.mnn_engine.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class MnnNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MNN API Server", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun build(baseUrl: String, testUrl: String, modelName: String): Notification {
        val copyIntent = Intent(context, MnnServiceActionReceiver::class.java).apply {
            action = MnnServiceActionReceiver.ACTION_COPY_URL
            putExtra(MnnServiceActionReceiver.EXTRA_URL, baseUrl)
        }
        val testIntent = Intent(context, MnnServiceActionReceiver::class.java).apply {
            action = MnnServiceActionReceiver.ACTION_TEST_PAGE
            putExtra(MnnServiceActionReceiver.EXTRA_URL, testUrl)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("MNN Server is running")
            .setContentText("$baseUrl · $modelName")
            .setSmallIcon(R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_menu_set_as,
                "Copy URL",
                PendingIntent.getBroadcast(
                    context,
                    28021,
                    copyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                R.drawable.ic_menu_view,
                "Test Page",
                PendingIntent.getBroadcast(
                    context,
                    28022,
                    testIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "mnn_engine_server"
        const val NOTIFICATION_ID = 2802
    }
}
