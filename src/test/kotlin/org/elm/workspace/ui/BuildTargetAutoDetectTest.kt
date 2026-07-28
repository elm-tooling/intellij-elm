package org.elm.workspace.ui

import org.elm.fileTree
import org.elm.openapiext.pathAsPath
import org.elm.workspace.ElmWorkspaceTestBase
import org.elm.workspace.LamderaApplicationProject
import org.elm.workspace.Version
import org.elm.workspace.elmWorkspace
import org.elm.workspace.compiler.ElmBuildTargetConfig
import org.elm.workspace.compiler.ElmBuildTargetType
import org.elm.workspace.compiler.ElmCompilerKind
import org.junit.Test
import java.nio.file.Paths

class BuildTargetAutoDetectTest : ElmWorkspaceTestBase() {

    @Test
    fun `test suggests a package target for a package project`() {
        buildProject {
            project("elm.json", PACKAGE_MANIFEST)
            dir("src") { elm("Foo.elm") }
        }
        val elmProject = project.elmWorkspace.allProjects.single()

        val suggestion = detectBuildTargetSuggestions(project, emptyList()).single()

        checkEquals("foo/bar (package)", suggestion.label)
        checkEquals("foo/bar", suggestion.config.name)
        checkEquals(ElmBuildTargetType.PACKAGE, suggestion.config.type)
        checkEquals(ElmCompilerKind.ELM, suggestion.config.compilerKind)
        // A package target's input is the project's elm.json.
        checkEquals(elmProject.manifestPath.toString(), suggestion.config.inputPath)
    }

