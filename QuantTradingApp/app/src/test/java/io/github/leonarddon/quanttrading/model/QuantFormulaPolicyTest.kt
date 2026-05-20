package io.github.leonarddon.quanttrading.model

import org.junit.Assert.assertFalse
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
}
