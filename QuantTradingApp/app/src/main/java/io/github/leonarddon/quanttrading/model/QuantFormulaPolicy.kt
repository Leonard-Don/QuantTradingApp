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
    private const val SUPPORTED_CHARACTER_MESSAGE =
        "公式仅支持英文变量、数字、空格和 + - * / < > = ! & | ( ) _。"

    fun isAllowed(formula: String): Boolean {
        return rejectionReason(formula) == null
    }

    fun rejectionReason(formula: String): String? {
        if (formula.isBlank()) return "公式不能为空。"
        if (formula.length > MAX_LENGTH) return "公式长度超过 $MAX_LENGTH 字符上限。"
        if (!allowed.matches(formula)) {
            return unsupportedCharacterReason(formula)
        }
        val (_, error) = StrategyFormulaPolicy.parseDetailed(formula)
        return error
    }

    private fun unsupportedCharacterReason(formula: String): String {
        val rejected = formula.firstOrNull { !isSupportedCharacter(it) }
        return if (rejected != null && rejected.code < 128) {
            "公式包含非法字符 '$rejected'。"
        } else if (rejected != null && !rejected.isLetterOrDigit() && !rejected.isWhitespace()) {
            "公式包含不支持的符号 '$rejected'，请改用英文半角运算符。"
        } else {
            SUPPORTED_CHARACTER_MESSAGE
        }
    }

    private fun isSupportedCharacter(c: Char): Boolean {
        return c in 'a'..'z' ||
            c in 'A'..'Z' ||
            c in '0'..'9' ||
            c in "_().+-*/<>=!&|" ||
            c.isWhitespace()
    }

    private const val MAX_LENGTH = 160
}
