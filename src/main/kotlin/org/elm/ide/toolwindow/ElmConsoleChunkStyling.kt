package org.elm.ide.toolwindow

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap

internal object ElmConsoleChunkStyling {
    fun contentTypeFor(
        prefix: String,
        cache: ConcurrentHashMap<String, ConsoleViewContentType>,
        color: String?,
        bold: Boolean,
        underline: Boolean
    ): ConsoleViewContentType {
        val fg = parseElmColor(color)
        val key = listOf(fg.rgb, bold, underline).joinToString("|")
        return cache.computeIfAbsent(key) {
            val effectType = if (underline) EffectType.LINE_UNDERSCORE else null
            val attrs = TextAttributes(
                fg,
                null,
                if (effectType != null) fg else null,
                effectType,
                if (bold) Font.BOLD else Font.PLAIN
            )
            ConsoleViewContentType("${prefix}_$key", attrs)
        }
    }

    private fun parseElmColor(raw: String?): Color {
        if (raw.isNullOrBlank()) return UIUtil.getLabelForeground()
        val normalized = raw.trim()
        if (normalized.startsWith("#")) {
            return runCatching {
                val c = Color.decode(normalized)
                JBColor(c, c)
            }.getOrElse { UIUtil.getLabelForeground() }
        }

        return when (normalized.uppercase()) {
            "RED" -> fixedColor(0xFF, 0x59, 0x59)
            "YELLOW" -> fixedColor(0xFA, 0xCF, 0x5A)
            "GREEN" -> fixedColor(0x5A, 0xD6, 0x7D)
            "BLUE" -> fixedColor(0x6C, 0xA0, 0xFF)
            "MAGENTA", "PURPLE" -> fixedColor(0xC5, 0x7B, 0xFF)
            "CYAN" -> fixedColor(0x4F, 0x9D, 0xA6)
            "BLACK" -> JBColor.BLACK
            "WHITE" -> JBColor.WHITE
            "GRAY", "GREY" -> JBColor.GRAY
            else -> UIUtil.getLabelForeground()
        }
    }

    private fun fixedColor(r: Int, g: Int, b: Int): JBColor {
        val rgb = (r shl 16) or (g shl 8) or b
        return JBColor(rgb, rgb)
    }
}