    @Test
    fun `test suggests an application target for a file with a top-level main`() {
        val testProject = buildProject {
            project("elm.json", applicationManifest(installedElmCompilerVersion))
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (main)
                    main = 0
                """.trimIndent())
            }
        }
        val elmProject = project.elmWorkspace.allProjects.single()

        val suggestion = detectBuildTargetSuggestions(project, emptyList()).single()

        checkEquals("${elmProject.presentableName}: src/Main.elm (application)", suggestion.label)
        checkEquals("src/Main.elm", suggestion.config.name)
        checkEquals(ElmBuildTargetType.APPLICATION, suggestion.config.type)
        checkEquals(ElmCompilerKind.ELM, suggestion.config.compilerKind)
        // The input is the absolute path to the entry file; output is left blank.
        val mainPath = testProject.root.findFileByRelativePath("src/Main.elm")!!.path
        checkEquals(mainPath, suggestion.config.inputPath)
        checkEquals("", suggestion.config.outputPath)
    }

    @Test
    fun `test suggests a target for each file with a top-level main`() {
        buildProject {
            project("elm.json", applicationManifest(installedElmCompilerVersion))
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (main)
                    main = 0
                """.trimIndent())
                elm("Second.elm", """
                    module Second exposing (main)
                    main = 0
                """.trimIndent())
            }
        }
        val elmProject = project.elmWorkspace.allProjects.single()

        val suggestions = detectBuildTargetSuggestions(project, emptyList())

        checkEquals(
            setOf("src/Main.elm", "src/Second.elm"),
            suggestions.map { it.config.name }.toSet()
        )
        checkEquals(
            setOf(
                "${elmProject.presentableName}: src/Main.elm (application)",
                "${elmProject.presentableName}: src/Second.elm (application)"
            ),
            suggestions.map { it.label }.toSet()
        )
    }

    @Test
    fun `test does not suggest anything for an application without a top-level main`() {
        buildProject {
            project("elm.json", applicationManifest(installedElmCompilerVersion))
            dir("src") { elm("Other.elm") }
        }

        checkEquals(true, detectBuildTargetSuggestions(project, emptyList()).isEmpty())
    }

    @Test
    fun `test ignores a main nested in a let expression`() {
        buildProject {
            project("elm.json", applicationManifest(installedElmCompilerVersion))
            dir("src") {
                elm("Widget.elm", """
                    module Widget exposing (view)
                    view =
                        let
                            main = 0
                        in
                        main
                """.trimIndent())
            }
        }

        checkEquals(true, detectBuildTargetSuggestions(project, emptyList()).isEmpty())
    }

    @Test
    fun `test filters out a package suggestion already covered by an existing target`() {
        buildProject {
            project("elm.json", PACKAGE_MANIFEST)
            dir("src") { elm("Foo.elm") }
        }
        val elmProject = project.elmWorkspace.allProjects.single()

        val existing = listOf(
            ElmBuildTargetConfig(
                type = ElmBuildTargetType.PACKAGE,
                inputPath = elmProject.manifestPath.toString()
            )
        )
        checkEquals(true, detectBuildTargetSuggestions(project, existing).isEmpty())

        // An application target with the same path must NOT filter the package suggestion (different type).
        val differentType = listOf(
            ElmBuildTargetConfig(
                type = ElmBuildTargetType.APPLICATION,
                inputPath = elmProject.manifestPath.toString()
            )
        )
        checkEquals(1, detectBuildTargetSuggestions(project, differentType).size)
    }

    @Test
    fun `test filters out an application suggestion already covered by an existing target`() {
        val testProject = buildProject {
            project("elm.json", applicationManifest(installedElmCompilerVersion))
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (main)
                    main = 0
                """.trimIndent())
            }
        }
        val mainPath = testProject.root.findFileByRelativePath("src/Main.elm")!!.path

        // Surrounding whitespace is tolerated (the matcher trims before comparing).
        val existing = listOf(
            ElmBuildTargetConfig(
                type = ElmBuildTargetType.APPLICATION,
                inputPath = "  $mainPath  "
            )
        )
        checkEquals(true, detectBuildTargetSuggestions(project, existing).isEmpty())
    }

    // The Lamdera cases build the project by hand and call [buildTargetSuggestionsFor] directly.
    // Loading a real Lamdera project through the workspace would run the compiler to install its
    // dependencies into ~/.elm, which the test suite deliberately avoids for Lamdera packages.

    @Test
    fun `test suggests Lamdera Frontend and Backend targets`() {
        val testProject = fileTree {
            dir("src") {
                elm("Frontend.elm")
                elm("Backend.elm")
            }
        }.create(project, elmWorkspaceDirectory)
        val lamderaProject = lamderaProjectAt(testProject.root.pathAsPath)

        val suggestions = buildTargetSuggestionsFor(project, listOf(lamderaProject), emptyList())

        checkEquals(
            setOf(
                "${lamderaProject.presentableName}: Lamdera Frontend",
                "${lamderaProject.presentableName}: Lamdera Backend"
            ),
            suggestions.map { it.label }.toSet()
        )
        val byName = suggestions.associateBy { it.config.name }
        checkEquals(setOf("Lamdera Frontend", "Lamdera Backend"), byName.keys)
        for (suggestion in suggestions) {
            checkEquals(ElmBuildTargetType.APPLICATION, suggestion.config.type)
            checkEquals(ElmCompilerKind.LAMDERA, suggestion.config.compilerKind)
        }
        checkEquals(
            testProject.root.findFileByRelativePath("src/Frontend.elm")!!.path,
            byName.getValue("Lamdera Frontend").config.inputPath
        )
        checkEquals(
            testProject.root.findFileByRelativePath("src/Backend.elm")!!.path,
            byName.getValue("Lamdera Backend").config.inputPath
        )
    }

    @Test
    fun `test only suggests a Lamdera target for a file that exists`() {
        val testProject = fileTree {
            dir("src") { elm("Frontend.elm") } // no Backend.elm
        }.create(project, elmWorkspaceDirectory)
        val lamderaProject = lamderaProjectAt(testProject.root.pathAsPath)

        val suggestions = buildTargetSuggestionsFor(project, listOf(lamderaProject), emptyList())

        checkEquals(listOf("Lamdera Frontend"), suggestions.map { it.config.name })
    }

    @Test
    fun `test filters out a Lamdera suggestion already covered by an existing target`() {
        val testProject = fileTree {
            dir("src") {
                elm("Frontend.elm")
                elm("Backend.elm")
            }
        }.create(project, elmWorkspaceDirectory)
        val lamderaProject = lamderaProjectAt(testProject.root.pathAsPath)

        val frontendPath = testProject.root.findFileByRelativePath("src/Frontend.elm")!!.path
        val existing = listOf(
            ElmBuildTargetConfig(type = ElmBuildTargetType.APPLICATION, inputPath = frontendPath)
        )
        val suggestions = buildTargetSuggestionsFor(project, listOf(lamderaProject), existing)

        checkEquals(listOf("Lamdera Backend"), suggestions.map { it.config.name })
    }

    private fun lamderaProjectAt(projectDir: java.nio.file.Path) =
        LamderaApplicationProject(
            manifestPath = projectDir.resolve("elm.json"),
            elmVersion = Version(0, 19, 1),
            dependencies = emptyList(),
            testDependencies = emptyList(),
            sourceDirectories = listOf(Paths.get("src"))
        )
}

/** A minimal `elm.json` for an application project (deps must exist in the local package cache). */
private fun applicationManifest(elmVersion: Version) = """
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

/** A minimal `elm.json` for a package project named `foo/bar`. */
private const val PACKAGE_MANIFEST = """
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
