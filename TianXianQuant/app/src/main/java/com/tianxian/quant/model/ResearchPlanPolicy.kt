package com.tianxian.quant.model

import java.util.Locale

data class ResearchPlanItem(
    val title: String,
    val detail: String,
    val priority: String,
    val source: String
)

data class ResearchPlanReport(
    val score: Int,
    val grade: String,
    val headline: String,
    val dailyFocus: String,
    val planItems: List<ResearchPlanItem>,
    val riskReviewItems: List<String>,
    val trackingChecklist: List<String>,
    val exportText: String
)

object ResearchPlanPolicy {
    fun evaluate(
        date: String,
        upCount: Int,
        downCount: Int,
        totalAmount: Double,
        hotSectors: List<SectorInfo>,
        strongStocks: List<StockInfo>,
        watchlistStocks: List<StockInfo>,
        watchlistHealthReport: WatchlistHealthReport?,
        portfolioStressReport: PortfolioStressReport?,
        portfolioHoldingReport: PortfolioHoldingReport?,
        dailyBriefReport: DailyResearchBriefReport?
    ): ResearchPlanReport {
        val totalBreadth = (upCount + downCount).coerceAtLeast(1)
        val upRatio = upCount * 1.0 / totalBreadth
        val marketPriority = when {
            upRatio < 0.35 -> "高"
            upRatio < 0.52 -> "中"
            else -> "常规"
        }
        val leadingSector = hotSectors.firstOrNull()
        val strongestStock = strongStocks.firstOrNull()
        val holdingTop = portfolioHoldingReport?.positions?.firstOrNull()
        val riskCount = listOfNotNull(
            watchlistHealthReport?.riskItems,
            portfolioStressReport?.riskItems,
            portfolioHoldingReport?.riskItems,
            dailyBriefReport?.riskItems
        ).sumOf { it.size }

        val planItems = buildList {
            add(
                ResearchPlanItem(
                    title = "市场宽度复查",
                    detail = "上涨 $upCount 只、下跌 $downCount 只，上涨占比 ${formatRatio(upRatio)}，样本成交额 ${formatAmount(totalAmount)}亿。",
                    priority = marketPriority,
                    source = "市场"
                )
            )
            leadingSector?.let {
                add(
                    ResearchPlanItem(
                        title = "板块轮动验证",
                        detail = "${it.name} 样本涨跌 ${formatPercent(it.changePercent)}，代表样本 ${it.leadingStock}，复查是否存在单一板块叙事偏差。",
                        priority = if (it.changePercent < 0.0) "中" else "常规",
                        source = "板块"
                    )
                )
            }
            if (watchlistStocks.isEmpty()) {
                add(
                    ResearchPlanItem(
                        title = "建立自选观察池",
                        detail = "当前自选池为空，先加入至少 5 只跨行业样本，后续体检、压力测试和简报才有个性化口径。",
                        priority = "高",
                        source = "自选"
                    )
                )
            } else {
                add(
                    ResearchPlanItem(
                        title = "自选池体检跟踪",
                        detail = "自选池 ${watchlistStocks.size} 只，健康 ${watchlistHealthReport?.score?.toString() ?: "暂无"} 分；优先复查评分变化和风险项。",
                        priority = if ((watchlistHealthReport?.score ?: 70) < 60) "高" else "中",
                        source = "自选"
                    )
                )
            }
            holdingTop?.let {
                add(
                    ResearchPlanItem(
                        title = "持仓组合复盘",
                        detail = "最高权重 ${it.holding.name}(${it.holding.code}) ${formatNumber(it.weightPercent)}%，组合浮盈亏 ${formatPercent(portfolioHoldingReport.profitLossPercent)}。",
                        priority = if (portfolioHoldingReport.score < 65 || it.weightPercent >= 35.0) "高" else "中",
                        source = "持仓"
                    )
                )
            } ?: add(
                ResearchPlanItem(
                    title = "补全持仓样本",
                    detail = "尚未记录持仓组合，可录入股票代码、成本价和数量，让复盘页生成浮盈亏和集中度报告。",
                    priority = "中",
                    source = "持仓"
                )
            )
            portfolioStressReport?.let {
                add(
                    ResearchPlanItem(
                        title = "压力情景复查",
                        detail = "压力评分 ${it.score}/100（${it.grade}），重点核对温和/系统/极端三类情景下的承压样本。",
                        priority = if (it.score < 65) "高" else "中",
                        source = "压力"
                    )
                )
            }
            strongestStock?.let {
                add(
                    ResearchPlanItem(
                        title = "强势样本留痕",
                        detail = "${it.name}(${it.code}) 涨跌 ${formatPercent(it.changePercent)}，记录其板块、成交额和均线状态是否延续。",
                        priority = "常规",
                        source = "样本"
                    )
                )
            }
            dailyBriefReport?.actionItems?.take(2)?.forEachIndexed { index, action ->
                add(
                    ResearchPlanItem(
                        title = "简报动作 ${index + 1}",
                        detail = action,
                        priority = "中",
                        source = "简报"
                    )
                )
            }
        }
            .distinctBy { it.title }
            .sortedWith(compareBy<ResearchPlanItem> { priorityRank(it.priority) }.thenBy { it.source })
            .take(8)

        val risks = buildList {
            dailyBriefReport?.riskItems?.forEach { add(it) }
            watchlistHealthReport?.riskItems?.forEach { add(it) }
            portfolioStressReport?.riskItems?.forEach { add(it) }
            portfolioHoldingReport?.riskItems?.forEach { add(it) }
            if (isEmpty()) add("暂无明显风险项，继续跟踪市场宽度、持仓集中度和样本成交额变化。")
        }
            .distinct()
            .take(6)

        val checklist = buildList {
            add("记录今日市场宽度、前三板块和强势样本，不把单日涨跌当成结论。")
            add("复查自选池评分、压力评分和风险项是否连续两次恶化。")
            if (portfolioHoldingReport == null) {
                add("补录至少 1 个持仓样本，建立成本价和数量口径。")
            } else {
                add("更新持仓样本，确认最高权重、浮盈亏和行情覆盖是否合理。")
            }
            add("把可验证假设写入研究记录，避免形成具体买卖指令或收益承诺。")
        }.distinct()

        val score = (76 +
            ((upRatio - 0.5) * 18).toInt() +
            ((watchlistHealthReport?.score ?: 62) - 70) / 4 +
            ((portfolioStressReport?.score ?: 62) - 70) / 4 +
            ((portfolioHoldingReport?.score ?: 62) - 70) / 4 -
            riskCount.coerceAtMost(10)
            ).coerceIn(0, 100)
        val headline = "$date 研究计划：${planItems.count { it.priority == "高" }} 个高优先级任务，${risks.size} 条风险复查。"
        val dailyFocus = buildDailyFocus(leadingSector, portfolioHoldingReport, watchlistStocks, upRatio)
        val exportText = buildExportText(
            headline = headline,
            dailyFocus = dailyFocus,
            planItems = planItems,
            risks = risks,
            checklist = checklist
        )

        return ResearchPlanReport(
            score = score,
            grade = gradeFor(score),
            headline = headline,
            dailyFocus = dailyFocus,
            planItems = planItems,
            riskReviewItems = risks,
            trackingChecklist = checklist,
            exportText = exportText
        )
    }

