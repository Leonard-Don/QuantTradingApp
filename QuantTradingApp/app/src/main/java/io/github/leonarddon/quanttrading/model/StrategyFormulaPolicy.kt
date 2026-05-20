package io.github.leonarddon.quanttrading.model

import kotlin.math.abs

/**
 * Pure expression parser/evaluator for [Strategy.formula].
 *
 * Replaces the previous keyword pattern-matching ("ma60" in formula ...) with a real
 * recursive-descent parser that turns a formula such as
 *
 *     close > ma20 && volume > avg_volume_5
 *
 * into a typed [FormulaNode] AST and evaluates it against a per-bar map of indicator
 * values ([FormulaContext]). The component is side-effect free: parsing and evaluation
 * are pure functions, so it lives in the `model` policy layer alongside the other
 * pure `*Policy` classes.
 *
 * Grammar (lowest to highest precedence):
 *
 *     or         := and ( "||" and )*
 *     and        := comparison ( "&&" comparison )*
 *     comparison := additive ( ( ">" | "<" | ">=" | "<=" | "==" | "!=" ) additive )?
 *     additive   := multiplicative ( ( "+" | "-" ) multiplicative )*
 *     multiplicative := unary ( ( "*" | "/" ) unary )*
 *     unary      := ( "!" | "-" ) unary | primary
 *     primary    := number | identifier | "(" or ")" | call
 *     call       := identifier "(" or ")"          // currently only abs(x)
 *
 * Comparisons are non-associative (`a > b > c` is rejected). A bare arithmetic
 * expression with no comparison is treated as truthy when non-zero, which keeps
 * formulas like `abs(close - ma20) / ma20` usable as a boolean signal.
 */
object StrategyFormulaPolicy {

    /** Per-bar indicator values keyed by lower-case indicator name (e.g. "close", "ma20"). */
    data class FormulaContext(val indicators: Map<String, Double>) {
        fun resolve(name: String): Double? = indicators[name.lowercase()]

        companion object {
            fun of(vararg pairs: Pair<String, Double>): FormulaContext =
                FormulaContext(pairs.associate { it.first.lowercase() to it.second })
        }
    }

    /** Outcome of [evaluate]: either a boolean signal or a structured failure reason. */
    sealed class EvalResult {
        data class Signal(val matched: Boolean) : EvalResult()
        data class Error(val reason: String) : EvalResult()
    }

    /** Numeric vs. boolean value produced while walking the AST. */
    private sealed class FormulaValue {
        data class Number(val value: Double) : FormulaValue()
        data class Bool(val value: Boolean) : FormulaValue()
    }

    /** Parsed formula AST node. */
    sealed class FormulaNode {
        data class NumberLiteral(val value: Double) : FormulaNode()
        data class Indicator(val name: String) : FormulaNode()
        data class FunctionCall(val name: String, val argument: FormulaNode) : FormulaNode()
        data class Unary(val operator: String, val operand: FormulaNode) : FormulaNode()
        data class Arithmetic(
            val operator: String,
            val left: FormulaNode,
            val right: FormulaNode
        ) : FormulaNode()
        data class Comparison(
            val operator: String,
            val left: FormulaNode,
            val right: FormulaNode
        ) : FormulaNode()
        data class Logical(
            val operator: String,
            val left: FormulaNode,
            val right: FormulaNode
        ) : FormulaNode()
    }

    /** Thrown internally while parsing; surfaced to callers as a structured failure. */
    class FormulaException(message: String) : Exception(message)

    private const val MAX_LENGTH = 160
    private val SUPPORTED_FUNCTIONS = setOf("abs")

    /**
     * Lightweight syntactic gate kept for callers that only need a yes/no check
     * (e.g. UI input validation). Returns true when the formula parses cleanly.
     */
    fun isParseable(formula: String): Boolean = parse(formula) != null

    /**
     * Parse [formula] into a [FormulaNode] AST, or null when the formula is blank,
     * over the length limit, or malformed. Pure: never throws to the caller.
     */
    fun parse(formula: String): FormulaNode? {
        if (formula.isBlank() || formula.length > MAX_LENGTH) return null
        return runCatching {
            val tokens = tokenize(formula)
            Parser(tokens).parseProgram()
        }.getOrNull()
    }

