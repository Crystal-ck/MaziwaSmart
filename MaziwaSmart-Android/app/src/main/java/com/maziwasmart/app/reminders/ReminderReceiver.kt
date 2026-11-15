package com.maziwasmart.app.reminders



import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "maziwa_reminders"
    }

    override fun onReceive(context: Context, intent: Intent) {

        val type = intent.getStringExtra(
            ReminderScheduler.EXTRA_TYPE
        ) ?: return

        val hour = intent.getIntExtra(
            ReminderScheduler.EXTRA_HOUR, 6
        )

        val minute = intent.getIntExtra(
            ReminderScheduler.EXTRA_MINUTE, 0
        )

        createNotificationChannel(context)

        val (title, message, notificationId) = when (type) {
            ReminderScheduler.FEEDING -> Triple(
                "Feeding Reminder",
                "It's time to feed your cows. Remember to record today's feeding.",
                100
            )

            ReminderScheduler.MORNING_MILKING -> Triple(
                "Morning Milking Reminder",
                "It's time for morning milking. Remember to record the milk yield.",
                101
            )

            ReminderScheduler.EVENING_MILKING -> Triple(
                "Evening Milking Reminder",
                "It's time for evening milking. Remember to record the milk yield.",
                102
            )

            else -> return
        }

        // Check notification permission on Android 13 and newer.
        val permissionGranted =
            Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(Notification.BigTextStyle().bigText(message))
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            }

            val manager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.notify(notificationId, notification)
        }

        // Schedule the next occurrence, even if notification permission
        // has not yet been granted.
        ReminderScheduler.scheduleDailyReminder(
            context,
            type,
            hour,
            minute
        )
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Maziwa Smart Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily feeding and milking reminders"
            }

            val manager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }
}

