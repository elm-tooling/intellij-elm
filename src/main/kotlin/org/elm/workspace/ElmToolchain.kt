package org.elm.workspace

import com.intellij.openapi.project.Project
import org.elm.openapiext.Result
import org.elm.workspace.commandLineTools.ElmCLI
import org.elm.workspace.commandLineTools.ElmFormatCLI
import org.elm.workspace.commandLineTools.ElmReviewCLI
import org.elm.workspace.commandLineTools.ElmTestCLI
import org.elm.workspace.commandLineTools.LamderaCLI
import org.elm.workspace.commandLineTools.WrapCLI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

const val elmCompilerTool = "elm"
const val lamderaCompilerTool = "lamdera"
const val elmWrapCompilerTool = "wrap"
const val elmFormatTool = "elm-format"
const val elmTestTool = "elm-test"
const val elmReviewTool = "elm-review"
val elmTools = listOf(elmCompilerTool, lamderaCompilerTool, elmWrapCompilerTool, elmFormatTool, elmTestTool, elmReviewTool)

enum class ElmCompilerType(val displayName: String, val toolName: String) {
    ELM("Elm", elmCompilerTool),
    LAMDERA("Lamdera", lamderaCompilerTool),
    ELM_WRAP("Elm Wrap", elmWrapCompilerTool);

    override fun toString(): String = displayName

    companion object {
        fun fromRaw(value: String?): ElmCompilerType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ELM
    }
}

data class ElmToolchain(
        val compilerPath: Path?,
        val compilerType: ElmCompilerType = DEFAULT_COMPILER_TYPE,
        val elmFormatPath: Path?,
        val elmTestPath: Path?,
        val elmReviewPath: Path?,
        val isElmFormatOnSaveEnabled: Boolean,
        val isElmReviewOnTheFlyEnabled: Boolean = DEFAULT_REVIEW_ON_THE_FLY,
        val isElmBuildOnSaveEnabled: Boolean = DEFAULT_BUILD_ON_SAVE
) {
    constructor(
        compilerPath: String,
        compilerType: ElmCompilerType = DEFAULT_COMPILER_TYPE,
        elmFormatPath: String,
        elmTestPath: String,
        elmReviewPath: String,
        isElmFormatOnSaveEnabled: Boolean,
        isElmReviewOnTheFlyEnabled: Boolean = DEFAULT_REVIEW_ON_THE_FLY,
        isElmBuildOnSaveEnabled: Boolean = DEFAULT_BUILD_ON_SAVE
    ) :
            this(
                    if (compilerPath.isNotBlank() && Files.exists(Paths.get(compilerPath))) Paths.get(compilerPath) else null,
                    compilerType,
                    if (elmFormatPath.isNotBlank() && Files.exists(Paths.get(elmFormatPath))) Paths.get(elmFormatPath) else null,
                    if (elmTestPath.isNotBlank() && Files.exists(Paths.get(elmTestPath))) Paths.get(elmTestPath) else null,
                    if (elmReviewPath.isNotBlank() && Files.exists(Paths.get(elmReviewPath))) Paths.get(elmReviewPath) else null,
                    isElmFormatOnSaveEnabled,
                    isElmReviewOnTheFlyEnabled,
                    isElmBuildOnSaveEnabled
            )

    val elmCompilerPath: Path? get() = compilerPath

    val elmCLI: ElmCLI? = if (compilerType == ElmCompilerType.ELM) compilerPath?.let { ElmCLI(it) } else null

    val lamderaCLI: LamderaCLI? = if (compilerType == ElmCompilerType.LAMDERA) compilerPath?.let { LamderaCLI(it) } else null
    val wrapCLI: WrapCLI? = if (compilerType == ElmCompilerType.ELM_WRAP) compilerPath?.let { WrapCLI(it) } else null

    val elmFormatCLI: ElmFormatCLI? = elmFormatPath?.let { ElmFormatCLI(it) }

    val elmTestCLI: ElmTestCLI? = elmTestPath?.let { ElmTestCLI(it) }

    val elmReviewCLI: ElmReviewCLI? = elmReviewPath?.let { ElmReviewCLI(it) }

    fun queryCompilerVersion(project: Project): Result<Version> =
        when (compilerType) {
            ElmCompilerType.ELM -> elmCLI?.queryVersion(project) ?: Result.Err("Elm compiler is not configured")
            ElmCompilerType.LAMDERA -> lamderaCLI?.queryVersion(project) ?: Result.Err("Lamdera compiler is not configured")
            ElmCompilerType.ELM_WRAP -> wrapCLI?.queryVersion(project) ?: Result.Err("Elm Wrap compiler is not configured")
        }

    /**
     * Checks the currently configured elm compiler path. If a bare `elm` command is provided we check that it is on the
     * path.
     * This performs file I/O.
     */
    fun looksLikeValidToolchain(overridePathSearch: Sequence<Path> = emptySequence()): Boolean {
        val configuredPath = compilerPath
        val bareCommand = when (compilerType) {
            ElmCompilerType.ELM -> elmCompilerTool
            ElmCompilerType.LAMDERA -> lamderaCompilerTool
            ElmCompilerType.ELM_WRAP -> elmWrapCompilerTool
        }
        return if (configuredPath.toString() == bareCommand) {
            ElmSuggest.compilerIsOnPath(bareCommand, overridePathSearch)
        } else {
            configuredPath != null && Files.isExecutable(configuredPath)
        }
    }

    /**
     * Attempts to locate Elm tool paths for all tools which are un-configured.
     * Returns a copy of the receiver. Performs file I/O.
     */
    fun autoDiscoverAll(project: Project): ElmToolchain {
        val suggestions = ElmSuggest.suggestTools(project)
        return copy(
                compilerPath = compilerPath ?: suggestions[compilerType.toolName],
                elmFormatPath = elmFormatPath ?: suggestions[elmFormatTool],
                elmTestPath = elmTestPath ?: suggestions[elmTestTool],
                elmReviewPath = elmReviewPath ?: suggestions[elmReviewTool]
        )
    }

    companion object {
        const val ELM_JSON = "elm.json"

        /**
         * The name of the file that contains information specific to an Elm project, but which is _not_ in `elm.json`.
         * Here we put extra information which isn't in the normal `elm.json`, but which this plugin requires, such as
         * a custom path to the directory containing tests.
         *
         * The `elm.json` file is referred to elsewhere as the _manifest_. This `elm.intellij.json` file is referred to
         * as the _sidecar manifest_.
         */
        const val SIDECAR_FILENAME = "elm.intellij.json"

        val DEFAULT_COMPILER_TYPE: ElmCompilerType = ElmCompilerType.ELM
        const val DEFAULT_FORMAT_ON_SAVE = true
        const val DEFAULT_REVIEW_ON_THE_FLY = true
        const val DEFAULT_BUILD_ON_SAVE = false

        /**
         * A blank, default [ElmToolchain].
         */
        val BLANK = ElmToolchain(
                compilerPath = null,
                compilerType = DEFAULT_COMPILER_TYPE,
                elmFormatPath = null,
                elmTestPath = null,
                elmReviewPath = null,
                isElmFormatOnSaveEnabled = DEFAULT_FORMAT_ON_SAVE,
                isElmReviewOnTheFlyEnabled = DEFAULT_REVIEW_ON_THE_FLY,
                isElmBuildOnSaveEnabled = DEFAULT_BUILD_ON_SAVE
        )

        /**
         * Suggest a default toolchain based on common locations where Elm tools are frequently installed.
         * This performs file I/O.
         */
        fun suggest(project: Project): ElmToolchain =
                BLANK.autoDiscoverAll(project)
    }
}
