package org.elm.ide.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object ElmIcons {

    /** Basic file icon, matching the rest of IntelliJ's file icons */
    val FILE = getIcon("elm-file.png")

    /** Colorful Elm icon */
    val COLORFUL = getIcon("elm-colorful.png")

    /** Gutter icon for values and types exposed by an Elm module */
    val EXPOSED_GUTTER = getIcon("elm-exposure.png")

    val RECURSIVE_CALL = AllIcons.Gutter.RecursiveMethod

    // STRUCTURE VIEW ICONS

    val FUNCTION = getIcon("function.png")
    val UNION_TYPE = getIcon("type.png")
    val TYPE_ALIAS = getIcon("type.png")

    private fun getIcon(path: String): Icon {
        return IconLoader.getIcon("/icons/$path", ElmIcons::class.java)
    }
}
