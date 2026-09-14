package com.flowpilot.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.flowpilot.MainActivity

class TeachingNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "flowpilot_teaching_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_TEACHING = "com.flowpilot.ACTION_STOP_TEACHING"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FlowPilot Teaching",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording controls while teaching FlowPilot"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showRecordingNotification(utterance: String, count: Int) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, StopTeachingReceiver::class.java).apply {
            action = ACTION_STOP_TEACHING
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("🔴 Recording: \"$utterance\"")
            .setContentText("Actions recorded: $count. Tap Done when finished.")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_save, "Done (Save)", stopPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun updateNotification(count: Int, lastAction: String) {
        val stopIntent = Intent(context, StopTeachingReceiver::class.java).apply {
            action = ACTION_STOP_TEACHING
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("🔴 Recording ($count actions captured)")
            .setContentText(lastAction.take(60))
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_save, "Done (Save)", stopPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun dismissNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}

class StopTeachingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == TeachingNotificationManager.ACTION_STOP_TEACHING) {
            TeachingCoordinator.activeInstance?.stopTeaching()
        }
    }
}
