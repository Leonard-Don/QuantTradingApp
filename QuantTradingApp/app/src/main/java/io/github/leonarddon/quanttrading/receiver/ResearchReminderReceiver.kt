package io.github.leonarddon.quanttrading.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.leonarddon.quanttrading.data.LocalStateRepository
import io.github.leonarddon.quanttrading.util.NotificationHelper
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
                            NotificationHelper.showResearchReminder(
                                appContext,
                                buildResearchReminderSummary()
                            )
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun buildResearchReminderSummary(): String {
        val latestSnapshot = LocalStateRepository.getReviewSnapshots(limit = 1).firstOrNull()
        val holdingCount = LocalStateRepository.getPortfolioHoldings().size
        val snapshotText = latestSnapshot?.let {
            "最近复盘 ${it.date}：上涨/下跌样本 ${it.upCount}/${it.downCount}，成交额 ${String.format(java.util.Locale.CHINA, "%.2f", it.totalAmount)}亿。"
        } ?: "暂无历史复盘快照，今晚可以生成第一份研究简报。"
        val holdingText = if (holdingCount > 0) {
            "本机持仓 $holdingCount 个，建议同步查看浮盈亏和集中度。"
        } else {
            "尚未记录持仓组合，可先录入成本价建立复盘口径。"
        }
        return "$snapshotText $holdingText"
    }
}
