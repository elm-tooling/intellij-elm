package org.elm.lang.core.psi.elements

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.ElmBinOpPartTag

sealed class Pipeline {
    abstract val pipeline: ElmBinOpExpr
    abstract fun segments(): List<Segment>

    data class Segment(
            val expressionParts: List<ElmBinOpPartTag>,
            val comments: List<PsiComment>
    )

    data class LeftPipeline(override val pipeline: ElmBinOpExpr) : Pipeline() {
        override fun segments(): List<Segment> {
            val segments = mutableListOf<Segment>()
            var unprocessed = pipeline.parts.toList().reversed()
            while (true) {
                val currentPipeExpression = unprocessed
                        .takeWhile { !(it is ElmOperator && it.referenceName == "<|") }
                        .reversed()
                unprocessed = unprocessed.drop(currentPipeExpression.size + 1)
                segments += Segment(
                        currentPipeExpression.toList(),
                        currentPipeExpression.filterIsInstance<PsiComment>().toList()
                )

                if (currentPipeExpression.isEmpty() || unprocessed.isEmpty()) {
                    return segments
                }
            }
        }
    }

    data class RightPipeline(override val pipeline: ElmBinOpExpr) : Pipeline() {
        val isNotFullyPiped: Boolean
            get() =
                when (val firstPart = pipeline.parts.firstOrNull()) {
                    is ElmFunctionCallExpr -> firstPart.arguments.count() > 0
                    else -> false
                }

        override fun segments(): List<Segment> {
            val segments = mutableListOf<Segment>()
            val iter = pipeline.partsWithComments.iterator()

            var pendingComments: List<PsiComment> = emptyList()

            fun isPipeOp(p: PsiElement) =
                p is ElmOperator && p.referenceName == "|>"

            while (iter.hasNext()) {
                val buffer = mutableListOf<PsiElement>()

                // collect until "|>" or end
                while (iter.hasNext()) {
                    val next = iter.next()
                    if (isPipeOp(next)) break
                    buffer += next
                }

                val sliceComments = buffer.filterIsInstance<PsiComment>()
                val sliceTags = buffer.filterIsInstance<ElmBinOpPartTag>()

                // If empty or we're at end, carry comments forward
                if (buffer.isEmpty() || !iter.hasNext()) {
                    pendingComments = pendingComments + sliceComments
                }

                segments += Segment(sliceTags, pendingComments)

                // Prepare comments for the next iteration
                pendingComments = sliceComments

                if (buffer.isEmpty() || !iter.hasNext()) break
            }

            return segments
        }

    }

}
