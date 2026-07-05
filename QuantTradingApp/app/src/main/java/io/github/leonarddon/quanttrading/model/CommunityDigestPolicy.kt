package io.github.leonarddon.quanttrading.model

data class CommunityDigestReport(
    val title: String,
    val summary: String,
    val keyPoints: List<String>,
    val riskNotes: List<String>,
    val researchActions: List<String>
)

object CommunityDigestPolicy {
    fun build(post: Post, comments: List<PostComment>): CommunityDigestReport {
        val content = post.content.trim()
        val commentCount = comments.size
        val category = post.category.ifBlank { "未分类" }
        val keyPoints = buildKeyPoints(post, comments)
        val risks = buildRiskNotes(post)
        val actions = buildActions(post, comments)

        return CommunityDigestReport(
            title = "${post.title} · 研究纪要",
            summary = "分类：$category；作者：${post.author}；本机评论 $commentCount 条。${content.take(72)}${if (content.length > 72) "..." else ""}",
            keyPoints = keyPoints,
            riskNotes = risks,
            researchActions = actions
        )
    }

    private fun buildKeyPoints(post: Post, comments: List<PostComment>): List<String> {
        val points = mutableListOf<String>()
        points += when (post.category) {
            "量化研究" -> "重点沉淀模型假设、样本范围、回撤和胜率边界。"
            "复盘笔记" -> "重点沉淀市场事实、板块扩散度和情绪变化。"
            "投资逻辑" -> "重点沉淀行业景气度、估值位置和财报验证项。"
            FEATURED_CATEGORY -> "重点沉淀样本池、模型模板和风险约束。"
            else -> "重点沉淀研究问题、数据字段和后续验证路径。"
        }
        if (post.content.contains("成交额") || post.content.contains("量能")) {
            points += "正文提到成交额/量能，应与行情源和复盘快照交叉验证。"
        }
        if (post.content.contains("估值") || post.content.contains("财报")) {
            points += "正文涉及估值或财报，需要补充基本面字段和时间维度。"
        }
        if (comments.isNotEmpty()) {
            val authors = comments.map { it.author }.distinct().take(3).joinToString("、")
            points += "已有评论参与者：$authors，可继续围绕证据口径收敛讨论。"
        }
        return points.distinct().take(4)
    }

    private fun buildRiskNotes(post: Post): List<String> {
        val risks = mutableListOf<String>()
        if (post.content.contains("买") || post.content.contains("卖") || post.content.contains("加仓")) {
            risks += "正文存在可能被理解为交易动作的表述，发布或引用时需改写为研究观察。"
        }
        if (post.content.contains("必涨") || post.content.contains("稳赚") || post.content.contains("翻倍")) {
            risks += "正文存在夸大收益倾向，需要删除确定性承诺。"
        }
        if (risks.isEmpty()) {
            risks += "未发现明显交易指令或收益承诺表述，仍需保持研究参考口径。"
        }
        return risks
    }

    private fun buildActions(post: Post, comments: List<PostComment>): List<String> {
        val actions = mutableListOf<String>()
        actions += "把纪要同步到个人复盘记录，标注数据来源、样本范围和验证日期。"
        actions += when (post.category) {
            "量化研究" -> "用量化页历史模拟复核模型假设，不直接推导交易动作。"
            "复盘笔记" -> "用复盘页历史快照跟踪相同口径是否连续出现。"
            "投资逻辑" -> "补充行业、估值和财报数据后再形成研究判断。"
            FEATURED_CATEGORY -> "把样本池与自选池体检交叉比对，确认风险项是否一致。"
            else -> "补充可验证字段后再继续讨论。"
        }
        if (comments.isEmpty()) {
            actions += "邀请社区成员围绕数据口径提出补充问题，而不是给出结论。"
        } else {
            actions += "整理评论中的分歧点，下一轮只讨论可验证证据。"
        }
        return actions.distinct().take(4)
    }

    private const val FEATURED_CATEGORY = "专题研究"
}