    /**
     * Parse [formula] and, on failure, return a human-readable reason.
     * Useful when the caller wants to explain *why* a formula was rejected.
     */
    fun parseDetailed(formula: String): Pair<FormulaNode?, String?> {
        if (formula.isBlank()) return null to "公式为空。"
        if (formula.length > MAX_LENGTH) return null to "公式长度超过 $MAX_LENGTH 字符上限。"
        return runCatching {
            val tokens = tokenize(formula)
            Parser(tokens).parseProgram()
        }.fold(
            onSuccess = { it to null },
            onFailure = { (null to (it.message ?: "公式解析失败。")) }
        )
    }

    /**
     * Evaluate [formula] against [context]. Parses on every call; callers in a hot
     * loop should [parse] once and reuse [evaluateNode].
     */
    fun evaluate(formula: String, context: FormulaContext): EvalResult {
        val (node, error) = parseDetailed(formula)
        if (node == null) return EvalResult.Error(error ?: "公式解析失败。")
        return evaluateNode(node, context)
    }

    /** Evaluate an already-parsed [node] against [context]. */
    fun evaluateNode(node: FormulaNode, context: FormulaContext): EvalResult {
        return runCatching {
            when (val value = eval(node, context)) {
                is FormulaValue.Bool -> EvalResult.Signal(value.value)
                is FormulaValue.Number -> EvalResult.Signal(value.value != 0.0)
            }
        }.getOrElse { EvalResult.Error(it.message ?: "公式求值失败。") }
    }

    /**
     * Convenience boolean evaluation. Returns [default] when the formula is malformed
     * or an indicator is missing, so callers that just want a signal need no branching.
     */
    fun matches(formula: String, context: FormulaContext, default: Boolean = false): Boolean {
        return when (val result = evaluate(formula, context)) {
            is EvalResult.Signal -> result.matched
            is EvalResult.Error -> default
        }
    }

    /** Same as [matches] but for a pre-parsed [node]. */
    fun matchesNode(node: FormulaNode, context: FormulaContext, default: Boolean = false): Boolean {
        return when (val result = evaluateNode(node, context)) {
            is EvalResult.Signal -> result.matched
            is EvalResult.Error -> default
        }
    }

    /** Distinct indicator names referenced by the formula (lower-cased). */
    fun referencedIndicators(formula: String): Set<String> {
        val node = parse(formula) ?: return emptySet()
        val out = linkedSetOf<String>()
        collectIndicators(node, out)
        return out
    }

