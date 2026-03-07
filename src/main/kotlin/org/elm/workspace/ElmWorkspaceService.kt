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
        val isElmFormatOnSaveEnabled: Boolean = DEFAULT_FORMAT_ON_SAVE,
        val isElmReviewOnTheFlyEnabled: Boolean = DEFAULT_REVIEW_ON_THE_FLY,
        val isElmBuildOnSaveEnabled: Boolean = DEFAULT_BUILD_ON_SAVE,
        val buildTargetsByManifest: List<ElmProjectBuildTargetConfig> = emptyList()
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

    fun buildTargetConfigsFor(elmProject: ElmProject): List<ElmBuildTargetConfig> {
        val manifestPath = elmProject.manifestPath.systemIndependentPath
        return rawSettingsRef.get().buildTargetsByManifest
            .firstOrNull { it.manifestPath == manifestPath }
            ?.targets
            .orEmpty()
    }

    fun setBuildTargetConfigsFor(manifestPath: Path, targets: List<ElmBuildTargetConfig>) {
        val key = manifestPath.systemIndependentPath
        modifySettings {
            val rest = it.buildTargetsByManifest.filterNot { cfg -> cfg.manifestPath == key }
            val updated = if (targets.isEmpty()) {
                rest
            } else {
                rest + ElmProjectBuildTargetConfig(key, targets)
            }
            it.copy(buildTargetsByManifest = updated.sortedBy { cfg -> cfg.manifestPath })
        }
    }

    fun resolveBuildTargets(
        elmProject: ElmProject
    ): Result<List<ResolvedBuildTarget>> {
        val targets = buildTargetConfigsFor(elmProject)
        if (targets.isEmpty()) {
            return Result.Err("No build targets configured")
        }

        val resolved = mutableListOf<ResolvedBuildTarget>()
        val errors = mutableListOf<String>()
        val projectRoot = elmProject.projectDirPath
        for ((index, target) in targets.withIndex()) {
            val row = index + 1
            val inputRaw = target.inputPath.trim()
            val inputAllowedBlank = elmProject is ElmPackageProject
            if (inputRaw.isBlank() && !inputAllowedBlank) {
                errors += "Row $row: input path is blank"
                continue
            }

            val inputAbsPath = if (inputRaw.isBlank()) {
                projectRoot.resolve(ELM_JSON).normalize()
            } else {
                val inputRelPath = inputRaw.toPathOrNull()
                if (inputRelPath == null || inputRelPath.isAbsolute) {
                    errors += "Row $row: input path must be project-relative"
                    continue
                }
                val input = projectRoot.resolve(inputRelPath).normalize()
                if (!input.startsWith(projectRoot) || !Files.exists(input)) {
                    errors += "Row $row: input file '$inputRaw' does not exist in the project"
                    continue
                }
                if (input.fileName?.toString()?.endsWith(".elm") != true) {
                    errors += "Row $row: input file '$inputRaw' is not an Elm file"
                    continue
                }
                input
            }

            val compilerRaw = target.compilerPath.trim()
            val compilerPath = compilerRaw.toPathOrNull()
            if (compilerRaw.isBlank() || compilerPath == null || !Files.isExecutable(compilerPath)) {
                errors += "Row $row: compiler path '$compilerRaw' is invalid or not executable"
                continue
            }

            val outputRaw = target.outputPath.trim()
            val outputForCompiler = if (outputRaw.isBlank()) {
                nullOutputTargetPathString()
            } else {
                val outputPath = outputRaw.toPathOrNull()
                if (outputPath == null || outputPath.isAbsolute) {
                    errors += "Row $row: output path must be project-relative (or blank)"
                    continue
                }
                val outputAbsPath = projectRoot.resolve(outputPath).normalize()
                if (!outputAbsPath.startsWith(projectRoot)) {
                    errors += "Row $row: output path '$outputRaw' is outside of the project"
                    continue
                }
                outputRaw
            }

            resolved += ResolvedBuildTarget(
                name = target.name,
                inputPath = inputAbsPath,
                inputPathForCompiler = inputRaw,
                outputPathForCompiler = outputForCompiler,
                mode = target.mode,
                compilerKind = target.compilerKind,
                compilerPath = compilerPath,
                compileOnSave = target.compileOnSave,
                offset = 0
            )
        }
        return when {
            errors.isNotEmpty() -> Result.Err(errors.joinToString("\n"))
            else -> Result.Ok(resolved)
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
                installProjectDeps(manifestPath)
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

    private fun installProjectDeps(manifestPath: Path): Boolean {
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
            if (isUnitTestMode) {
                Version(0, 19, 1)
            } else {
                settings.toolchain.queryCompilerVersion(intellijProject).orNull()
                    ?: run {
                        log.warn("Could not determine version of the selected compiler while refreshing Elm projects. Falling back to 0.19.1.")
                        Version(0, 19, 1)
                    }
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
        if (isUnitTestMode) return Version(0, 19, 1)
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
        settingsElement.setAttribute("isElmFormatOnSaveEnabled", raw.isElmFormatOnSaveEnabled.toString())
        settingsElement.setAttribute("isElmReviewOnTheFlyEnabled", raw.isElmReviewOnTheFlyEnabled.toString())
        settingsElement.setAttribute("isElmBuildOnSaveEnabled", raw.isElmBuildOnSaveEnabled.toString())
        if (raw.buildTargetsByManifest.isNotEmpty()) {
            val buildTargetsElement = Element("buildTargets")
            state.addContent(buildTargetsElement)
            for (projectConfig in raw.buildTargetsByManifest.sortedBy { it.manifestPath }) {
                val projectElement = Element("project")
                    .setAttribute("manifestPath", projectConfig.manifestPath)
                for (target in projectConfig.targets) {
                    projectElement.addContent(
                        Element("target")
                            .setAttribute("name", target.name)
                            .setAttribute("inputPath", target.inputPath)
                            .setAttribute("outputPath", target.outputPath)
                            .setAttribute("mode", target.mode.name)
                            .setAttribute("compilerKind", target.compilerKind.name)
                            .setAttribute("compilerPath", target.compilerPath)
                            .setAttribute("compileOnSave", target.compileOnSave.toString())
                    )
                }
                buildTargetsElement.addContent(projectElement)
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
        val buildTargetsByManifest = state.getChild("buildTargets")
            ?.getChildren("project")
            ?.mapNotNull { projectElement ->
                val manifestPath = projectElement.getAttributeValue("manifestPath") ?: return@mapNotNull null
                val targets = projectElement.getChildren("target")
                    .mapNotNull { targetElement ->
                        val name = targetElement.getAttributeValue("name") ?: ""
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
                            inputPath = inputPath,
                            outputPath = outputPath,
                            mode = mode,
                            compilerKind = compilerKind,
                            compilerPath = compilerPath,
                            compileOnSave = compileOnSave
                        )
                    }
                ElmProjectBuildTargetConfig(manifestPath = manifestPath, targets = targets)
            }
            ?.sortedBy { it.manifestPath }
            .orEmpty()

        modifySettings(notify = false) {
            RawSettings(
                elmCompilerPath = compilerPath,
                compilerType = compilerType,
                elmFormatPath = elmFormatPath,
                elmTestPath = elmTestPath,
                elmReviewPath = elmReviewPath,
                isElmFormatOnSaveEnabled = isElmFormatOnSaveEnabled,
                isElmReviewOnTheFlyEnabled = isElmReviewOnTheFlyEnabled,
                isElmBuildOnSaveEnabled = isElmBuildOnSaveEnabled,
                buildTargetsByManifest = buildTargetsByManifest
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
