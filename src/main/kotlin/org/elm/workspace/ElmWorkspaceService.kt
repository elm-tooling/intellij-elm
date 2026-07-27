/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * Originally from intellij-rust
 */

package org.elm.workspace

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.annotations.VisibleForTesting
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo.TOTAL_RESCAN
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.io.systemIndependentPath
import com.intellij.util.messages.Topic
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import org.elm.lang.core.psi.modificationTracker
import org.elm.openapiext.isUnitTestMode
import org.elm.openapiext.*
import org.elm.utils.MyDirectoryIndex
import org.elm.utils.joinAll
import org.elm.utils.runAsyncTask
import org.elm.workspace.ElmToolchain.Companion.DEFAULT_BUILD_ON_SAVE
import org.elm.workspace.ElmToolchain.Companion.DEFAULT_COMPILER_TYPE
import org.elm.workspace.ElmToolchain.Companion.DEFAULT_FORMAT_ON_SAVE
import org.elm.workspace.ElmToolchain.Companion.DEFAULT_REVIEW_ON_THE_FLY
import org.elm.workspace.ElmToolchain.Companion.ELM_JSON
import org.elm.workspace.compiler.*
import org.elm.workspace.ui.ElmWorkspaceConfigurable
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists


private val log = logger<ElmWorkspaceService>()

/**
 * The workspace can hold multiple [ElmProject]s. Most of the time there will only be one
 * [ElmProject], but you might have multiple if you are working on multiple Elm apps in
 * the same IntelliJ project.
 *
 * There is only one workspace for the entire IntelliJ [Project].
 *
 * The state includes user-specific paths, so it is persisted to IntelliJ's workspace file
 * (which is _not_ placed in version control).
 */
