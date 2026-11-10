package org.elm.workspace

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import org.elm.fileTree
import org.elm.workspace.ui.ElmWorkspaceConfigurable
import org.elm.openapiext.elementFromXmlString
import org.elm.openapiext.pathAsPath
import org.elm.openapiext.toXmlString
import org.elm.workspace.compiler.ElmBuildMode
import org.elm.workspace.compiler.ElmBuildTargetConfig
import org.elm.workspace.compiler.ElmBuildTargetType
import org.elm.workspace.compiler.ElmCompilerKind
import org.junit.Test
import java.io.File
import java.nio.file.Paths

class ElmWorkspaceServiceTest : ElmWorkspaceTestBase() {

    @Test
    fun `test finds Elm project for source file`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                    elm("Utils.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val workspace = project.elmWorkspace.apply {
            asyncAttachElmProject(rootPath.resolve("a/elm.json")).get()
        }

        fun checkFile(relativePath: String, projectName: String?) {
            val vFile = testProject.root.findFileByRelativePath(relativePath)!!
            val project = workspace.findProjectForFile(vFile)
            if (project?.presentableName != projectName) {
                error("Expected $projectName, found $project for $relativePath")
            }
        }

        checkFile("a/src/Main.elm", "a")
        checkFile("a/src/Utils.elm", "a")
    }


    @Test
    fun `test can attach application json files`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", """
                    {
                        "type": "application",
                        "source-directories": [
                            "src", "vendor"
                        ],
                        "elm-version": "$installedElmCompilerVersion",
                        "dependencies": {
                            "direct": {
                                "elm/browser": "1.0.2",
                                "elm/core": "1.0.5",
                                "elm/html": "1.0.0"
                            },
                            "indirect": {
                                "elm/json": "1.1.4",
                                "elm/time": "1.0.0",
                                "elm/url": "1.0.0",
                                "elm/virtual-dom": "1.0.2"
                            }
                        },
                        "test-dependencies": {
                            "direct": {
                                "elm-explorations/test": "1.0.0"
                            },
                            "indirect": {
                                "elm/random": "1.0.0"
                            }
                        }
                    }
                    """)
                dir("src") {
                    elm("Main.elm")
                }
                dir("vendor") {
                    elm("VendoredPackage.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val workspace = project.elmWorkspace.apply {
            asyncAttachElmProject(rootPath.resolve("a/elm.json")).get()
        }

        val elmProject = workspace.allProjects.firstOrNull()
        if (elmProject == null) {
            fail("failed to find an Elm project")
            return
        }

        if (elmProject !is ElmApplicationProject) {
            fail("expected an Elm application project, got $elmProject")
            return
        }

        checkEquals(installedElmCompilerVersion, elmProject.elmVersion)
        checkEquals(setOf(Paths.get("src"), Paths.get("vendor")), elmProject.sourceDirectories.toSet())

        checkDependencies(elmProject.dependencies,
                mapOf(
                        "elm/browser" to Version(1, 0, 2),
                        "elm/core" to Version(1, 0, 5),
                        "elm/html" to Version(1, 0, 0)
                )
        )

        checkDependencies(elmProject.testDependencies,
                mapOf(
                        "elm-explorations/test" to Version(1, 0, 0)
                )
        )
    }

    @Test
    fun `test can attach package json files`() {
        val testProject = fileTree {
            project("elm.json", """
                    {
                        "type": "package",
                        "name": "elm/json",
                        "summary": "Encode and decode JSON values",
                        "license": "BSD-3-Clause",
                        "version": "1.2.3",
                        "exposed-modules": [
                            "Json.Decode",
                            "Json.Encode"
                        ],
                        "elm-version": "0.19.0 <= v < 0.20.0",
                        "dependencies": {
                            "elm/core": "1.0.0 <= v < 1.0.1"
                        },
                        "test-dependencies": {
                            "elm-explorations/test": "1.0.0 <= v < 1.0.1"
                        }
                    }
                    """)
            dir("src") {
                elm("Foo.elm")
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val workspace = project.elmWorkspace.apply {
            asyncAttachElmProject(rootPath.resolve("elm.json")).get()
        }

        val elmProject = workspace.allProjects.firstOrNull()
        if (elmProject == null) {
            fail("failed to find an Elm project")
            return
        }

        if (elmProject !is ElmPackageProject) {
            fail("expected an Elm package project, got $elmProject")
            return
        }

        checkEquals(makeConstraint(Version(0, 19, 0), Version(0, 20, 0))
                , elmProject.elmVersion)

        checkEquals(Version(1, 2, 3), elmProject.version)

        // The source directory for Elm 0.19 packages is implicitly "src". It cannot be changed.
        checkEquals(setOf(Paths.get("src")), elmProject.sourceDirectories.toSet())

        checkDependencies(elmProject.dependencies, mapOf("elm/core" to Version(1, 0, 0)))
        checkDependencies(elmProject.testDependencies, mapOf("elm-explorations/test" to Version(1, 0, 0)))

        checkEquals(setOf("Json.Decode", "Json.Encode"),
                elmProject.exposedModules.toSet())
    }

    @Test
    fun `test peekApplicationElmVersion reads the exact version from an application manifest`() {
        // This drives how the `~/.elm/<version>/` package cache directory is chosen when a project is
        // loaded. An application's manifest pins the exact Elm version it must be built with, which is
        // authoritative for locating its packages (Elm or Lamdera) regardless of the configured
        // compiler. Packages declare a range and fall back to the configured compiler (null here).
        val testProject = fileTree {
            dir("app") {
                project("elm.json", """
                    {
                      "type": "application",
                      "source-directories": [ "src" ],
                      "elm-version": "0.19.5",
                      "dependencies": { "direct": {}, "indirect": {} },
                      "test-dependencies": { "direct": {}, "indirect": {} }
                    }
                    """)
            }
            dir("lamdera") {
                project("elm.json", """
                    {
                      "type": "application",
                      "source-directories": [ "src" ],
                      "elm-version": "0.19.1",
                      "dependencies": {
                        "direct": { "lamdera/core": "1.0.0" },
                        "indirect": {}
                      },
                      "test-dependencies": { "direct": {}, "indirect": {} }
                    }
                    """)
            }
            dir("pkg") {
                project("elm.json", BASIC_PACKAGE_MANIFEST)
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath

        // Application: the exact pinned version comes from the manifest, independent of the configured
        // compiler. Using an unusual 0.19.5 makes clear the value is not the running compiler's.
        checkEquals(Version(0, 19, 5), peekApplicationElmVersion(rootPath.resolve("app/elm.json"))!!)

        // Lamdera application: its manifest pins the Lamdera compiler's Elm version (0.19.1), so
        // lookups land in ~/.elm/0.19.1/packages/ even under an Elm 0.19.2 toolchain.
        checkEquals(Version(0, 19, 1), peekApplicationElmVersion(rootPath.resolve("lamdera/elm.json"))!!)

        // Package: null (elm-version is a range; falls back to the compiler).
        check(peekApplicationElmVersion(rootPath.resolve("pkg/elm.json")) == null) {
            "Expected null for a package manifest (elm-version is a range)"
        }

        // Missing manifest: null, so the caller falls back to the configured compiler version.
        check(peekApplicationElmVersion(rootPath.resolve("nope/elm.json")) == null) {
            "Expected null for a missing manifest"
        }
    }

    @Test
    fun `test can attach multiple Elm projects`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
            dir("b") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val workspace = project.elmWorkspace.apply {
            asyncAttachElmProject(rootPath.resolve("a/elm.json")).get()
            asyncAttachElmProject(rootPath.resolve("b/elm.json")).get()
        }

        fun checkFile(relativePath: String, projectName: String?) {
            val vFile = testProject.root.findFileByRelativePath(relativePath)!!
            val project = workspace.findProjectForFile(vFile)
            if (project?.presentableName != projectName) {
                error("Expected $projectName, found $project for $relativePath")
            }
        }

        checkFile("a/src/Main.elm", "a")
        checkFile("b/src/Main.elm", "b")
    }


    @Test
    fun `test auto discover Elm project at root level`() {
        val testProject = fileTree {
            project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
            dir("src") {
                elm("Main.elm")
            }
        }.create(project, elmWorkspaceDirectory)

        val elmProjects = project.elmWorkspace.asyncDiscoverAndRefresh().get()
        check(elmProjects.size == 1) { "Should have found one Elm project but found ${elmProjects.size}" }
        val elmProject = elmProjects.first()
        check(elmProject.manifestPath == testProject.root.pathAsPath.resolve("elm.json"))
    }

    @Test
    fun `test auto discover Elm project skips bad project files`() {
        fileTree {
            project("elm.json", """ { "BOGUS": "INVALID ELM.JSON" } """)
            dir("src") {
                elm("Main.elm")
            }
        }.create(project, elmWorkspaceDirectory)

        val elmProjects = project.elmWorkspace.asyncDiscoverAndRefresh().get()
        check(elmProjects.isEmpty()) { "Should have found zero Elm projects but found ${elmProjects.size}" }
    }

    @Test
    fun `test refresh detaches project when manifest is deleted`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val workspace = project.elmWorkspace
        workspace.asyncAttachElmProject(rootPath.resolve("a/elm.json")).get()
        check(workspace.allProjects.size == 1) { "Expected one attached project before deleting manifest" }

        val manifestFile = testProject.root.findFileByRelativePath("a/elm.json")
            ?: error("Could not find manifest to delete")
        runWriteAction {
            manifestFile.delete(this)
        }

        workspace.asyncRefreshAllProjects().get()
        check(workspace.allProjects.isEmpty()) { "Expected no attached projects after deleting manifest and refreshing" }
    }

    @Test
    fun `test persistence roundtrip`() {
        // setup real files on disk
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath

        // The known-good, serialized state that we must be able to handle
        val projectPath = rootPath.resolve("a").resolve("elm.json")
        val projectPathString = projectPath.toString().replace("\\", "/") // normalize windows paths
        val xml = """
            <state>
              <elmProjects>
                <project path="$projectPathString" />
              </elmProjects>
              <settings elmCompilerPath="${toolchain.elmCompilerPath}" compilerType="ELM" elmFormatPath="${toolchain.elmFormatPath}" elmTestPath="${toolchain.elmTestPath}" elmReviewPath="" elmReviewConfigPath="" isElmFormatOnSaveEnabled="true" isElmReviewOnTheFlyEnabled="true" isElmBuildOnSaveEnabled="false" />
            </state>
            """.trimIndent()

        // ... must be able to load from serialized state ...
        workspace.asyncLoadState(elementFromXmlString(xml)).get()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val actualProjects = workspace.allProjects.map { it.manifestPath }
        val expectedProjects = listOf(projectPath)
        checkEquals(expectedProjects, actualProjects)

        // ... and serialize the resulting state ...
        val actualXml = workspace.state.toXmlString()
        checkEquals(xml, actualXml)
    }

    @Test
    fun `test migrates pre-migration build targets nested under projects to the flat model`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        val projectPath = rootPath.resolve("a").resolve("elm.json")
        val projectPathString = projectPath.toString().replace("\\", "/") // normalize windows paths
        val projectDirString = projectPath.parent.toString().replace("\\", "/")

        // The old serialized format: build targets nested under <project manifestPath="…"> with
        // project-relative input/output paths and no `type` attribute.
        val xml = """
            <state>
              <elmProjects>
                <project path="$projectPathString" />
              </elmProjects>
              <settings elmCompilerPath="${toolchain.elmCompilerPath}" compilerType="ELM" elmFormatPath="${toolchain.elmFormatPath}" elmTestPath="${toolchain.elmTestPath}" elmReviewPath="" elmReviewConfigPath="" isElmFormatOnSaveEnabled="true" isElmReviewOnTheFlyEnabled="true" isElmBuildOnSaveEnabled="false" />
              <buildTargets>
                <project manifestPath="$projectPathString">
                  <target name="App" inputPath="src/Main.elm" outputPath="build/main.js" mode="DEBUG" compilerKind="ELM" compilerPath="${toolchain.elmCompilerPath}" compileOnSave="true" />
                  <target name="Pkg check" inputPath="" outputPath="" mode="NONE" compilerKind="ELM" compilerPath="${toolchain.elmCompilerPath}" compileOnSave="false" />
                </project>
              </buildTargets>
            </state>
            """.trimIndent()

        workspace.asyncLoadState(elementFromXmlString(xml)).get()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val expected = listOf(
            // A relative input path becomes an APPLICATION target with an absolute input file and
            // an absolute (project-relative-resolved) output path.
            ElmBuildTargetConfig(
                name = "App",
                type = ElmBuildTargetType.APPLICATION,
                inputPath = "$projectDirString/src/Main.elm",
                outputPath = "$projectDirString/build/main.js",
                mode = ElmBuildMode.DEBUG,
                compilerKind = ElmCompilerKind.ELM,
                compilerPath = toolchain.elmCompilerPath.toString(),
                compileOnSave = true
            ),
            // A blank input path meant "type-check this package"; it becomes a PACKAGE target
            // pointing at the project's own elm.json.
            ElmBuildTargetConfig(
                name = "Pkg check",
                type = ElmBuildTargetType.PACKAGE,
                inputPath = projectPathString,
                outputPath = "",
                mode = ElmBuildMode.NONE,
                compilerKind = ElmCompilerKind.ELM,
                compilerPath = toolchain.elmCompilerPath.toString(),
                compileOnSave = false
            )
        )
        checkEquals(expected, workspace.buildTargets)
    }

    @Test
    fun `test asyncSetEnabledProjects enables and disables projects`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") { elm("Main.elm") }
            }
            dir("b") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") { elm("Main.elm") }
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val a = rootPath.resolve("a/elm.json")
        val b = rootPath.resolve("b/elm.json")
        val workspace = project.elmWorkspace

        // Enabling both attaches both.
        workspace.asyncSetEnabledProjects(setOf(a, b)).get()
        checkEquals(setOf(a, b), workspace.enabledProjectPaths)
        checkEquals(setOf(a, b), workspace.allProjects.map { it.manifestPath }.toSet())

        // Disabling one detaches it and removes it from the enabled set.
        workspace.asyncSetEnabledProjects(setOf(a)).get()
        checkEquals(setOf(a), workspace.enabledProjectPaths)
        checkEquals(setOf(a), workspace.allProjects.map { it.manifestPath }.toSet())
    }

    @Test
    fun `test enabled project that fails to load stays enabled and errored`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") { elm("Main.elm") }
            }
            dir("b") {
                project("elm.json", """ { "BOGUS": "INVALID ELM.JSON" } """)
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        val a = rootPath.resolve("a").resolve("elm.json")
        val b = rootPath.resolve("b").resolve("elm.json")
        val aString = a.toString().replace("\\", "/")
        val bString = b.toString().replace("\\", "/")

        val xml = """
            <state>
              <elmProjects>
                <project path="$aString" />
                <project path="$bString" />
              </elmProjects>
              <settings elmCompilerPath="${toolchain.elmCompilerPath}" compilerType="ELM" elmFormatPath="${toolchain.elmFormatPath}" elmTestPath="${toolchain.elmTestPath}" elmReviewPath="" elmReviewConfigPath="" isElmFormatOnSaveEnabled="true" isElmReviewOnTheFlyEnabled="true" isElmBuildOnSaveEnabled="false" />
            </state>
            """.trimIndent()

        workspace.asyncLoadState(elementFromXmlString(xml)).get()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Only the valid project loads...
        checkEquals(listOf(a), workspace.allProjects.map { it.manifestPath })
        // ...but both stay enabled (intent), and the bad one is recorded as errored.
        checkEquals(setOf(a, b), workspace.enabledProjectPaths)
        check(workspace.projectLoadErrors.containsKey(b)) { "Expected a recorded load error for $b" }

        // The enabled set (including the errored project) round-trips through persistence, so a
        // transient failure does not silently drop the project.
        val actualXml = workspace.state.toXmlString()
        checkEquals(xml, actualXml)
    }

    @Test
    fun `test settings panel builds and shows enabled project as checked`() {
        val testProject = fileTree {
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") { elm("Main.elm") }
            }
        }.create(project, elmWorkspaceDirectory)

        val a = testProject.root.pathAsPath.resolve("a").resolve("elm.json")
        project.elmWorkspace.asyncAttachElmProject(a).get()

        // Build and reset the whole settings panel; this exercises the projects checkbox list and
        // its toolbar wiring the same way opening Settings > Languages & Frameworks > Elm would.
        val configurable = ElmWorkspaceConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            // reset() populates the enabled-projects checkbox list asynchronously (discovery runs
            // off the EDT), so pump the event queue until that settles before asserting.
            PlatformTestUtil.waitWithEventsDispatching(
                "A freshly reset settings panel should not be modified",
                { !configurable.isModified },
                10
            )
        } finally {
            configurable.disposeUIResources()
            Disposer.dispose(configurable)
        }
    }

    @Test
    fun `test discoverElmJsonManifestPaths lists content elm-json sorted by depth then name`() {
        val testProject = fileTree {
            project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
            dir("src") { elm("Main.elm") }
            dir("b") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
            }
            dir("a") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val discovered = project.elmWorkspace.discoverElmJsonManifestPaths()
        checkEquals(
            listOf(
                rootPath.resolve("elm.json"),
                rootPath.resolve("a").resolve("elm.json"),
                rootPath.resolve("b").resolve("elm.json")
            ),
            discovered
        )
    }

    @Test
    fun `test discoverElmJsonManifestPaths skips node_modules and elm-stuff`() {
        val testProject = fileTree {
            project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
            dir("src") { elm("Main.elm") }
            dir("node_modules") {
                dir("some-elm-package") {
                    project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                }
            }
            dir("elm-stuff") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val discovered = project.elmWorkspace.discoverElmJsonManifestPaths()
        checkEquals(listOf(rootPath.resolve("elm.json")), discovered)
    }


    // START OF TESTS RELATED TO SIDECAR MANIFEST (elm.intellij.json)

    @Test
    fun `test uses default tests location when no sidecar manifest exists (application project)`() =
            testSidecarManifest(null, "tests", false)

    @Test
    fun `test uses custom tests location when sidecar manifest exists`() =
            testSidecarManifest(getSidecarManifest("custom-tests"), "custom-tests", true)

    @Test
    fun `test uses default tests location when sidecar manifest specifies default location`() =
            testSidecarManifest(getSidecarManifest("tests"), "tests", false)

    private val nestedTestsLocation = listOf("custom", "tests", "location").joinToString(File.separator)

    @Test
    fun `test handles nested custom tests location`() =
            testSidecarManifest(getSidecarManifest(nestedTestsLocation), nestedTestsLocation, true)

    @Test
    fun `test normalizes custom tests location`() {
        // Repeat above test with path like "./custom/tests/location/." (i.e. leading and trailing dot).
        val denormalizedNestedTestsLocation = listOf(".", "custom", "tests", "location", ".").joinToString(File.separator)
        testSidecarManifest(getSidecarManifest(denormalizedNestedTestsLocation), nestedTestsLocation, true)
    }

    @Test
    fun `test uses default tests location when no sidecar manifest exists (package project)`() =
            testSidecarManifest(null, "tests", expectedIsCustomTestsDir = false, isApplicationProject = false)

    @Test
    fun `test ignores custom tests location for packages`() =
            testSidecarManifest(getSidecarManifest("custom-tests"), "tests", expectedIsCustomTestsDir = false, isApplicationProject = false)

    @Test
    fun `test build target derives its Elm project from the input file`() {
        val testProject = fileTree {
            dir("app") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        workspace.asyncAttachElmProject(rootPath.resolve("app/elm.json")).get()

        val compilerPath = project.elmToolchain.compilerPath?.toString()
            ?: error("Compiler path is not configured in test toolchain")
        val inputPath = rootPath.resolve("app/src/Main.elm").toString()
        workspace.setBuildTargets(
            listOf(
                ElmBuildTargetConfig(
                    name = "App build",
                    inputPath = inputPath,
                    outputPath = "",
                    mode = ElmBuildMode.NONE,
                    compilerKind = ElmCompilerKind.ELM,
                    compilerPath = compilerPath,
                    compileOnSave = true
                )
            )
        )

        val outcome = workspace.resolveBuildTargetsDetailed().single()
        check(outcome.error == null) { "Expected target to resolve, got error: ${outcome.error}" }
        val resolved = outcome.resolved ?: error("Expected a resolved target")
        checkEquals(inputPath, resolved.inputPathForCompiler)
    }

    @Test
    fun `test build target reports an error for a blank input path`() {
        val testProject = fileTree {
            dir("app") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        workspace.asyncAttachElmProject(rootPath.resolve("app/elm.json")).get()

        val compilerPath = project.elmToolchain.compilerPath?.toString()
            ?: error("Compiler path is not configured in test toolchain")
        workspace.setBuildTargets(
            listOf(
                ElmBuildTargetConfig(
                    name = "App build",
                    inputPath = "",
                    outputPath = "",
                    mode = ElmBuildMode.NONE,
                    compilerKind = ElmCompilerKind.ELM,
                    compilerPath = compilerPath,
                    compileOnSave = true
                )
            )
        )

        val outcome = workspace.resolveBuildTargetsDetailed().single()
        check(outcome.resolved == null) { "Expected target to fail, got ${outcome.resolved}" }
        check(outcome.error?.contains("input file is not set") == true) { "Unexpected error: ${outcome.error}" }
    }

    @Test
    fun `test package build target resolves to the chosen elm-json and builds with no arguments`() {
        val testProject = fileTree {
            dir("pkg") {
                project("elm.json", BASIC_PACKAGE_MANIFEST)
                dir("src") {
                    elm("Foo.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        val manifestPath = rootPath.resolve("pkg/elm.json")
        workspace.asyncAttachElmProject(manifestPath).get()

        val elmProject = workspace.allProjects.single() as ElmPackageProject
        val compilerPath = project.elmToolchain.compilerPath?.toString()
            ?: error("Compiler path is not configured in test toolchain")
        workspace.setBuildTargets(
            listOf(
                ElmBuildTargetConfig(
                    name = "Package check",
                    type = ElmBuildTargetType.PACKAGE,
                    inputPath = manifestPath.toString(),
                    compilerKind = ElmCompilerKind.ELM,
                    compilerPath = compilerPath,
                    compileOnSave = true
                )
            )
        )

        val outcome = workspace.resolveBuildTargetsDetailed().single()
        check(outcome.error == null) { "Expected package target to resolve, got error: ${outcome.error}" }
        val resolved = outcome.resolved ?: error("Expected a resolved target")
        checkEquals(ElmBuildTargetType.PACKAGE, resolved.type)
        checkEquals(elmProject.projectDirPath, resolved.workDir)
        // A package is type-checked by `elm make` with no input/output/mode arguments.
        checkEquals(listOf("make"), resolved.makeParameters())
    }

    @Test
    fun `test package build target resolves even when its elm-json is not an attached project`() {
        val testProject = fileTree {
            dir("app") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") { elm("Main.elm") }
            }
            // A second package that is deliberately NOT attached to the workspace.
            dir("pkg") {
                project("elm.json", BASIC_PACKAGE_MANIFEST)
                dir("src") { elm("Foo.elm") }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        workspace.asyncAttachElmProject(rootPath.resolve("app/elm.json")).get()

        val compilerPath = project.elmToolchain.compilerPath?.toString()
            ?: error("Compiler path is not configured in test toolchain")
        val pkgManifest = rootPath.resolve("pkg/elm.json")
        workspace.setBuildTargets(
            listOf(
                ElmBuildTargetConfig(
                    name = "Unattached package",
                    type = ElmBuildTargetType.PACKAGE,
                    inputPath = pkgManifest.toString(),
                    compilerKind = ElmCompilerKind.ELM,
                    compilerPath = compilerPath,
                    compileOnSave = true
                )
            )
        )

        val outcome = workspace.resolveBuildTargetsDetailed().single()
        check(outcome.error == null) { "Expected package target to resolve without attachment, got error: ${outcome.error}" }
        val resolved = outcome.resolved ?: error("Expected a resolved target")
        // No attached project claims it, but the working directory is the elm.json's directory.
        checkEquals(pkgManifest.parent, resolved.workDir)
        checkEquals(listOf("make"), resolved.makeParameters())
    }

    @Test
    fun `test an automatic test target is appended for a project that has a tests directory`() {
        val testProject = fileTree {
            dir("app") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
                dir("tests") {
                    elm("MainTest.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        workspace.asyncAttachElmProject(rootPath.resolve("app/elm.json")).get()
        val elmProject = workspace.allProjects.single()

        // Ensure elm-test is configured so the target resolves; the resolution logic only needs a
        // non-null path, so reuse the compiler executable (which exists) rather than depending on
        // elm-test being discoverable in the test sandbox.
        val elmTestPath = project.elmToolchain.compilerPath ?: error("Compiler path is not configured in test toolchain")
        workspace.useToolchain(project.elmToolchain.copy(elmTestPath = elmTestPath))

        // No configured build targets: the only outcome should be the automatic test target,
        // appended after the (empty) configured list.
        val outcomes = workspace.resolveBuildTargetsDetailed()
        val testOutcome = outcomes.single()
        checkEquals(ElmBuildTargetType.TEST, testOutcome.config.type)
        checkEquals("Tests (${elmProject.presentableName})", testOutcome.config.name)

        val resolved = testOutcome.resolved ?: error("Expected the test target to resolve: ${testOutcome.error}")
        checkEquals(ElmBuildTargetType.TEST, resolved.type)
        checkEquals(elmProject.projectDirPath, resolved.workDir)
        checkEquals(elmProject.testsDirPath, resolved.inputPath)
        check(resolved.testExecutablePath == elmTestPath) { "Expected elm-test path $elmTestPath, got ${resolved.testExecutablePath}" }
        check(resolved.testsCustomDir == null) { "Default tests dir should not be passed as an argument" }
    }

    @Test
    fun `test no automatic test target is created when the project has no tests directory`() {
        val testProject = fileTree {
            dir("app") {
                project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val workspace = project.elmWorkspace
        val rootPath = testProject.root.pathAsPath
        workspace.asyncAttachElmProject(rootPath.resolve("app/elm.json")).get()

        check(workspace.resolveBuildTargetsDetailed().isEmpty()) {
            "Expected no build targets when there are no configured targets and no tests directory"
        }
    }

    @Test
    fun `test auto discover Elm project skips project with bad sidecar manifest`() {
        fileTree {
            project("elm.json", basicApplicationManifest(installedElmCompilerVersion))
            file("elm.intellij.json", """ { "BOGUS": "INVALID ELM.INTELLIJ.JSON" } """)
            dir("src") {
                elm("Main.elm")
            }
        }.create(project, elmWorkspaceDirectory)

        val elmProjects = project.elmWorkspace.asyncDiscoverAndRefresh().get()
        check(elmProjects.isEmpty()) { "Should have found zero Elm projects but found ${elmProjects.size}" }
    }

    /**
     * Executes a test related to the sidecar manifest (`elm.intellij.json`). Creates a project, adding a sidecar manifest
     * with the specified `sidecarManifestContent` if not null. Then verifies that the [ElmProject] generated by attaching
     * the manifest has the expected data related to the sidecar manifest, i.e. information about the tests directory.
     */
    private fun testSidecarManifest(
            sidecarManifestContent: String?,
            expectedTestsRelativeDirPath: String,
            expectedIsCustomTestsDir: Boolean,
            isApplicationProject: Boolean = true
    ) {

        val testProject = fileTree {
            dir("a") {
                project("elm.json", if (isApplicationProject) basicApplicationManifest(installedElmCompilerVersion) else BASIC_PACKAGE_MANIFEST)
                if (sidecarManifestContent != null)
                    file("elm.intellij.json", sidecarManifestContent)
                dir("src") {
                    elm("Main.elm")
                }
            }
        }.create(project, elmWorkspaceDirectory)

        val rootPath = testProject.root.pathAsPath
        val workspace = project.elmWorkspace.apply {
            asyncAttachElmProject(rootPath.resolve("a/elm.json")).get()
        }

        val elmProject = workspace.allProjects.firstOrNull() ?: error("No Elm project found")

        checkEquals(expectedTestsRelativeDirPath, elmProject.testsRelativeDirPath)
        checkEquals(expectedIsCustomTestsDir, elmProject.isCustomTestsDir)
        checkEquals(elmProject.projectDirPath.resolve(expectedTestsRelativeDirPath).normalize(), elmProject.testsDirPath)
    }

    // END OF TESTS RELATED TO SIDECAR MANIFEST (elm.intellij.json)

    private fun checkDependencies(actual: List<ElmPackageProject>, expected: Map<String, Version>) {
        checkEquals(actual.associate { it.name to it.version }, expected)
    }
}


private fun makeConstraint(low: Version, high: Version): Constraint {
    return Constraint(
            low = low,
            lowOp = Constraint.Op.LESS_THAN_OR_EQUAL,
            highOp = Constraint.Op.LESS_THAN,
            high = high
    )
}

/**
 * A minimal `elm.json` file for an application project.
 *
 * Parameterized by the installed compiler version: an application's `elm-version` must match the
 * compiler that builds it (the compiler rejects a mismatch), and that version also selects which
 * `~/.elm/<version>/packages/` directory the plugin resolves its dependencies from.
 */
private fun basicApplicationManifest(elmVersion: Version) = """
{
  "type": "application",
  "source-directories": [ "src" ],
  "elm-version": "$elmVersion",
  "dependencies": {
    "direct": {
        "elm/core": "1.0.0",
        "elm/json": "1.0.0"
    },
    "indirect": {}
  },
  "test-dependencies": {
    "direct": {},
    "indirect": {}
  }
}
"""

/**
 * A minimal `elm.json` file for a package project.
 */
private const val BASIC_PACKAGE_MANIFEST = """
{
  "type": "package",
  "name": "foo/bar",
  "summary": "Example package",
  "license": "MIT",
  "version": "1.2.3",
  "exposed-modules": [],
  "elm-version": "0.19.0 <= v < 0.20.0",
  "dependencies": {
    "elm/core": "1.0.0 <= v < 2.0.0"
  },
  "test-dependencies": { }
}
"""

/**
 * Builds a sidecar manifest (i.e. `elm.intellij.json`) with the specified test directory.
 */
private fun getSidecarManifest(testDir: String): String = """
{
  "test-directory": "${testDir.replace("\\", "\\\\")}"
}
"""
