package dev.stagelab.nativehook.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.stagelab.nativehook.R

/**
 * Standalone notification posted by `io.github.luckylca.stagelab` (separate from SystemUI system notifications).
 * Tapping it cleanly disables Stage Manager, updates SharedPreferences, cancels the notification,
 * and restores the native ColorOS desktop & navigation gestures.
 */
object StageModeNotificationHelper {

    const val PREFS_NAME = "stagelab_native_prefs"
    const val KEY_ENABLED = "enabled"

    const val ACTION_EXIT_FROM_NOTIFICATION = "io.github.luckylca.stagelab.ACTION_EXIT_FROM_NOTIFICATION"
    const val ACTION_SYNC_STATE = "io.github.luckylca.stagelab.ACTION_SYNC_STATE"
    const val ACTION_UI_STATE_CHANGED = "io.github.luckylca.stagelab.ACTION_UI_STATE_CHANGED"

    private const val CHANNEL_ID = "stagelab_standalone_mode"
    private const val NOTIFICATION_ID = 20260419

    fun showExitNotification(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StageLab 台前调度",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "台前调度独立运行状态通知，点击一键退出台前调度模式"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val exitIntent = Intent(context, StageModeStateReceiver::class.java).apply {
                action = ACTION_EXIT_FROM_NOTIFICATION
            }
            val pendingExit = PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID,
                exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stagelab_notif)
                .setColor(0xFF6750A4.toInt())
                .setContentTitle("StageLab 台前调度运行中")
                .setContentText("点击此处退出台前调度模式并恢复系统桌面")
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingExit)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancelExitNotification(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
