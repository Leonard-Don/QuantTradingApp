package com.tianxian.quant.model

import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityDigestPolicyTest {
    @Test
    fun buildsDigestWithCategorySpecificActionsAndComments() {
        val report = CommunityDigestPolicy.build(
            post(
                category = "复盘笔记",
                content = "今天样本股成交额改善，板块扩散度提升，先记录事实再形成研究假设。"
            ),
            listOf(comment("研究员A", "需要补充指数口径"), comment("研究员B", "关注量能持续性"))
        )

        assertTrue(report.summary.contains("本机评论 2 条"))
        assertTrue(report.keyPoints.any { it.contains("成交额") || it.contains("量能") })
        assertTrue(report.keyPoints.any { it.contains("研究员A") })
        assertTrue(report.researchActions.any { it.contains("复盘页历史快照") })
        assertTrue(report.riskNotes.any { it.contains("研究参考口径") })
    }

    @Test
    fun flagsTradingActionAndOverPromisingWords() {
        val report = CommunityDigestPolicy.build(
            post(
                category = "投资逻辑",
                content = "这个样本可以买，后面必涨翻倍，估值也有优势。"
            ),
            emptyList()
        )

        assertTrue(report.keyPoints.any { it.contains("估值") })
        assertTrue(report.riskNotes.any { it.contains("交易动作") })
        assertTrue(report.riskNotes.any { it.contains("确定性承诺") })
        assertTrue(report.researchActions.any { it.contains("行业、估值和财报") })
    }

    @Test
    fun vipPostAddsPrivateCircleRiskNote() {
        val report = CommunityDigestPolicy.build(
            post(category = "VIP专属", content = "VIP 样本池模板只用于复盘训练。", isVip = true),
            emptyList()
        )

        assertTrue(report.keyPoints.any { it.contains("订阅圈子") })
        assertTrue(report.riskNotes.any { it.contains("VIP 内容") })
        assertTrue(report.researchActions.any { it.contains("自选池体检") })
    }

    private fun post(category: String, content: String, isVip: Boolean = false): Post {
        return Post(
            id = "1",
            author = "测试作者",
            avatar = "",
            title = "测试帖子",
            content = content,
            time = "刚刚",
            likes = 0,
            comments = 0,
            category = category,
            isVip = isVip
        )
    }

    private fun comment(author: String, content: String): PostComment {
        return PostComment(
            id = author,
            postId = "1",
            author = author,
            content = content,
            time = "刚刚",
            createdAt = 0L
        )
    }
}
