package org.elm.workspace.elmreview

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange

data class ElmReviewError(
    var suppressed: Boolean? = null,
    var path: String? = null,
    var rule: String? = null,
    var message: String? = null,
    var region: Region? = null,
    var formattedText: String? = null,
    var formattedChunks: List<Chunk>? = null
) {
    var ruleLink: String? = null
    var details: List<String>? = null
    var fix: List<Fix>? = null
    var origin: ElmReviewErrorOrigin = ElmReviewErrorOrigin.GENERIC
}

enum class ElmReviewErrorOrigin {
    REVIEW,
    COMPILER,
    GENERIC
}

data class Region(
    var start: Location? = null,
    var end: Location? = null
) {
    fun toTextRange(document: Document): TextRange? {
        val startOffset = toOffset(document, start?.line ?: return null, start?.column ?: return null)
        val endOffset = toOffset(document, end?.line ?: return null, end?.column ?: return null)
        return if (startOffset != null && endOffset != null && startOffset <= endOffset) {
            TextRange(startOffset, endOffset)
        } else {
            null
        }
    }

    companion object {
        fun toOffset(document: Document, line: Int, column: Int): Int? {
            val line0 = line - 1
            val column0 = column - 1
            if (line0 < 0 || line0 >= document.lineCount || column0 < 0) return null
            val offset = document.getLineStartOffset(line0) + column0
            return offset.takeIf { it <= document.textLength }
        }
    }
}

data class Location(
    var line: Int = 0,
    var column: Int = 0
)

data class Fix(
    var range: Region = Region(),
    var string: String = ""
)

sealed class Chunk {
    data class Unstyled(var str: String) : Chunk()
    @Suppress("unused")
    data class Styled(var string: String? = null, var bold: Boolean? = null, var underline: Boolean? = null, var color: String? = null, var href: String? = null) : Chunk()
}

enum class ReviewOutputType(val label: String) {
    ERROR("error"), COMPILE_ERRORS("compile-errors"), REVIEW_ERRORS("review-errors");

    companion object {
        fun fromLabel(label: String) =
            when (label) {
                ERROR.label -> ERROR
                COMPILE_ERRORS.label -> COMPILE_ERRORS
                REVIEW_ERRORS.label -> REVIEW_ERRORS
                else -> null
            }
    }
}

fun JsonReader.readProperties(propertyHandler: (String) -> Unit) {
    beginObject()
    while (hasNext()) {
        propertyHandler(nextName())
    }
    endObject()
}

fun JsonReader.readErrorReport(): List<ElmReviewError> {
    val errors = mutableListOf<ElmReviewError>()
    if (this.peek() == JsonToken.END_DOCUMENT) return emptyList()

    var type: ReviewOutputType? = null
    val topLevelError = ElmReviewError()
    var hasTopLevelErrorData = false
    readProperties { property ->
        when (property) {
            "type" -> {
                type = ReviewOutputType.fromLabel(nextString())
            }
            "errors" -> {
                when (type) {
                    ReviewOutputType.REVIEW_ERRORS -> {
                        beginArray()
                        while (hasNext()) {
                            var currentPath: String? = null
                            readProperties { outerProperty ->
                                when (outerProperty) {
                                    // `path` is null for global errors that aren't tied to a source file.
                                    "path" -> currentPath = readNullableString()
                                    "errors" -> {
                                        beginArray()
                                        while (hasNext()) {
                                            val elmReviewError = ElmReviewError(path = currentPath).apply {
                                                origin = ElmReviewErrorOrigin.REVIEW
                                            }
                                            readProperties { innerProperty ->
                                                when (innerProperty) {
                                                    "suppressed" -> elmReviewError.suppressed = nextBoolean()
                                                    "rule" -> elmReviewError.rule = nextString()
                                                    "message" -> elmReviewError.message = nextString()
                                                    "ruleLink" -> elmReviewError.ruleLink = nextString()
                                                    "details" -> elmReviewError.details = readStringArray()
                                                    "region" -> {
                                                        elmReviewError.region = readRegion()
                                                    }
                                                    "fix" -> elmReviewError.fix = readFixList()
                                                    "formatted" -> {
                                                        val chunkList = readChunkList()
                                                        elmReviewError.formattedChunks = chunkList
                                                        elmReviewError.formattedText = chunksToLines(chunkList).joinToString("\n")
                                                    }
                                                    else -> {
                                                        // "originallySuppressed"
                                                        skipValue()
                                                    }
                                                }
                                            }
                                            errors.add(elmReviewError)
                                        }
                                        endArray()
                                    }
                                }
                            }
                        }
                        endArray()
                    }
                    ReviewOutputType.COMPILE_ERRORS -> {
                        beginArray()
                        while (hasNext()) {
                            var currentPath: String? = null
                            readProperties { outerProperty ->
                                when (outerProperty) {
                                    "path" -> currentPath = readNullableString()
                                    "name" -> skipValue()
                                    "problems" -> {
                                        beginArray()
                                        while (hasNext()) {
                                            val elmReviewError = ElmReviewError(path = currentPath).apply {
                                                origin = ElmReviewErrorOrigin.COMPILER
                                            }
                                            readProperties { innerProperty ->
                                                when (innerProperty) {
                                                    "title" -> elmReviewError.rule = nextString()
                                                    "region" -> elmReviewError.region = readRegion()
                                                    "message" -> {
                                                        when (peek()) {
                                                            JsonToken.BEGIN_ARRAY -> {
                                                                val chunkList = readChunkList()
                                                                val text = chunksToLines(chunkList).joinToString("\n")
                                                                elmReviewError.message = text
                                                                elmReviewError.formattedChunks = chunkList
                                                                elmReviewError.formattedText = text
                                                            }
                                                            JsonToken.STRING -> {
                                                                val text = nextString()
                                                                elmReviewError.message = text
                                                                elmReviewError.formattedText = text
                                                            }
                                                            else -> skipValue()
                                                        }
                                                    }
                                                    else -> {
                                                        skipValue()
                                                    }
                                                }
                                            }
                                            errors.add(elmReviewError)
                                        }
                                        endArray()
                                    }
                                }
                            }
                        }
                        endArray()
                    }
                    ReviewOutputType.ERROR -> throw RuntimeException("Unexpected json-type 'error' with 'errors' array")
                    null -> skipValue()
                }
            }
            "title" -> {
                topLevelError.rule = nextString()
                hasTopLevelErrorData = true
            }
            "path" -> {
                topLevelError.path = nextString()
                hasTopLevelErrorData = true
            }
            "message" -> {
                topLevelError.message = when (peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        val chunkList = readChunkList()
                        chunksToLines(chunkList).joinToString("\n")
                    }
                    JsonToken.STRING -> nextString()
                    else -> {
                        skipValue()
                        null
                    }
                }
                hasTopLevelErrorData = true
            }
            else -> {
                // Ignore top-level metadata fields such as `cliVersion`.
                skipValue()
            }
        }
    }
    if (hasTopLevelErrorData) {
        topLevelError.origin = when (type) {
            ReviewOutputType.REVIEW_ERRORS -> ElmReviewErrorOrigin.REVIEW
            ReviewOutputType.COMPILE_ERRORS -> ElmReviewErrorOrigin.COMPILER
            else -> ElmReviewErrorOrigin.GENERIC
        }
        errors.add(topLevelError)
    }
    return errors
}

