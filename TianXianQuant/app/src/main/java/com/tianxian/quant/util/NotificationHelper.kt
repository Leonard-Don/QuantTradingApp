package com.tianxian.quant.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tianxian.quant.MainActivity
import com.tianxian.quant.R
import com.tianxian.quant.model.PriceAlertTrigger
import com.tianxian.quant.model.StockPriceAlertPolicy
import com.tianxian.quant.receiver.ResearchReminderReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object NotificationHelper {
    const val ACTION_RESEARCH_REMINDER = "com.tianxian.quant.action.RESEARCH_REMINDER"

    private const val CHANNEL_ID = "research_alerts"
    private const val CHANNEL_NAME = "研究提醒"
    private const val REMINDER_ID = 1001
    private const val PRICE_ALERT_ID_BASE = 1200
    private const val REMINDER_REQUEST_CODE = 2026
    private const val OPEN_APP_REQUEST_CODE = 2027
    private const val REMINDER_HOUR = 18
    private const val REMINDER_MINUTE = 30

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "复盘、选股和量化模型的本地提醒"
        }
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun scheduleDailyReminder(context: Context) {
        ensureChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextReminderTimeMillis(),
            AlarmManager.INTERVAL_DAY,
            reminderPendingIntent(context)
        )
    }

    fun cancelDailyReminder(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(reminderPendingIntent(context))
    }

    fun showResearchReminder(
        context: Context,
        summary: String = "今日可以查看行情池、复盘样本和量化模型记录。"
    ) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("天线量化研究提醒")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openAppPendingIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        postNotification(context, notification)
    }

    fun showPriceAlert(context: Context, trigger: PriceAlertTrigger) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val summary = StockPriceAlertPolicy.triggerSummary(trigger)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("目标价研究提醒：${trigger.name}")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openStockPendingIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        postNotification(context, notification, priceAlertNotificationId(trigger.code))
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(
        context: Context,
        notification: Notification,
        notificationId: Int = REMINDER_ID
    ) {
        if (!canPostNotifications(context)) return
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify call.
        }
    }

    fun nextReminderTimeLabel(): String {
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return formatter.format(Date(nextReminderTimeMillis()))
    }

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ResearchReminderReceiver::class.java).apply {
            action = ACTION_RESEARCH_REMINDER
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REMINDER_REQUEST_CODE, intent, flags)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = MainActivity.reviewIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, OPEN_APP_REQUEST_CODE, intent, flags)
    }

    private fun openStockPendingIntent(context: Context): PendingIntent {
        val intent = MainActivity.stockIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, OPEN_APP_REQUEST_CODE + 1, intent, flags)
    }

    private fun priceAlertNotificationId(code: String): Int {
        return PRICE_ALERT_ID_BASE + (code.hashCode() and 0x7fffffff) % 700
    }

    private fun nextReminderTimeMillis(): Long {
        val now = Calendar.getInstance()
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, REMINDER_HOUR)
            set(Calendar.MINUTE, REMINDER_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }.timeInMillis
    }
}
