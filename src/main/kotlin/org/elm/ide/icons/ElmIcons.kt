package org.elm.ide.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object ElmIcons {

    /** Basic file icon, matching the rest of IntelliJ's file icons */
    val FILE = getIcon("elm-file-32x32.svg")

    /** Colorful Elm icon */
    val COLORFUL = getIcon("elm-colorful-original.svg")

    /** Gutter icon for values and types exposed by an Elm module */
    val EXPOSED_GUTTER = getIcon("elm-exposure-original.svg")

    val RECURSIVE_CALL = AllIcons.Gutter.RecursiveMethod

    // STRUCTURE VIEW ICONS

    val FUNCTION = getIcon("function-32x32.svg")
    val UNION_TYPE = getIcon("type-32x32.svg")
    val TYPE_ALIAS = getIcon("type-32x32.svg")

    private fun getIcon(path: String): Icon {
        return IconLoader.getIcon("/icons/$path", ElmIcons::class.java)
    }
}
