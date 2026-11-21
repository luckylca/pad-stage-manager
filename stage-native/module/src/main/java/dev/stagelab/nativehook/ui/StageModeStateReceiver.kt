package dev.stagelab.nativehook.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.stagelab.nativehook.bridge.StageNativeRuntime

/**
 * Single-source-of-truth state receiver inside `dev.stagelab.nativehook`:
 * - Handles user tap on the standalone StageLab exit notification (`ACTION_EXIT_FROM_NOTIFICATION`).
 * - Handles state sync broadcasts from `SystemUI` (`ACTION_SYNC_STATE`) so `MainActivity` switch
 *   and standalone notification never drift out of sync.
 */
class StageModeStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences(StageModeNotificationHelper.PREFS_NAME, Context.MODE_PRIVATE)

        when (action) {
            StageModeNotificationHelper.ACTION_EXIT_FROM_NOTIFICATION -> {
                prefs.edit().putBoolean(StageModeNotificationHelper.KEY_ENABLED, false).apply()
                StageModeNotificationHelper.cancelExitNotification(context)

                // Notify SystemUI to disable Stage Manager and unlock navigation gestures.
                context.sendBroadcast(
                    Intent(StageNativeRuntime.ACTION_STAGE_CMD).apply {
                        setPackage("com.android.systemui")
                        putExtra("cmd", "disable_module")
                    },
                )

                // Notify MainActivity (if open) to flip its Switch to OFF immediately.
                context.sendBroadcast(
                    Intent(StageModeNotificationHelper.ACTION_UI_STATE_CHANGED).apply {
                        setPackage(context.packageName)
                        putExtra(StageModeNotificationHelper.KEY_ENABLED, false)
                    },
                )
            }

            StageModeNotificationHelper.ACTION_SYNC_STATE -> {
                val enabled = intent.getBooleanExtra(StageModeNotificationHelper.KEY_ENABLED, false)
                prefs.edit().putBoolean(StageModeNotificationHelper.KEY_ENABLED, enabled).apply()
                if (enabled) {
                    StageModeNotificationHelper.showExitNotification(context)
                } else {
                    StageModeNotificationHelper.cancelExitNotification(context)
                }
                context.sendBroadcast(
                    Intent(StageModeNotificationHelper.ACTION_UI_STATE_CHANGED).apply {
                        setPackage(context.packageName)
                        putExtra(StageModeNotificationHelper.KEY_ENABLED, enabled)
                    },
                )
            }
        }
    }
}