@Service(Service.Level.PROJECT)
@State(name = "ElmWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ElmWorkspaceService(private val intellijProject: Project) : PersistentStateComponent<Element>, Disposable {

    private val refreshQueue = MergingUpdateQueue(
        "ElmWorkspaceRefreshQueue",
        300,
        true,
        MergingUpdateQueue.ANY_COMPONENT,
        this,
        null,
        false
    )

    init {
        if (!isUnitTestMode) {
            with(intellijProject.messageBus.connect(this@ElmWorkspaceService)) {
                subscribe(VirtualFileManager.VFS_CHANGES, ElmProjectWatcher {
                    refreshQueue.queue(object : Update("refresh-all-projects") {
                        override fun run() {
                            asyncRefreshAllProjects().exceptionally {
                                logRefreshFailure("Could not refresh Elm projects after VFS change", it)
                                emptyList()
                            }
                        }
                    })
                })
            }
        }
    }

    /**
     * Increments whenever the workspace and/or settings change.
     *
     * This is provided as an alternative to listening to the message bus for changes.
     * You should use either one or the other, depending on your situation.
     */
    val changeTracker = SimpleModificationTracker()


    // SETTINGS AND TOOLCHAIN

    /** A nice view of the settings to the outside world */
    data class Settings(val toolchain: ElmToolchain)

    /** Representation of settings suitable for editor UI and serialization */
    data class RawSettings(
        val elmCompilerPath: String = "",
        val compilerType: ElmCompilerType = DEFAULT_COMPILER_TYPE,
        val elmFormatPath: String = "",
        val elmTestPath: String = "",
        val elmReviewPath: String = "",
        val elmReviewConfigPath: String = "",
        val isElmFormatOnSaveEnabled: Boolean = DEFAULT_FORMAT_ON_SAVE,
        val isElmReviewOnTheFlyEnabled: Boolean = DEFAULT_REVIEW_ON_THE_FLY,
        val isElmBuildOnSaveEnabled: Boolean = DEFAULT_BUILD_ON_SAVE,
        val buildTargets: List<ElmBuildTargetConfig> = emptyList()
    )

    data class BuildTargetSelectionRequest(
        val targetName: String,
        val targetInputPath: String
    )


    val settings: Settings
        get() {
            val raw = rawSettingsRef.get()
            val toolchain = ElmToolchain(
                compilerPath = raw.elmCompilerPath,
                compilerType = raw.compilerType,
                elmFormatPath = raw.elmFormatPath,
                elmTestPath = raw.elmTestPath,
                elmReviewPath = raw.elmReviewPath,
                isElmFormatOnSaveEnabled = raw.isElmFormatOnSaveEnabled,
                isElmReviewOnTheFlyEnabled = raw.isElmReviewOnTheFlyEnabled,
                isElmBuildOnSaveEnabled = raw.isElmBuildOnSaveEnabled
            )
            return Settings(toolchain = toolchain)
        }


    val rawSettings: RawSettings? get() = rawSettingsRef.get()


    private val rawSettingsRef = AtomicReference(RawSettings())
    @Volatile
    private var pendingBuildTargetSelection: BuildTargetSelectionRequest? = null

    /** The flat list of configured build targets. Each target names an absolute input file; the
     * owning Elm project (elm.json) is derived from that file at resolution time. */
    val buildTargets: List<ElmBuildTargetConfig>
        get() = rawSettingsRef.get().buildTargets

    fun setBuildTargets(targets: List<ElmBuildTargetConfig>) {
        modifySettings { it.copy(buildTargets = targets) }
    }

    /**
     * Resolve every configured build target independently, returning one outcome per target
     * (either a runnable [ResolvedBuildTarget] plus its owning Elm project, or an error message).
     * This does not collapse to a single error when one target is misconfigured, so the tool
     * window can list valid and invalid targets side by side and surface the reason a target
     * failed instead of silently dropping it.
     *
     * The owning Elm project is derived from the input file via [findProjectForFile] — the same
     * nearest-elm.json logic the rest of the IDE uses — rather than being chosen by the user.
     */
    fun resolveBuildTargetsDetailed(): List<BuildTargetOutcome> {
        val targets = buildTargets
        return runReadAction {
            targets.mapIndexed { index, target ->
                resolveBuildTarget(target, index + 1)
            }
        }
    }

    /** Must be called inside a read action (uses the VFS and the project directory index). */
    private fun resolveBuildTarget(target: ElmBuildTargetConfig, row: Int): BuildTargetOutcome {
        fun invalid(message: String) =
            BuildTargetOutcome(row, target, resolved = null, error = message)

        fun validCompilerPathOrNull(): Path? {
            val raw = target.compilerPath.trim()
            val path = raw.toPathOrNull()
            return if (raw.isBlank() || path == null || !Files.isExecutable(path)) null else path
        }

        return when (target.type) {
            ElmBuildTargetType.APPLICATION -> {
                val inputRaw = target.inputPath.trim()
                if (inputRaw.isBlank()) {
                    return invalid("Row $row: input file is not set")
                }
                val inputPath = inputRaw.toPathOrNull()
                if (inputPath == null || !inputPath.isAbsolute) {
                    return invalid("Row $row: input path must be an absolute file path")
                }
                if (inputPath.fileName?.toString()?.endsWith(".elm") != true) {
                    return invalid("Row $row: input file '$inputRaw' is not an Elm file")
                }
                val inputFile = findFileByPathTestAware(inputPath)
                if (inputFile == null || !inputFile.exists()) {
                    return invalid("Row $row: input file '$inputRaw' does not exist")
                }
                val elmProject = findProjectForFile(inputFile)
                    ?: return invalid("Row $row: no Elm project (elm.json) found for '$inputRaw' — open the file and attach an elm.json")

                val compilerPath = validCompilerPathOrNull()
                    ?: return invalid("Row $row: compiler path '${target.compilerPath.trim()}' is invalid or not executable")

                val outputRaw = target.outputPath.trim()
                val outputForCompiler = if (outputRaw.isBlank()) {
                    nullOutputTargetPathString()
                } else {
                    val outputPath = outputRaw.toPathOrNull()
                    if (outputPath == null || !outputPath.isAbsolute) {
                        return invalid("Row $row: output path must be an absolute file path (or blank)")
                    }
                    outputRaw
                }

                BuildTargetOutcome(
                    row = row,
                    config = target,
                    resolved = ResolvedBuildTarget(
                        name = target.name,
                        type = ElmBuildTargetType.APPLICATION,
                        workDir = elmProject.projectDirPath,
                        inputPath = inputPath,
                        inputPathForCompiler = inputRaw,
                        outputPathForCompiler = outputForCompiler,
                        mode = target.mode,
                        compilerKind = target.compilerKind,
                        compilerPath = compilerPath,
                        compileOnSave = target.compileOnSave,
                        offset = 0
                    ),
                    error = null
                )
            }

            ElmBuildTargetType.PACKAGE -> {
                val manifestRaw = target.inputPath.trim()
                if (manifestRaw.isBlank()) {
                    return invalid("Row $row: elm.json file is not set")
                }
                val manifestPath = manifestRaw.toPathOrNull()
                if (manifestPath == null || !manifestPath.isAbsolute) {
                    return invalid("Row $row: elm.json path must be an absolute file path")
                }
                if (manifestPath.fileName?.toString() != ELM_JSON) {
                    return invalid("Row $row: '$manifestRaw' is not an elm.json file")
                }
                val manifestFile = findFileByPathTestAware(manifestPath)
                if (manifestFile == null || !manifestFile.exists()) {
                    return invalid("Row $row: elm.json file '$manifestRaw' does not exist")
                }
                // We only need the elm.json's directory as the working directory; the package
                // does not have to be an attached project. If it happens to be one, keep it for
                // display purposes.
                val workDir = manifestPath.parent.normalize()

                val compilerPath = validCompilerPathOrNull()
                    ?: return invalid("Row $row: compiler path '${target.compilerPath.trim()}' is invalid or not executable")

                BuildTargetOutcome(
                    row = row,
                    config = target,
                    resolved = ResolvedBuildTarget(
                        name = target.name,
                        type = ElmBuildTargetType.PACKAGE,
                        workDir = workDir,
                        inputPath = manifestPath,
                        inputPathForCompiler = "",
                        outputPathForCompiler = "",
                        mode = ElmBuildMode.NONE,
                        compilerKind = target.compilerKind,
                        compilerPath = compilerPath,
                        compileOnSave = target.compileOnSave,
                        offset = 0
                    ),
                    error = null
                )
            }
        }
    }


    /**
     * The core, internal function updating the workspace settings. All updates must ultimately
     * go through here to make sure that the appropriate notifications are triggered.
     */
    fun modifySettings(notify: Boolean = true, f: (RawSettings) -> RawSettings): RawSettings {
        return rawSettingsRef.getAndUpdate(f)
            .also { if (notify) notifyDidChangeWorkspace(projectSetChanged = false) }
    }


    fun useToolchain(toolchain: ElmToolchain) {
        modifySettings {
            it.copy(
                elmCompilerPath = toolchain.compilerPath.toString(),
                compilerType = toolchain.compilerType,
                elmFormatPath = toolchain.elmFormatPath.toString(),
                elmTestPath = toolchain.elmTestPath.toString(),
                elmReviewPath = toolchain.elmReviewPath.toString(),
                isElmFormatOnSaveEnabled = toolchain.isElmFormatOnSaveEnabled,
                isElmReviewOnTheFlyEnabled = toolchain.isElmReviewOnTheFlyEnabled,
                isElmBuildOnSaveEnabled = toolchain.isElmBuildOnSaveEnabled
            )
        }
    }


    fun showConfigureToolchainUI() {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(intellijProject, ElmWorkspaceConfigurable::class.java)
    }

    fun showConfigureBuildTargetUI(targetName: String, targetInputPath: String) {
        pendingBuildTargetSelection = BuildTargetSelectionRequest(
            targetName = targetName,
            targetInputPath = targetInputPath
        )
        showConfigureToolchainUI()
    }

    fun consumePendingBuildTargetSelection(): BuildTargetSelectionRequest? {
        val pending = pendingBuildTargetSelection
        pendingBuildTargetSelection = null
        return pending
    }


    // ELM PROJECTS


    /**
     * The INTERNAL list of Elm projects in the workspace. Project truth lives here.
     *
     * IMPORTANT: must ensure thread-safe access and that the ElmProject objects themselves are immutable values.
     */
    private val projectsRef = AtomicReference(emptyList<ElmProject>())


    /**
     * The list of Elm projects in the workspace, suitable for use by the rest of the plugin.
     */
    val allProjects: List<ElmProject>
        get() = projectsRef.get()


    /**
     * The core, internal function for updating the list of Elm projects in the workspace.
     * All updates must ultimately go through here to make sure that dependent data-structures
     * are updated and notifications are triggered.
     */
    private fun modifyProjects(f: (List<ElmProject>) -> List<ElmProject>): List<ElmProject> {
        projectsRef.getAndUpdate(f)
        log.info("Resetting the directoryIndex for project lookup")
        directoryIndex.resetIndex()
        notifyDidChangeWorkspace(projectSetChanged = true)
        return allProjects
    }


    /**
     * Add [elmProject] to the workspace. If the project has already been registered, it will be replaced
     * with the newer one.
     */
    private fun upsertProject(elmProject: ElmProject): List<ElmProject> =
        modifyProjects { projects ->
            val otherProjects = projects.filter { it.manifestPath != elmProject.manifestPath }
            otherProjects + elmProject
        }


    /**
     * Asynchronously load an Elm project described by a manifest file (e.g. `elm.json`).
     */
    private fun asyncLoadProject(
        manifestPath: Path,
        installDeps: Boolean = false,
        compilerVersion: Version? = null
    ): CompletableFuture<ElmProject> =
        runAsyncTask(intellijProject, "Loading Elm project '$manifestPath'") {
            val elmCompilerVersion = compilerVersion
                ?: resolveCompilerVersionForProjectLoad()

            if (installDeps) {
                installProjectDeps(manifestPath, elmCompilerVersion)
            }

            // not thread-safe; do not reuse across threads!
            // TODO lamderaCompilerVersion
            val repo = ElmPackageRepository(elmCompilerVersion)

            // External files may have been created/modified by the Elm compiler. Refresh.
            findFileByPathTestAware(Paths.get(repo.elmHomePath))?.also {
                fullyRefreshDirectory(it)
            }

            // Load the project
            ElmProjectLoader.topLevelLoad(manifestPath, repo)
        }.whenComplete { _, error ->
            // log the result
            if (error == null) {
                log.info("Successfully loaded Elm project $manifestPath")
            } else {
                when (error) {
                    is ProjectLoadException -> log.warn("Failed to load $manifestPath: ${error.message}")
                    else -> log.error("Unexpected error when loading $manifestPath", error)
                }
            }
        }

    private fun installProjectDeps(manifestPath: Path, compilerVersion: Version): Boolean {
        // The only way to install an Elm project's dependencies is to compile
        // the project. But the project may not be in a compilable state when
        // we try to load it. So we will copy the `elm.json` into a temp dir
        // and run the compiler there.

        // Create temp dir to hold everything
        val dir = FileUtil.createTempDirectory("elm_deps_hack", null)

        // Re-write the `elm.json` with sane source-directories
        val mapper = ObjectMapper()
        val dto = mapper.readTree(manifestPath.toFile()) as ObjectNode
        if (dto.has("source-directories")) {
            dto.putArray("source-directories").add("src")
        }

        // An application's `elm.json` pins an exact `elm-version`, and the Elm compiler refuses
        // to build (and therefore to download dependencies) unless it matches the compiler exactly.
        // Since this is a throwaway copy used only to trigger the download, rewrite it to the
        // compiler's own version so deps land in `~/.elm/<compilerVersion>/packages/` regardless of
        // which 0.19.x the user has installed. (Packages declare a version *range*, so they never
        // hit this mismatch and are left untouched. Lamdera has its own versioning scheme.)
        if (settings.toolchain.compilerType == ElmCompilerType.ELM && dto.get("type")?.textValue() == "application") {
            dto.put("elm-version", compilerVersion.toString())
        }
        val tempManifest = dir.toPath().resolve(ELM_JSON).toFile()
        mapper.writeValue(tempManifest, dto)

        // Synthesize a dummy Elm file to make the compiler happy
        val tempMain = dir.toPath().resolve("src/Main.elm").toFile()
        FileUtil.writeToFile(
            tempMain, """
                                module Main exposing (..)
                                dummyValue = 0
                            """.trimIndent()
        )

        // Before we run the Elm compiler, make sure that the `elm.json` file is valid
        // because some inputs can hang the compiler.
        if (!ElmProjectLoader.isValid(tempManifest.toPath())) {
            log.error("Failed to install deps: the elm.json file is invalid")
            FileUtil.delete(dir)
            return false
        }

        // Run the Elm compiler to install the dependencies
        val tmpEntryPoint = ResolvedBuildTarget(
            name = "Install dependencies",
            type = ElmBuildTargetType.APPLICATION,
            workDir = dir.toPath(),
            inputPath = tempMain.toPath(),
            inputPathForCompiler = tempMain.path,
            outputPathForCompiler = nullOutputTargetPathString(),
            mode = ElmBuildMode.NONE,
            compilerKind = ElmCompilerKind.ELM,
            compilerPath = settings.toolchain.compilerPath ?: Paths.get("elm"),
            compileOnSave = false,
            offset = 0
        )

        val success = when (settings.toolchain.compilerType) {
            ElmCompilerType.LAMDERA -> {
                val lamderaCLI = settings.toolchain.lamderaCLI
                    ?: throw ProjectLoadException("Must specify a valid path to Lamdera binary in Settings")
                lamderaCLI.make(intellijProject, workDir = dir.toPath(), null, listOf(tmpEntryPoint))
            }
            ElmCompilerType.ELM -> {
                val elmCLI = settings.toolchain.elmCLI
                    ?: throw ProjectLoadException("Must specify a valid path to Elm binary in Settings")
                elmCLI.make(intellijProject, workDir = dir.toPath(), null, listOf(tmpEntryPoint))
            }
            ElmCompilerType.ELM_WRAP -> {
                val wrapCLI = settings.toolchain.wrapCLI
                    ?: throw ProjectLoadException("Must specify a valid path to Elm Wrap binary in Settings")
                wrapCLI.make(intellijProject, workDir = dir.toPath(), null, listOf(tmpEntryPoint))
            }
        }

        // Cleanup
        FileUtil.delete(dir)

        return success
    }


    // WORKSPACE ACTIONS


    fun asyncAttachElmProject(manifestPath: Path): CompletableFuture<List<ElmProject>> =
        asyncLoadProject(manifestPath, installDeps = true)
            .thenApply {
                upsertProject(it)
            }


    fun detachElmProject(manifestPath: Path) {
        modifyProjects { oldProjects ->
            oldProjects.filter { it.manifestPath != manifestPath }
        }
    }


    fun asyncRefreshAllProjects(installDeps: Boolean = false): CompletableFuture<List<ElmProject>> =
        if (allProjects.isEmpty()) {
            CompletableFuture.completedFuture(
                modifyProjects { it }
            )
        } else
        runAsyncTask(intellijProject, "Preparing Elm project refresh") {
            settings.toolchain.queryCompilerVersion(intellijProject).orNull()
                ?: run {
                    log.warn("Could not determine version of the selected compiler while refreshing Elm projects. Falling back to 0.19.1.")
                    Version(0, 19, 1)
                }
        }.thenCompose { elmCompilerVersion ->
            allProjects.map { elmProject ->
                asyncLoadProject(
                    elmProject.manifestPath,
                    installDeps = installDeps,
                    compilerVersion = elmCompilerVersion
                ).thenApply { loadedProject ->
                    RefreshOutcome(elmProject.manifestPath, loadedProject, null)
                }.exceptionally { error ->
                    // TODO Communicate this error in the UI (while warnings may be fine for tests)
                    val root = unwrapCompletionError(error)
                    logRefreshFailure("Could not load elm project", root)
                    RefreshOutcome(elmProject.manifestPath, null, root)
                }
            }.joinAll()
                .thenApply { outcomes ->
                    val refreshOutcomeByManifest = outcomes.associateBy { it.manifestPath }
                    modifyProjects { currentProjects ->
                        currentProjects.mapNotNull { current ->
                            val outcome = refreshOutcomeByManifest[current.manifestPath] ?: return@mapNotNull current
                            when {
                                outcome.project != null -> outcome.project
                                isMissingManifestFailure(outcome.error) -> null
                                else -> current
                            }
                        }
                    }
                }
        }


    fun asyncDiscoverAndRefresh(): CompletableFuture<List<ElmProject>> {
        if (hasAtLeastOneValidProject())
            return CompletableFuture.completedFuture(allProjects)

        val guessManifest = intellijProject.modules
            .asSequence()
            .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
            .firstNotNullOfOrNull { dir -> dir.findFileBreadthFirst(maxDepth = 3) { it.name == ELM_JSON } }
            ?: return CompletableFuture.completedFuture(allProjects)

        return asyncAttachElmProject(guessManifest.pathAsPath).exceptionally {
            log.warn("Could not attach elm project ${it.message}")
            emptyList()
        }
    }


    fun hasAtLeastOneValidProject() =
        allProjects.any { it.manifestPath.exists() }

    private fun logRefreshFailure(context: String, error: Throwable) {
        val root = unwrapCompletionError(error)
        if (isUnitTestMode || isMissingManifestFailure(root)) {
            log.warn("$context: ${root.message ?: root::class.java.simpleName}")
        } else {
            log.warn(context, error)
        }
    }

    private fun unwrapCompletionError(error: Throwable): Throwable {
        val completionCause = (error as? CompletionException)?.cause
        return completionCause ?: error
    }

    private fun isMissingManifestFailure(error: Throwable?): Boolean {
        if (error !is ProjectLoadException) return false
        return error.message?.startsWith("Manifest file not found:") == true
    }

    private fun resolveCompilerVersionForProjectLoad(): Version {
        return settings.toolchain.queryCompilerVersion(intellijProject).orNull()
            ?: run {
                log.warn("Could not determine version of the selected compiler while loading Elm projects. Falling back to 0.19.1.")
                Version(0, 19, 1)
            }
    }

    private data class RefreshOutcome(
        val manifestPath: Path,
        val project: ElmProject?,
        val error: Throwable?
    )


    // PROJECT LOOKUP


    fun findProjectForFile(file: VirtualFile): ElmProject? =
        directoryIndex.getInfoForFile(file).takeIf { it !== noProjectSentinel }


    private val directoryIndex: MyDirectoryIndex<ElmProject> =
        MyDirectoryIndex(this, noProjectSentinel) { index ->
            fun put(path: Path?, elmProject: ElmProject) {
                if (path == null) return
                val file = findFileByPathTestAware(path) ?: return
                val existingElmProject = findProjectForFile(file)
                if (existingElmProject == null) {
                    index.putInfo(file, elmProject)
                } else {
                    /*
                    Conflict: There is already an Elm project associated with this directory.

                    Elm's source directories can be shared between Elm projects.
                    The "right" thing to do would be to model this fully, allowing an Elm file
                    to belong to multiple Elm projects. But that would complicate things everywhere,
                    and so I have chosen to instead keep things simple by associating each Elm file
                    with a SINGLE Elm project only.

                    The conflict will be resolved by always associating an Elm file with
                    the Elm project that is nearest in the file system hierarchy. This is by no
                    means perfect, but it should be good enough in nearly all cases.

                    In the future we may want to re-visit this decision.
                    */
                    val oldDistance = existingElmProject.projectDirPath.relativize(path.normalize()).toList().size
                    val newDistance = elmProject.projectDirPath.relativize(path.normalize()).toList().size
                    if (newDistance < oldDistance) {
                        log.debug("Resolved conflict by by re-associating $file with $elmProject")
                        index.putInfo(file, elmProject)
                    } else {
                        log.debug("Resolved conflict by keeping the existing association of $file with $existingElmProject")
                    }
                }
            }

            for (project in allProjects) {
                for (sourceDir in project.absoluteSourceDirectories) {
                    log.debug("Registering source directory $sourceDir for $project")
                    put(sourceDir, project)
                }
                for (pkg in project.deepDeps()) {
                    log.debug("Registering dependency directory ${pkg.projectDirPath} for $pkg")
                    put(pkg.projectDirPath, pkg)
                }

                log.debug("Registering tests directory ${project.testsDirPath} for $project")
                put(project.testsDirPath, project)
            }
        }

    override fun dispose() = Unit


    // INTEGRATION TEST SUPPORT


    /// Configures the workspace for the Elm project described by [manifestFile]
    fun setupForTests(toolchain: ElmToolchain, manifestFile: VirtualFile) {
        useToolchain(toolchain)
        asyncLoadProject(manifestFile.pathAsPath)
            .get(5, TimeUnit.SECONDS)
            .run { upsertProject(this) }
    }


    // PERSISTENT STATE


    override fun getState(): Element {
        val state = Element("state")

        val projectsElement = Element("elmProjects")
        state.addContent(projectsElement)
        for (project in allProjects) {
            val elem = Element("project").setAttribute("path", project.manifestPath.systemIndependentPath)
            projectsElement.addContent(elem)
        }

        val settingsElement = Element("settings")
        state.addContent(settingsElement)
        val raw = rawSettingsRef.get()
        settingsElement.setAttribute("elmCompilerPath", raw.elmCompilerPath)
        settingsElement.setAttribute("compilerType", raw.compilerType.name)
        settingsElement.setAttribute("elmFormatPath", raw.elmFormatPath)
        settingsElement.setAttribute("elmTestPath", raw.elmTestPath)
        settingsElement.setAttribute("elmReviewPath", raw.elmReviewPath)
        settingsElement.setAttribute("elmReviewConfigPath", raw.elmReviewConfigPath)
        settingsElement.setAttribute("isElmFormatOnSaveEnabled", raw.isElmFormatOnSaveEnabled.toString())
        settingsElement.setAttribute("isElmReviewOnTheFlyEnabled", raw.isElmReviewOnTheFlyEnabled.toString())
        settingsElement.setAttribute("isElmBuildOnSaveEnabled", raw.isElmBuildOnSaveEnabled.toString())
        if (raw.buildTargets.isNotEmpty()) {
            val buildTargetsElement = Element("buildTargets")
            state.addContent(buildTargetsElement)
            for (target in raw.buildTargets) {
                buildTargetsElement.addContent(
                    Element("target")
                        .setAttribute("name", target.name)
                        .setAttribute("type", target.type.name)
                        .setAttribute("inputPath", target.inputPath)
                        .setAttribute("outputPath", target.outputPath)
                        .setAttribute("mode", target.mode.name)
                        .setAttribute("compilerKind", target.compilerKind.name)
                        .setAttribute("compilerPath", target.compilerPath)
                        .setAttribute("compileOnSave", target.compileOnSave.toString())
                )
            }
        }

        return state
    }

    override fun loadState(state: Element) {
        asyncLoadState(state)
    }

    @VisibleForTesting
    fun asyncLoadState(state: Element): CompletableFuture<Unit> {
        // Must load the Settings before the Elm Projects in order to have an ElmToolchain ready
        val settingsElement = state.getChild("settings")
        val elmCompilerPath = settingsElement.getAttributeValue("elmCompilerPath") ?: ""
        val lamderaCompilerPath = settingsElement.getAttributeValue("lamderaCompilerPath") ?: ""
        val compilerTypeFromState = ElmCompilerType.fromRaw(settingsElement.getAttributeValue("compilerType"))
        val compilerType = when {
            settingsElement.getAttributeValue("compilerType") != null -> compilerTypeFromState
            lamderaCompilerPath.isNotBlank() -> ElmCompilerType.LAMDERA
            else -> DEFAULT_COMPILER_TYPE
        }
        val compilerPath = when (compilerType) {
            ElmCompilerType.ELM -> elmCompilerPath
            ElmCompilerType.LAMDERA -> lamderaCompilerPath.ifBlank { elmCompilerPath }
            ElmCompilerType.ELM_WRAP -> elmCompilerPath
        }
        val elmFormatPath = settingsElement.getAttributeValue("elmFormatPath") ?: ""
        val elmTestPath = settingsElement.getAttributeValue("elmTestPath") ?: ""
        val elmReviewPath = settingsElement.getAttributeValue("elmReviewPath") ?: ""
        val elmReviewConfigPath = settingsElement.getAttributeValue("elmReviewConfigPath") ?: ""
        val isElmFormatOnSaveEnabled = settingsElement
            .getAttributeValue("isElmFormatOnSaveEnabled")
            .takeIf { it != null && it.isNotBlank() }?.toBoolean()
            ?: DEFAULT_FORMAT_ON_SAVE
        val isElmReviewOnTheFlyEnabled = settingsElement
            .getAttributeValue("isElmReviewOnTheFlyEnabled")
            .takeIf { it != null && it.isNotBlank() }?.toBoolean()
            ?: DEFAULT_REVIEW_ON_THE_FLY
        val isElmBuildOnSaveEnabled = settingsElement
            .getAttributeValue("isElmBuildOnSaveEnabled")
            .takeIf { it != null && it.isNotBlank() }?.toBoolean()
            ?: DEFAULT_BUILD_ON_SAVE
        val buildTargets = state.getChild("buildTargets")
            ?.getChildren("target")
            ?.mapNotNull { targetElement ->
                val name = targetElement.getAttributeValue("name") ?: ""
                val type = targetElement.getAttributeValue("type")
                    ?.let { rawType -> runCatching { ElmBuildTargetType.valueOf(rawType) }.getOrNull() }
                    ?: ElmBuildTargetType.APPLICATION
                val inputPath = targetElement.getAttributeValue("inputPath") ?: return@mapNotNull null
                val outputPath = targetElement.getAttributeValue("outputPath") ?: ""
                val mode = targetElement.getAttributeValue("mode")
                    ?.let { rawMode -> runCatching { ElmBuildMode.valueOf(rawMode) }.getOrNull() }
                    ?: ElmBuildMode.NONE
                val compilerKind = targetElement.getAttributeValue("compilerKind")
                    ?.let { rawKind -> runCatching { ElmCompilerKind.valueOf(rawKind) }.getOrNull() }
                    ?: ElmCompilerKind.ELM
                val compilerPath = targetElement.getAttributeValue("compilerPath") ?: ""
                val compileOnSave = targetElement.getAttributeValue("compileOnSave")
                    ?.takeIf { it.isNotBlank() }
                    ?.toBoolean() ?: false
                ElmBuildTargetConfig(
                    name = name,
                    type = type,
                    inputPath = inputPath,
                    outputPath = outputPath,
                    mode = mode,
                    compilerKind = compilerKind,
                    compilerPath = compilerPath,
                    compileOnSave = compileOnSave
                )
            }
            .orEmpty()

        modifySettings(notify = false) {
            RawSettings(
                elmCompilerPath = compilerPath,
                compilerType = compilerType,
                elmFormatPath = elmFormatPath,
                elmTestPath = elmTestPath,
                elmReviewPath = elmReviewPath,
                elmReviewConfigPath = elmReviewConfigPath,
                isElmFormatOnSaveEnabled = isElmFormatOnSaveEnabled,
                isElmReviewOnTheFlyEnabled = isElmReviewOnTheFlyEnabled,
                isElmBuildOnSaveEnabled = isElmBuildOnSaveEnabled,
                buildTargets = buildTargets
            )
        }

        return state.getChild("elmProjects")
            .getChildren("project")
            .mapNotNull { it.getAttributeValue("path") }
            .mapNotNull { Paths.get(it) }
            .map { path ->
                asyncLoadProject(path).exceptionally {
                    // TODO Communicate this error in the UI (while warnings may be fine for tests)
                    log.warn("Could not load child project", it)
                    null
                }
            }.joinAll()
            .thenApply { rawProjects ->
                if (rawProjects.isNotEmpty()) {
                    // Exclude `elm-stuff` directories to prevent pollution of open-by-filename, etc.
                    ApplicationManager.getApplication().invokeLater {
                        for (module in intellijProject.modules.asSequence()) {
                            ModuleRootModificationUtil.updateModel(module) { model ->
                                model.contentEntries.forEach {
                                    if ("elm-stuff" !in it.excludePatterns)
                                        it.addExcludePattern("elm-stuff")
                                }
                            }
                        }
                    }
                }

                modifyProjects { _ -> rawProjects.filterNotNull() }
            }
    }

//  The runWhenProjectIsInitialized method is deprecated. It has been upgraded (as of July 2025)
//  but the code is left here for reference because I'm not 100% convinced that it is completely upgraded.
//   -- AH.
//    override fun noStateLoaded() {
//        // The workspace is being opened for the first time. As soon as IntelliJ has
//        // fully loaded the project, we will attempt to auto-discover the Elm toolchain
//        // and the `elm.json` file.
//        StartupManager.getInstance(intellijProject).runWhenProjectIsInitialized {
//            asyncAutoDiscoverWorkspace(intellijProject)
//        }
//    }

    // NOTIFICATIONS


    private fun notifyDidChangeWorkspace(projectSetChanged: Boolean) {
        if (intellijProject.isDisposed) return
        ApplicationManager.getApplication().invokeLater({
            if (intellijProject.isDisposed) return@invokeLater
            runWriteAction {
                // Invalidate caches
                ResolveCache.getInstance(intellijProject).clearCache(true) // PsiReference resolve
                intellijProject.modificationTracker.incModificationCount() // CachedValuesManager: Elm Psi content
                changeTracker.incModificationCount()                       // CachedValuesManager: Elm workspace/settings

                // Refresh library roots
                if (projectSetChanged) {
                    ProjectRootManagerEx.getInstanceEx(intellijProject)
                        .makeRootsChange(EmptyRunnable.getInstance(), TOTAL_RESCAN)
                }
            }
            if (intellijProject.isDisposed) return@invokeLater
            intellijProject.messageBus.syncPublisher(WORKSPACE_TOPIC)
                .didUpdate()
        }, ModalityState.nonModal())
    }


    interface ElmWorkspaceListener {
        fun didUpdate()
    }


    companion object {
        // This topic covers any changes to the projects contained within the Elm workspace as
        // well as changes to the workspace settings.
        val WORKSPACE_TOPIC = Topic("Elm workspace changes", ElmWorkspaceListener::class.java)
    }
}


