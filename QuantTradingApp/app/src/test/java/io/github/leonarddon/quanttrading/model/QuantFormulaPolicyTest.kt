package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantFormulaPolicyTest {
    @Test
    fun acceptsLegalFormula() {
        assertTrue(QuantFormulaPolicy.isAllowed("close > ma20 && volume > avg_volume_5"))
    }

    @Test
    fun rejectsBlankFormula() {
        assertFalse(QuantFormulaPolicy.isAllowed("   "))
    }

    @Test
    fun rejectsFormulaLongerThanLimit() {
        assertFalse(QuantFormulaPolicy.isAllowed("a".repeat(161)))
    }

    @Test
    fun rejectsChineseFormula() {
        assertFalse(QuantFormulaPolicy.isAllowed("收盘价 > ma20"))
    }

    @Test
    fun rejectsUnsupportedCharacters() {
        assertFalse(QuantFormulaPolicy.isAllowed("close > ma20; drop"))
    }

    @Test
    fun explainsRejectedFormulasWithParserReason() {
        assertNull(QuantFormulaPolicy.rejectionReason("close > ma20"))
        assertTrue(QuantFormulaPolicy.rejectionReason("close >")!!.contains("提前结束"))
        assertTrue(
            QuantFormulaPolicy.rejectionReason("close > ma20 & volume > avg_volume_5")!!
                .contains("&&")
        )
        assertTrue(QuantFormulaPolicy.rejectionReason("收盘价 > ma20")!!.contains("英文变量"))
    }

    @Test
    fun explainsUnsupportedPunctuationWithRejectedCharacter() {
        val reason = QuantFormulaPolicy.rejectionReason("close @ ma20")!!

        assertTrue(reason.contains("@"))
    }

    @Test
    fun explainsFullWidthPunctuationWithRejectedCharacter() {
        val reason = QuantFormulaPolicy.rejectionReason("close ＞ ma20")!!

        assertTrue(reason.contains("＞"))
    }

    @Test
    fun explainsReversedComparisonOperators() {
        val greaterReason = QuantFormulaPolicy.rejectionReason("close => ma20")!!
        val lessReason = QuantFormulaPolicy.rejectionReason("close =< ma20")!!

        assertTrue(greaterReason.contains(">="))
        assertTrue(lessReason.contains("<="))
    }

    @Test
    fun explainsPercentLiteralsAsDecimals() {
        val reason = QuantFormulaPolicy.rejectionReason("abs(close - ma20) / ma20 < 8%")!!

        assertTrue(reason.contains("百分比"))
        assertTrue(reason.contains("0.08"))
    }

    @Test
    fun explainsFullWidthPercentLiteralsAsDecimals() {
        val reason = QuantFormulaPolicy.rejectionReason("abs(close - ma20) / ma20 < 8％")!!

        assertTrue(reason.contains("百分比"))
        assertTrue(reason.contains("0.08"))
    }

    @Test
    fun explainsFullWidthDigitLiteralsAsHalfWidthDigits() {
        val reason = QuantFormulaPolicy.rejectionReason("abs(close - ma20) / ma20 < ８")!!

        assertTrue(reason.contains("全角数字"))
        assertTrue(reason.contains("8"))
    }

    @Test
    fun explainsFullWidthDecimalPointAsHalfWidthDecimalPoint() {
        val reason = QuantFormulaPolicy.rejectionReason("abs(close - ma20) / ma20 < 0．08")!!

        assertTrue(reason.contains("全角小数点"))
        assertTrue(reason.contains("0.08"))
    }
}
