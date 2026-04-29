package com.tianxian.quant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ResearchReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val notificationsEnabled = LocalStateRepository.getUserState().notificationsEnabled
                when (intent?.action) {
                    Intent.ACTION_BOOT_COMPLETED -> {
                        if (notificationsEnabled) {
                            NotificationHelper.scheduleDailyReminder(appContext)
                        }
                    }
                    NotificationHelper.ACTION_RESEARCH_REMINDER -> {
                        if (notificationsEnabled) {
                            NotificationHelper.showResearchReminder(appContext)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
