package com.maziwasmart.app.reminders


import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object ReminderScheduler {

    const val EXTRA_TYPE = "reminder_type"
    const val EXTRA_HOUR = "reminder_hour"
    const val EXTRA_MINUTE = "reminder_minute"

    const val FEEDING = "feeding"
    const val MORNING_MILKING = "morning_milking"
    const val EVENING_MILKING = "evening_milking"

    fun scheduleDefaultReminders(context: Context) {
        scheduleDailyReminder(context, FEEDING, 6, 0)
        scheduleDailyReminder(context, MORNING_MILKING, 6, 0)
        scheduleDailyReminder(context, EVENING_MILKING, 17, 0)
    }

    fun scheduleDailyReminder(
        context: Context,
        type: String,
        hour: Int,
        minute: Int
    ) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
        }

        val requestCode = when (type) {
            FEEDING -> 100
            MORNING_MILKING -> 101
            EVENING_MILKING -> 102
            else -> return
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Calculate the next occurrence of the selected time.
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // Use exact alarms when permission is available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                // Fallback: Android may deliver this a little later.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }
    }
}
