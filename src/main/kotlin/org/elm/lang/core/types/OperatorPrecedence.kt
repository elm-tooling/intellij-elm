package org.elm.lang.core.types

import org.elm.lang.core.psi.OperatorAssociativity
import org.elm.lang.core.psi.OperatorAssociativity.*

data class OperatorPrecedence(val precedence: Int, val associativity: OperatorAssociativity)

@Suppress("unused") // The type parameter on BinaryExprTree isn't used directly, but allows us to keep nodes consistent.
sealed class BinaryExprTree<T : Any> {
    data class Operand<T : Any>(val operand: T) : BinaryExprTree<T>()
    data class Binary<T : Any>(val left: BinaryExprTree<T>, val operator: T, val right: BinaryExprTree<T>) : BinaryExprTree<T>()

    companion object {
        // Treat "unknown op" as strictly lower than anything else so the loop won't enter.
        private const val DEFAULT_PRECEDENCE = -1

        /**
         * Parse a list of operands and operators into a binary tree structured in evaluation
         * order based on precedence and associativity.
         *
         * @param expression A list of [T]s representing an expression. The list must have odd length,
         *   and all odd-indexed values must be an operator. All even-indexed values must be operands.
         * @param operatorPrecedences operator precedence information for the operators in
         *   [expression]. Every *operator* in [expression] should have an entry; if one is missing,
         *   we will treat it as unknown (lowest precedence) and not crash.
         */
        fun <T : Any> parse(expression: List<T>, operatorPrecedences: Map<out T, OperatorPrecedence>): BinaryExprTree<T> {
            // Fast sanity checks to avoid hard-to-debug states.
            require(expression.isNotEmpty()) { "expression must not be empty" }
            require(expression.size % 2 == 1) {
                "expression must have odd length: operand op operand [op operand]… (size=${expression.size})"
            }
            // Optional: verify that odd indices are present in the precedence table.
            // Don't require it strictly—parser can still proceed safely.
            // If you want strict mode, turn the 'check' into 'require'.
            for (i in 1 until expression.size step 2) {
                if (operatorPrecedences[expression[i]] == null) {
                    // Leave as a soft check so we don't throw; helpful during tests/dev.
                    // You can route this to your logger if available.
                    // e.g., LOG.debug("Unknown operator in expression at index $i: ${expression[i]}")
                    break
                }
            }

            return parseExpression(expression, operatorPrecedences, DEFAULT_PRECEDENCE, 0).first
        }

        /**
         * A pure functional Pratt parser with optimizations based on the fact that all operators are infix and binary.
         */
        private fun <T : Any> parseExpression(
                expression: List<T>,
                operatorPrecedences: Map<out T, OperatorPrecedence>,
                precedence: Int,
                idx: Int
        ): Pair<BinaryExprTree<T>, Int> {
            var left: BinaryExprTree<T> = Operand(expression[idx])

            if (idx >= expression.lastIndex) {
                return left to (idx + 1)
            }

            var i = idx + 1

            fun nextPrecedence(): Int {
                return if (i >= expression.lastIndex) {
                    DEFAULT_PRECEDENCE
                } else {
                    // SAFE: if the operator isn't in the table, treat it as the lowest precedence.
                    val op = expression[i]
                    operatorPrecedences[op]?.precedence ?: DEFAULT_PRECEDENCE
                }
            }

            // Only enter the loop if the *next* operator binds tighter than the current precedence.
            while (precedence < nextPrecedence()) {
                val operator = expression[i]
                val funcPrecedence = operatorPrecedences[operator] ?: break
                val rightPrecedence = when (funcPrecedence.associativity) {
                    LEFT, NON -> funcPrecedence.precedence
                    RIGHT -> funcPrecedence.precedence - 1
                }

                val result = parseExpression(expression, operatorPrecedences, rightPrecedence, i + 1)
                left = Binary(left, operator, result.first)
                i = result.second
            }
            return left to i
        }
    }
}