private fun JsonReader.readStringArray(): List<String> {
    val values = mutableListOf<String>()
    beginArray()
    while (hasNext()) {
        values.add(nextString())
    }
    endArray()
    return values
}

private fun JsonReader.readFixList(): List<Fix> {
    val fixes = mutableListOf<Fix>()
    beginArray()
    while (hasNext()) {
        val fix = Fix()
        readProperties { property ->
            when (property) {
                "range" -> fix.range = readRegion()
                "string" -> fix.string = nextString()
                else -> skipValue()
            }
        }
        fixes.add(fix)
    }
    endArray()
    return fixes
}

private fun JsonReader.readChunkList(): List<Chunk> {
    val chunkList = mutableListOf<Chunk>()
    beginArray()
    while (hasNext()) {
        if (this.peek() == JsonToken.BEGIN_OBJECT) {
            val chunkStyled = Chunk.Styled()
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "string" -> chunkStyled.string = readNullableString()
                    "color" -> chunkStyled.color = readNullableString()
                    "href" -> chunkStyled.href = readNullableString()
                    "bold" -> chunkStyled.bold = readNullableBoolean()
                    "underline" -> chunkStyled.underline = readNullableBoolean()
                }
            }
            endObject()
            chunkList.add(chunkStyled)
        } else {
            chunkList.add(Chunk.Unstyled(nextString()))
        }
    }
    endArray()
    return chunkList
}

private fun JsonReader.readNullableString(): String? =
    if (peek() == JsonToken.NULL) {
        nextNull()
        null
    } else {
        nextString()
    }

private fun JsonReader.readNullableBoolean(): Boolean? =
    if (peek() == JsonToken.NULL) {
        nextNull()
        null
    } else {
        nextBoolean()
    }

fun JsonReader.readRegion(): Region {
    val region = Region()
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "start" -> {
                val location = readLocation()
                region.start = location
            }
            "end" -> {
                val location = readLocation()
                region.end = location
            }
        }
    }
    endObject()
    return region
}

fun JsonReader.readLocation(): Location {
    val location = Location()
    beginObject()
    while (hasNext()) {
        when (val prop = nextName()) {
            "line" -> location.line = nextInt()
            "column" -> location.column = nextInt()
            else -> skipValue()
        }
    }
    endObject()
    return location
}

private fun chunksToLines(chunks: List<Chunk>): List<String> {
    return chunks.joinToString("") {
        when (it) {
            is Chunk.Unstyled -> it.str
            is Chunk.Styled -> it.string.orEmpty()
        }
    }.lines()
}