class ProjectLoadException(msg: String, cause: Exception? = null) : RuntimeException(msg, cause)


// AUTO-DISCOVER


fun asyncAutoDiscoverWorkspace(project: Project, explicitRequest: Boolean = false): CompletableFuture<Unit> {
    if (isUnitTestMode) return CompletableFuture.completedFuture(Unit)
    if (!explicitRequest) {
        val alreadyTried = run {
            val key = "org.elm.workspace.PROJECT_DISCOVERY"
            with(PropertiesComponent.getInstance(project)) {
                getBoolean(key).also { setValue(key, true) }
            }
        }
        if (alreadyTried) return CompletableFuture.completedFuture(Unit)
    }

    val toolchain = project.elmToolchain
    val suggestedToolchain = toolchain.autoDiscoverAll(project)
    if (suggestedToolchain != toolchain) {
        project.elmWorkspace.useToolchain(suggestedToolchain)
    }

    return project.elmWorkspace.asyncDiscoverAndRefresh().thenApply { }
}


// CONVENIENCE EXTENSIONS


val Project.elmWorkspace
    get() = service<ElmWorkspaceService>()


val Project.elmSettings
    get() = elmWorkspace.settings


val Project.elmToolchain: ElmToolchain
    get() = elmSettings.toolchain


fun resolveElmReviewConfigDir(projectBasePath: Path, configuredPath: String): Path {
    val defaultPath = projectBasePath.resolve("review")
    val configured = configuredPath.trim()
    if (configured.isBlank()) return defaultPath.normalize()
    val configPath = runCatching { Paths.get(configured) }.getOrNull() ?: return defaultPath.normalize()
    return if (configPath.isAbsolute) configPath.normalize() else projectBasePath.resolve(configPath).normalize()
}
