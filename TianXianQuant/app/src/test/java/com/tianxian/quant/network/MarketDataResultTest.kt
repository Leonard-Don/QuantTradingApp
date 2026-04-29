package com.tianxian.quant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketDataResultTest {
    @Test
    fun successReturnsPayload() {
        val result = MarketDataResult.Success(listOf("600519"), "测试源")

        assertEquals(listOf("600519"), result.getOrNull())
        assertEquals("测试源", result.sourceOrNull())
    }

    @Test
    fun failureReturnsNullPayloadAndKeepsMessage() {
        val result = MarketDataResult.Failure("腾讯 quote 请求失败：HTTP 503")

        assertNull(result.getOrNull())
        assertEquals("腾讯 quote 请求失败：HTTP 503", result.message)
    }
}
