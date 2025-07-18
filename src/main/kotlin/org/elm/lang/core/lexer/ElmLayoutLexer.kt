package org.elm.lang.core.lexer

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.text.CharArrayCharSequence
import org.elm.lang.core.lexer.State.*
import org.elm.lang.core.psi.ELM_COMMENTS
import org.elm.lang.core.psi.ElmTypes.*
import java.util.*


/**
 * This is the main lexer. It wraps the underlying lexer generated by Flex
 * to synthesize special tokens based on whitespace-sensitive layout rules
 * in the Elm language. This makes it possible to write a traditional parser
 * using GrammarKit.
 *
 * @see ElmIncrementalLexer
 */
class ElmLayoutLexer(private val lexer: Lexer) : LexerBase() {

    private lateinit var tokens: ArrayList<Token>
    private var currentTokenIndex = 0
    private val currentToken: Token
        get() = tokens[currentTokenIndex]

    @Deprecated("")
    fun start(buffer: CharArray, startOffset: Int, endOffset: Int, initialState: Int) {
        start(CharArrayCharSequence(*buffer), startOffset, endOffset, initialState)
    }

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        require(startOffset == 0) { "does not support incremental lexing: startOffset must be 0" }
        require(initialState == 0) { "does not support incremental lexing: initialState must be 0" }

        // Start the incremental lexer
        lexer.start(buffer, startOffset, endOffset, initialState)

        tokens = doLayout(lexer)
        currentTokenIndex = 0
    }

    override fun getState() = lexer.state
    override fun getBufferSequence() = lexer.bufferSequence
    override fun getBufferEnd() = lexer.bufferEnd

    override fun getTokenType() = currentToken.elementType
    override fun getTokenStart() = currentToken.start
    override fun getTokenEnd() = currentToken.end

    override fun advance() {
        if (currentToken.isEOF)
            return

        currentTokenIndex++
    }
}

private fun slurpTokens(lexer: Lexer): MutableList<Token> {
    val tokens = ArrayList<Token>()
    var line = Line()
    var currentColumn = 0
    while (true) {
        val token = Token(lexer.tokenType, lexer.tokenStart, lexer.tokenEnd, currentColumn, line)
        tokens.add(token)

        if (line.columnWhereCodeStarts == null && token.isCode) {
            line.columnWhereCodeStarts = currentColumn
        }

        currentColumn += token.end - token.start

        if (token.isEOF) {
            break
        } else if (token.elementType == NEWLINE) {
            line = Line()
            currentColumn = 0
        }

        lexer.advance()
    }
    return tokens
}

private enum class State {
    /** The start state. Do not perform layout until we get to the first real line of code
     */
    START,

    /** Waiting for the first line of code inside a let/in or case/of in order to open a new section. */
    WAITING_FOR_SECTION_START,

    /**
     * Looking to emit virtual delimiters between declarations at the same indent level
     * and closing out sections when appropriate.
     */
    NORMAL
}


