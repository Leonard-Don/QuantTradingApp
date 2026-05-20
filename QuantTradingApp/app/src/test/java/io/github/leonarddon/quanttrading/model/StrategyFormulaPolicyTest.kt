package io.github.leonarddon.quanttrading.model

import io.github.leonarddon.quanttrading.model.StrategyFormulaPolicy.EvalResult
import io.github.leonarddon.quanttrading.model.StrategyFormulaPolicy.FormulaContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyFormulaPolicyTest {

    private fun ctx(vararg pairs: Pair<String, Double>): FormulaContext =
        FormulaContext.of(*pairs)

    private fun signal(formula: String, context: FormulaContext): Boolean {
        val result = StrategyFormulaPolicy.evaluate(formula, context)
        assertTrue("expected a signal but got $result", result is EvalResult.Signal)
        return (result as EvalResult.Signal).matched
    }

    private fun errorOf(formula: String, context: FormulaContext = ctx()): String {
        val result = StrategyFormulaPolicy.evaluate(formula, context)
        assertTrue("expected an error but got $result", result is EvalResult.Error)
        return (result as EvalResult.Error).reason
    }

    // --- basic comparisons -------------------------------------------------

    @Test
    fun evaluatesGreaterThanComparison() {
        assertTrue(signal("close > ma20", ctx("close" to 12.0, "ma20" to 10.0)))
        assertFalse(signal("close > ma20", ctx("close" to 9.0, "ma20" to 10.0)))
    }

    @Test
    fun evaluatesAllComparisonOperators() {
        val c = ctx("a" to 5.0, "b" to 5.0)
        assertTrue(signal("a >= b", c))
        assertTrue(signal("a <= b", c))
        assertTrue(signal("a == b", c))
        assertFalse(signal("a != b", c))
        assertFalse(signal("a > b", c))
        assertFalse(signal("a < b", c))
    }

    @Test
    fun comparesIndicatorAgainstNumericLiteral() {
        assertTrue(signal("pe < 35", ctx("pe" to 18.0)))
        assertFalse(signal("pe < 35", ctx("pe" to 41.0)))
        assertTrue(signal("close > 100.5", ctx("close" to 100.6)))
    }

    // --- logical operators -------------------------------------------------

    @Test
    fun evaluatesLogicalAnd() {
        val c = ctx("close" to 12.0, "ma20" to 10.0, "volume" to 200.0, "avg_volume_5" to 100.0)
        assertTrue(signal("close > ma20 && volume > avg_volume_5", c))
        assertFalse(
            signal(
                "close > ma20 && volume > avg_volume_5",
                ctx("close" to 12.0, "ma20" to 10.0, "volume" to 50.0, "avg_volume_5" to 100.0)
            )
        )
    }

    @Test
    fun evaluatesLogicalOr() {
        val c = ctx("close" to 8.0, "lower_band" to 9.0, "upper_band" to 20.0)
        assertTrue(signal("close < lower_band || close > upper_band", c))
        assertFalse(
            signal(
                "close < lower_band || close > upper_band",
                ctx("close" to 12.0, "lower_band" to 9.0, "upper_band" to 20.0)
            )
        )
    }

    @Test
    fun andBindsTighterThanOr() {
        // false && false || true  ==  (false && false) || true  == true
        val c = ctx("a" to 0.0, "b" to 0.0, "c" to 1.0, "d" to 1.0)
        assertTrue(signal("a > b && a > c || c == d", c))
        // false || true && false  ==  false || (true && false)  == false
        assertFalse(signal("a > c || c == d && a > c", c))
    }

    // --- arithmetic + precedence ------------------------------------------

    @Test
    fun arithmeticHonoursMultiplicationBeforeAddition() {
        // 2 + 3 * 4 == 14, so close(14) == that is true
        assertTrue(signal("close == 2 + 3 * 4", ctx("close" to 14.0)))
        // (2 + 3) * 4 == 20
        assertTrue(signal("close == (2 + 3) * 4", ctx("close" to 20.0)))
    }

    @Test
    fun arithmeticInsideComparisonOperands() {
        // close / ma60 > 1.08
        assertTrue(signal("close / ma60 > 1.08", ctx("close" to 120.0, "ma60" to 100.0)))
        assertFalse(signal("close / ma60 > 1.08", ctx("close" to 105.0, "ma60" to 100.0)))
    }

    @Test
    fun subtractionAndDivisionAreLeftAssociative() {
        // 20 - 5 - 5 == 10 ; 100 / 5 / 2 == 10
        assertTrue(signal("close == 20 - 5 - 5", ctx("close" to 10.0)))
        assertTrue(signal("close == 100 / 5 / 2", ctx("close" to 10.0)))
    }

    @Test
    fun supportsAbsFunctionCall() {
        // abs(close - ma20) / ma20 < 0.08
        assertTrue(
            signal("abs(close - ma20) / ma20 < 0.08", ctx("close" to 98.0, "ma20" to 100.0))
        )
        assertFalse(
            signal("abs(close - ma20) / ma20 < 0.08", ctx("close" to 80.0, "ma20" to 100.0))
        )
        // abs handles the other side of the band symmetrically
        assertTrue(
            signal("abs(close - ma20) / ma20 < 0.08", ctx("close" to 105.0, "ma20" to 100.0))
        )
    }

    @Test
    fun handlesUnaryMinusAndNot() {
        assertTrue(signal("close > -1", ctx("close" to 0.0)))
        assertTrue(signal("!(close > ma20)", ctx("close" to 5.0, "ma20" to 10.0)))
        assertFalse(signal("!(close > ma20)", ctx("close" to 15.0, "ma20" to 10.0)))
    }

    @Test
    fun bareArithmeticExpressionIsTruthyWhenNonZero() {
        // No comparison: non-zero result counts as a matched signal.
        assertTrue(signal("close - ma20", ctx("close" to 12.0, "ma20" to 10.0)))
        assertFalse(signal("close - ma20", ctx("close" to 10.0, "ma20" to 10.0)))
    }

    @Test
    fun indicatorNamesAreCaseInsensitive() {
        assertTrue(signal("CLOSE > Ma20", ctx("close" to 12.0, "ma20" to 10.0)))
    }

    @Test
    fun matchesSeedStrategyFormulas() {
        // Mirrors the formulas shipped in QuantViewModel.getSeedStrategies().
        assertTrue(
            signal(
                "close > ma20 && volume > avg_volume_5",
                ctx("close" to 12.0, "ma20" to 10.0, "volume" to 200.0, "avg_volume_5" to 100.0)
            )
        )
        assertTrue(
            signal(
                "close / ma60 > 1.08 && turnover > avg_turnover_20",
                ctx("close" to 120.0, "ma60" to 100.0, "turnover" to 80.0, "avg_turnover_20" to 30.0)
            )
        )
        assertTrue(
            signal(
                "pe < 35 && pb < 6 && turnover > avg_turnover_20",
                ctx("pe" to 20.0, "pb" to 3.0, "turnover" to 50.0, "avg_turnover_20" to 30.0)
            )
        )
    }

    // --- malformed-formula handling ---------------------------------------

    @Test
    fun blankFormulaIsRejected() {
        assertNull(StrategyFormulaPolicy.parse("   "))
        assertTrue(errorOf("   ").isNotBlank())
    }

    @Test
    fun overlongFormulaIsRejected() {
        assertNull(StrategyFormulaPolicy.parse("a".repeat(161)))
        assertTrue(errorOf("a".repeat(161)).contains("长度"))
    }

    @Test
    fun illegalCharactersAreRejected() {
        // Characters that cannot form a token are rejected by the tokenizer.
        assertNull(StrategyFormulaPolicy.parse("close > ma20; drop"))
        assertNull(StrategyFormulaPolicy.parse("close @ ma20"))
        assertNull(StrategyFormulaPolicy.parse("close > ma20 # note"))
    }

    @Test
    fun nonAsciiIdentifiersAreRejectedByQuantFormulaPolicy() {
        // The parser itself is charset-agnostic, but the ASCII-only gate in
        // QuantFormulaPolicy rejects Chinese-named operands before evaluation.
        assertFalse(QuantFormulaPolicy.isAllowed("收盘价 > ma20"))
        assertTrue(QuantFormulaPolicy.isAllowed("close > ma20"))
    }

    @Test
    fun danglingOperatorIsRejected() {
        assertNull(StrategyFormulaPolicy.parse("close >"))
        assertNull(StrategyFormulaPolicy.parse("close > ma20 &&"))
        assertNull(StrategyFormulaPolicy.parse("> ma20"))
    }

    @Test
    fun unbalancedParenthesesAreRejected() {
        assertNull(StrategyFormulaPolicy.parse("(close > ma20"))
        assertNull(StrategyFormulaPolicy.parse("close > ma20)"))
        assertNull(StrategyFormulaPolicy.parse("abs(close - ma20"))
    }

    @Test
    fun singleAmpersandOrPipeIsRejected() {
        assertNull(StrategyFormulaPolicy.parse("close > ma20 & volume > avg_volume_5"))
        assertNull(StrategyFormulaPolicy.parse("close > ma20 | close < ma5"))
        assertNull(StrategyFormulaPolicy.parse("close = ma20"))
    }

    @Test
    fun chainedComparisonIsRejected() {
        assertNull(StrategyFormulaPolicy.parse("ma5 < close < ma20"))
        assertTrue(errorOf("ma5 < close < ma20", ctx("ma5" to 1.0, "close" to 2.0, "ma20" to 3.0))
            .contains("连续比较"))
    }

    @Test
    fun unknownFunctionIsRejected() {
        assertNull(StrategyFormulaPolicy.parse("sqrt(close) > 3"))
    }

    @Test
    fun trailingTokensAreRejected() {
        assertNull(StrategyFormulaPolicy.parse("close > ma20 ma5"))
    }

    // --- indicator lookup failures ----------------------------------------

    @Test
    fun missingIndicatorYieldsStructuredError() {
        val reason = errorOf("close > ma20", ctx("close" to 12.0))
        assertTrue(reason.contains("ma20"))
    }

    @Test
    fun divisionByZeroYieldsStructuredError() {
        val reason = errorOf("close / ma60 > 1.0", ctx("close" to 10.0, "ma60" to 0.0))
        assertTrue(reason.contains("除以零"))
    }

    @Test
    fun nonFiniteIndicatorValueIsRejected() {
        val reason = errorOf("close > 1", ctx("close" to Double.NaN))
        assertTrue(reason.isNotBlank())
        assertTrue(
            errorOf("close > 1", ctx("close" to Double.POSITIVE_INFINITY)).isNotBlank()
        )
    }

    // --- matches() default behaviour --------------------------------------

    @Test
    fun matchesReturnsDefaultOnMalformedOrMissing() {
        // malformed -> default
        assertFalse(StrategyFormulaPolicy.matches("close >", ctx("close" to 1.0)))
        assertTrue(StrategyFormulaPolicy.matches("close >", ctx("close" to 1.0), default = true))
        // missing indicator -> default
        assertFalse(StrategyFormulaPolicy.matches("close > ma20", ctx("close" to 1.0)))
        // valid -> actual result, default ignored
        assertTrue(
            StrategyFormulaPolicy.matches(
                "close > ma20",
                ctx("close" to 12.0, "ma20" to 10.0),
                default = false
            )
        )
    }

    // --- AST shape + reuse -------------------------------------------------

    @Test
    fun parsesIntoExpectedAstShape() {
        val node = StrategyFormulaPolicy.parse("close > ma20 && volume > avg_volume_5")
        assertNotNull(node)
        node as StrategyFormulaPolicy.FormulaNode.Logical
        assertEquals("&&", node.operator)
        assertTrue(node.left is StrategyFormulaPolicy.FormulaNode.Comparison)
        assertTrue(node.right is StrategyFormulaPolicy.FormulaNode.Comparison)
        val left = node.left as StrategyFormulaPolicy.FormulaNode.Comparison
        assertEquals(">", left.operator)
        assertEquals(
            "close",
            (left.left as StrategyFormulaPolicy.FormulaNode.Indicator).name
        )
    }

    @Test
    fun preParsedNodeCanBeReused() {
        val node = StrategyFormulaPolicy.parse("close > ma20")!!
        assertTrue(
            StrategyFormulaPolicy.matchesNode(node, ctx("close" to 12.0, "ma20" to 10.0))
        )
        assertFalse(
            StrategyFormulaPolicy.matchesNode(node, ctx("close" to 8.0, "ma20" to 10.0))
        )
    }

    @Test
    fun referencedIndicatorsListsEveryOperand() {
        val refs = StrategyFormulaPolicy.referencedIndicators(
            "abs(close - ma20) / ma20 < 0.08 && volume > avg_volume_5"
        )
        assertEquals(setOf("close", "ma20", "volume", "avg_volume_5"), refs)
    }

    @Test
    fun referencedIndicatorsIsEmptyForMalformedFormula() {
        assertTrue(StrategyFormulaPolicy.referencedIndicators("close >").isEmpty())
    }

    @Test
    fun isParseableMatchesParseOutcome() {
        assertTrue(StrategyFormulaPolicy.isParseable("close > ma20"))
        assertFalse(StrategyFormulaPolicy.isParseable("close >"))
    }
}