    private fun buildDailyFocus(
        leadingSector: SectorInfo?,
        holdingReport: PortfolioHoldingReport?,
        watchlistStocks: List<StockInfo>,
        upRatio: Double
    ): String {
        val marketTone = when {
            upRatio >= 0.6 -> "市场样本偏强"
            upRatio <= 0.35 -> "市场样本承压"
            else -> "市场样本分化"
        }
        val sectorText = leadingSector?.let { "领先板块 ${it.name} ${formatPercent(it.changePercent)}" }
            ?: "板块数据暂缺"
        val holdingText = holdingReport?.let { "持仓组合 ${it.score} 分，浮盈亏 ${formatPercent(it.profitLossPercent)}" }
            ?: "持仓组合待补全"
        return "$marketTone；$sectorText；自选池 ${watchlistStocks.size} 只；$holdingText。"
    }

    private fun buildExportText(
        headline: String,
        dailyFocus: String,
        planItems: List<ResearchPlanItem>,
        risks: List<String>,
        checklist: List<String>
    ): String {
        val itemText = planItems.joinToString("\n") {
            "- [${it.priority}] ${it.title}（${it.source}）：${it.detail}"
        }
        val riskText = risks.joinToString("\n") { "- $it" }
        val checklistText = checklist.joinToString("\n") { "- $it" }
        return "$headline\n\n今日主线：$dailyFocus\n\n研究任务：\n$itemText\n\n风险复查：\n$riskText\n\n跟踪清单：\n$checklistText\n\n说明：以上仅为本机研究计划，不构成投资建议或交易指令。"
    }

    private fun priorityRank(priority: String): Int {
        return when (priority) {
            "高" -> 0
            "中" -> 1
            else -> 2
        }
    }

    private fun gradeFor(score: Int): String {
        return when {
            score >= 85 -> "高质量"
            score >= 72 -> "完整"
            score >= 58 -> "待补强"
            else -> "缺口明显"
        }
    }

    private fun formatRatio(value: Double): String = "${formatNumber(value * 100.0)}%"

    private fun formatPercent(value: Double): String = "${if (value >= 0) "+" else ""}${formatNumber(value)}%"

    private fun formatAmount(value: Double): String = formatNumber(value)

    private fun formatNumber(value: Double): String = String.format(Locale.CHINA, "%.2f", value)
}