private fun doLayout(lexer: Lexer): ArrayList<Token> {
    val tokens = slurpTokens(lexer)

    // initial state
    var i = 0
    var state = START
    val indentStack = IndentStack()
    indentStack.push(0) // top-level is an implicit section

    while (true) {
        val token = tokens[i]

        when (state) {
            START -> {
                if (token.isCode && token.column == 0) {
                    state = NORMAL
                }
            }
            WAITING_FOR_SECTION_START -> {
                if (token.isCode && token.column > indentStack.peek()) {
                    tokens.add(i, virtualToken(VIRTUAL_OPEN_SECTION, tokens[i - 1]))
                    i++
                    state = NORMAL
                    indentStack.push(token.column)
                } else if (token.isFirstSignificantTokenOnLine() && token.column <= indentStack.peek()) {
                    // The Elm program is malformed: most likely because the new section is empty
                    // (the user is still editing the text) or they did not indent the section.
                    // The empty section case is a common workflow, so we must handle it by bailing
                    // out of section building and re-process the token in the 'NORMAL' state.
                    // If, instead, the problem is that the user did not indent the text,
                    // tough luck (although we may want to handle this better in the future).
                    state = NORMAL
                    i--
                }
            }
            NORMAL -> {
                if (SECTION_CREATING_KEYWORDS.contains(token.elementType)) {
                    state = WAITING_FOR_SECTION_START
                } else if (token.isFirstSignificantTokenOnLine()) {
                    var insertAt = i

                    // We want to insert virtual tokens immediately after the newline that follows
                    // the last code token. This is important so that:
                    //
                    //   (1) trailing spaces at the end of the declaration are part of the declaration
                    //   (2) top-level comments that follow the declaration are NOT part of the declaration
                    //
                    // Note that a virtual token has to appear after a whitespace token, since the real token
                    // is combined with the virtual token during parsing (their text ranges overlap).
                    loop@ for (k in (i - 1) downTo 1) {
                        if (tokens[k].isCode) {
                            for (m in (k + 1) until (i + 1)) {
                                if (tokens[m].elementType == NEWLINE) {
                                    insertAt = m + 1
                                    break@loop
                                }
                            }
                        }
                    }

                    val precedingToken = tokens[insertAt - 1]

                    while (token.column <= indentStack.peek()) {
                        if (token.column == indentStack.peek()) {
                            tokens.add(insertAt, virtualToken(VIRTUAL_END_DECL, precedingToken))
                            i++
                            break
                        } else if (token.column < indentStack.peek()) {
                            tokens.add(insertAt, virtualToken(VIRTUAL_END_SECTION, precedingToken))
                            i++
                            insertAt++
                            indentStack.pop()
                        }
                    }
                } else if (isSingleLineLetIn(i, tokens)) {
                    tokens.add(i, virtualToken(VIRTUAL_END_SECTION, tokens[i - 1]))
                    i++
                    indentStack.pop()
                }
            }
        }

        i++
        if (i >= tokens.size)
            break
    }

    return ArrayList(tokens)
}

private fun isSingleLineLetIn(index: Int, tokens: List<Token>): Boolean {
    /*
    Elm allows for a let/in expression on a single line:
    e.g. ```foo = let x = 0 in x + 1```
    I don't know why you would ever do this, but some people do:
    https://github.com/intellij-elm/intellij-elm/issues/20#issuecomment-374843581

    If we didn't have special handling for it, the `let` section wouldn't
    get closed-out until a subsequent line with less indent, which would be wrong.
    */

    val token = tokens[index]
    if (token.elementType != IN)
        return false

    val thisLine = token.line
    var i = index
    do {
        val t = tokens[i--]
        if (t.elementType == LET)
            return true
    } while (t.line == thisLine && i in 0 until tokens.size)

    return false
}

/**
 * In a well-formed program, there would be no way to underflow the indent stack,
 * but this lexer will be asked to lex malformed/partial Elm programs, so we need
 * to guard against trying to use the stack when it's empty.
 */
private class IndentStack : LinkedList<Int>() {
    override fun peek(): Int {
        return if (super.isEmpty()) -1 else super.peek()
    }

    override fun pop(): Int {
        return if (super.isEmpty()) -1 else super.pop()
    }
}

private fun virtualToken(elementType: IElementType, precedesToken: Token): Token {
    return Token(
            elementType = elementType,
            start = precedesToken.start,
            end = precedesToken.start, // yes, this is intentional
            column = precedesToken.column,
            line = precedesToken.line)
}

private val NON_CODE_TOKENS = TokenSet.orSet(TokenSet.create(TokenType.WHITE_SPACE, TAB, NEWLINE), ELM_COMMENTS)
private val SECTION_CREATING_KEYWORDS = TokenSet.create(LET, OF)

private class Line(var columnWhereCodeStarts: Int? = null)

private class Token(val elementType: IElementType?,
                    val start: Int,
                    val end: Int,
                    val column: Int,
                    val line: Line) {

    override fun toString() =
            "${elementType.toString()} ($start, $end)"

    val isEOF: Boolean
        get() = elementType == null

    val isCode: Boolean
        get() = !NON_CODE_TOKENS.contains(elementType) && !isEOF

    fun isFirstSignificantTokenOnLine() =
            isCode && column == line.columnWhereCodeStarts
}
