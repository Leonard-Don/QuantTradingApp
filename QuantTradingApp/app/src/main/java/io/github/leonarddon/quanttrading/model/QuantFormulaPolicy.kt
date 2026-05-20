package io.github.leonarddon.quanttrading.model

object QuantFormulaPolicy {
    private val allowed = Regex("^[a-zA-Z0-9_().+\\-*/<>=!&|\\s]+$")

    fun isAllowed(formula: String): Boolean {
        return formula.isNotBlank() && formula.length <= MAX_LENGTH && allowed.matches(formula)
    }

    private const val MAX_LENGTH = 160
}