    private fun collectIndicators(node: FormulaNode, out: MutableSet<String>) {
        when (node) {
            is FormulaNode.Indicator -> out += node.name
            is FormulaNode.NumberLiteral -> Unit
            is FormulaNode.FunctionCall -> collectIndicators(node.argument, out)
            is FormulaNode.Unary -> collectIndicators(node.operand, out)
            is FormulaNode.Arithmetic -> {
                collectIndicators(node.left, out)
                collectIndicators(node.right, out)
            }
            is FormulaNode.Comparison -> {
                collectIndicators(node.left, out)
                collectIndicators(node.right, out)
            }
            is FormulaNode.Logical -> {
                collectIndicators(node.left, out)
                collectIndicators(node.right, out)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Evaluation
    // ---------------------------------------------------------------------

    private fun eval(node: FormulaNode, context: FormulaContext): FormulaValue {
        return when (node) {
            is FormulaNode.NumberLiteral -> FormulaValue.Number(node.value)
            is FormulaNode.Indicator -> {
                val value = context.resolve(node.name)
                    ?: throw FormulaException("指标 ${node.name} 缺少当前值。")
                if (!value.isFinite()) throw FormulaException("指标 ${node.name} 的值非法。")
                FormulaValue.Number(value)
            }
            is FormulaNode.FunctionCall -> {
                val arg = asNumber(eval(node.argument, context), node.name)
                when (node.name) {
                    "abs" -> FormulaValue.Number(abs(arg))
                    else -> throw FormulaException("不支持的函数 ${node.name}。")
                }
            }
            is FormulaNode.Unary -> when (node.operator) {
                "-" -> FormulaValue.Number(-asNumber(eval(node.operand, context), "一元负号"))
                "!" -> FormulaValue.Bool(!asBool(eval(node.operand, context)))
                else -> throw FormulaException("不支持的一元运算符 ${node.operator}。")
            }
            is FormulaNode.Arithmetic -> {
                val left = asNumber(eval(node.left, context), node.operator)
                val right = asNumber(eval(node.right, context), node.operator)
                val result = when (node.operator) {
                    "+" -> left + right
                    "-" -> left - right
                    "*" -> left * right
                    "/" -> {
                        if (right == 0.0) throw FormulaException("公式出现除以零。")
                        left / right
                    }
                    else -> throw FormulaException("不支持的算术运算符 ${node.operator}。")
                }
                if (!result.isFinite()) throw FormulaException("算术结果非法（溢出或非数）。")
                FormulaValue.Number(result)
            }
            is FormulaNode.Comparison -> {
                val left = asNumber(eval(node.left, context), node.operator)
                val right = asNumber(eval(node.right, context), node.operator)
                val matched = when (node.operator) {
                    ">" -> left > right
                    "<" -> left < right
                    ">=" -> left >= right
                    "<=" -> left <= right
                    "==" -> left == right
                    "!=" -> left != right
                    else -> throw FormulaException("不支持的比较运算符 ${node.operator}。")
                }
                FormulaValue.Bool(matched)
            }
            is FormulaNode.Logical -> when (node.operator) {
                // Short-circuit, matching Kotlin/Java && and || semantics.
                "&&" -> {
                    val left = asBool(eval(node.left, context))
                    if (!left) FormulaValue.Bool(false)
                    else FormulaValue.Bool(asBool(eval(node.right, context)))
                }
                "||" -> {
                    val left = asBool(eval(node.left, context))
                    if (left) FormulaValue.Bool(true)
                    else FormulaValue.Bool(asBool(eval(node.right, context)))
                }
                else -> throw FormulaException("不支持的逻辑运算符 ${node.operator}。")
            }
        }
    }

    private fun asNumber(value: FormulaValue, context: String): Double = when (value) {
        is FormulaValue.Number -> value.value
        is FormulaValue.Bool -> throw FormulaException("$context 需要数值操作数，但得到布尔值。")
    }

    private fun asBool(value: FormulaValue): Boolean = when (value) {
        is FormulaValue.Bool -> value.value
        // A non-zero number acts as truthy so `abs(close - ma20) / ma20` is usable alone.
        is FormulaValue.Number -> value.value != 0.0
    }

    // ---------------------------------------------------------------------
    // Tokenizer
    // ---------------------------------------------------------------------

    private sealed class Token {
        data class Number(val value: Double) : Token()
        data class Identifier(val name: String) : Token()
        data class Operator(val symbol: String) : Token()
        object LParen : Token()
        object RParen : Token()
    }

    private fun tokenize(formula: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = formula.length
        while (i < n) {
            val c = formula[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { tokens += Token.LParen; i++ }
                c == ')' -> { tokens += Token.RParen; i++ }
                c.isDigit() || (c == '.' && i + 1 < n && formula[i + 1].isDigit()) -> {
                    val start = i
                    var seenDot = false
                    while (i < n && (formula[i].isDigit() || (formula[i] == '.' && !seenDot))) {
                        if (formula[i] == '.') seenDot = true
                        i++
                    }
                    val raw = formula.substring(start, i)
                    val value = raw.toDoubleOrNull()
                        ?: throw FormulaException("无法解析数字字面量 \"$raw\"。")
                    tokens += Token.Number(value)
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < n && (formula[i].isLetterOrDigit() || formula[i] == '_')) i++
                    tokens += Token.Identifier(formula.substring(start, i))
                }
                else -> {
                    val two = if (i + 1 < n) formula.substring(i, i + 2) else ""
                    when (two) {
                        "&&", "||", ">=", "<=", "==", "!=" -> {
                            tokens += Token.Operator(two)
                            i += 2
                        }
                        else -> when (c) {
                            '+', '-', '*', '/', '>', '<', '!' -> {
                                tokens += Token.Operator(c.toString())
                                i++
                            }
                            '&' -> throw FormulaException("逻辑与必须写作 \"&&\"。")
                            '|' -> throw FormulaException("逻辑或必须写作 \"||\"。")
                            '=' -> throw FormulaException("相等比较必须写作 \"==\"。")
                            else -> throw FormulaException("公式包含非法字符 '$c'。")
                        }
                    }
                }
            }
        }
        if (tokens.isEmpty()) throw FormulaException("公式没有可解析的内容。")
        return tokens
    }

    // ---------------------------------------------------------------------
    // Recursive-descent parser
    // ---------------------------------------------------------------------

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun parseProgram(): FormulaNode {
            val node = parseOr()
            if (pos != tokens.size) {
                throw FormulaException("公式存在多余或无法解析的内容。")
            }
            return node
        }

        private fun parseOr(): FormulaNode {
            var node = parseAnd()
            while (matchOperator("||")) {
                val right = parseAnd()
                node = FormulaNode.Logical("||", node, right)
            }
            return node
        }

        private fun parseAnd(): FormulaNode {
            var node = parseComparison()
            while (matchOperator("&&")) {
                val right = parseComparison()
                node = FormulaNode.Logical("&&", node, right)
            }
            return node
        }

        private fun parseComparison(): FormulaNode {
            val left = parseAdditive()
            val op = peekComparisonOperator() ?: return left
            pos++
            val right = parseAdditive()
            // Comparisons are non-associative: reject `a > b > c`.
            if (peekComparisonOperator() != null) {
                throw FormulaException("不支持连续比较，请用 && 连接多个条件。")
            }
            return FormulaNode.Comparison(op, left, right)
        }

        private fun parseAdditive(): FormulaNode {
            var node = parseMultiplicative()
            while (true) {
                val op = peekArithmeticOperator(setOf("+", "-")) ?: break
                pos++
                val right = parseMultiplicative()
                node = FormulaNode.Arithmetic(op, node, right)
            }
            return node
        }

        private fun parseMultiplicative(): FormulaNode {
            var node = parseUnary()
            while (true) {
                val op = peekArithmeticOperator(setOf("*", "/")) ?: break
                pos++
                val right = parseUnary()
                node = FormulaNode.Arithmetic(op, node, right)
            }
            return node
        }

        private fun parseUnary(): FormulaNode {
            val token = tokens.getOrNull(pos)
            if (token is Token.Operator && (token.symbol == "-" || token.symbol == "!")) {
                pos++
                return FormulaNode.Unary(token.symbol, parseUnary())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): FormulaNode {
            val token = tokens.getOrNull(pos)
                ?: throw FormulaException("公式在期望操作数处提前结束。")
            return when (token) {
                is Token.Number -> {
                    pos++
                    FormulaNode.NumberLiteral(token.value)
                }
                is Token.Identifier -> {
                    pos++
                    val next = tokens.getOrNull(pos)
                    if (next is Token.LParen) {
                        val name = token.name.lowercase()
                        if (name !in SUPPORTED_FUNCTIONS) {
                            throw FormulaException("不支持的函数 \"${token.name}\"。")
                        }
                        pos++ // consume '('
                        val arg = parseOr()
                        expect(Token.RParen) { "函数 ${token.name} 缺少右括号。" }
                        FormulaNode.FunctionCall(name, arg)
                    } else {
                        FormulaNode.Indicator(token.name.lowercase())
                    }
                }
                is Token.LParen -> {
                    pos++
                    val inner = parseOr()
                    expect(Token.RParen) { "括号表达式缺少右括号。" }
                    inner
                }
                is Token.RParen -> throw FormulaException("出现未配对的右括号。")
                is Token.Operator -> throw FormulaException("运算符 \"${token.symbol}\" 缺少左侧操作数。")
            }
        }

        private fun matchOperator(symbol: String): Boolean {
            val token = tokens.getOrNull(pos)
            if (token is Token.Operator && token.symbol == symbol) {
                pos++
                return true
            }
            return false
        }

        private fun peekComparisonOperator(): String? {
            val token = tokens.getOrNull(pos)
            if (token is Token.Operator && token.symbol in COMPARISON_OPERATORS) {
                return token.symbol
            }
            return null
        }

        private fun peekArithmeticOperator(allowed: Set<String>): String? {
            val token = tokens.getOrNull(pos)
            if (token is Token.Operator && token.symbol in allowed) {
                return token.symbol
            }
            return null
        }

        private inline fun expect(expected: Token, message: () -> String) {
            if (tokens.getOrNull(pos) != expected) throw FormulaException(message())
            pos++
        }

        companion object {
            private val COMPARISON_OPERATORS = setOf(">", "<", ">=", "<=", "==", "!=")
        }
    }
}
