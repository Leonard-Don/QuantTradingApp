package io.github.leonarddon.quanttrading.model

/**
 * Validation gate for [Strategy.formula].
 *
 * Historically this only checked a character whitelist, so syntactically broken
 * formulas (e.g. `close >`) still passed. It now delegates to [StrategyFormulaPolicy],
 * which fully parses the formula into an AST — a formula is "allowed" only when it is
 * a real, evaluable expression. The character/length pre-checks are kept for fast
 * rejection and clearer error reporting.
 */
object QuantFormulaPolicy {
    private val allowed = Regex("^[a-zA-Z0-9_().+\\-*/<>=!&|\\s]+$")

    fun isAllowed(formula: String): Boolean {
        if (formula.isBlank() || formula.length > MAX_LENGTH) return false
        if (!allowed.matches(formula)) return false
        return StrategyFormulaPolicy.isParseable(formula)
    }

    private const val MAX_LENGTH = 160
}
