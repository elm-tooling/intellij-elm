package org.elm.workspace.elmreview

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import junit.framework.TestCase
import org.elm.lang.ElmTestBase
import org.intellij.lang.annotations.Language
import org.junit.Test


class ElmReviewJsonReportTest : ElmTestBase() {

    companion object {
        private const val NO_DEBUG_LOG_MESSAGE = "Remove the use of `Debug.log` before shipping to production"
        private const val NO_DEBUG_LOG_DETAILS = "`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
        private const val NO_DEBUG_LOG_RULE_LINK = "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log"
    }

    // $ elm --version
    // 0.19
    // $ elm-review src/Foo.elm --report=json

    @Test
    fun `parses type review-errors`() {
        @Language("JSON")
        val json = """
        {
          "type": "review-errors",
          "errors": [
            {
              "path": "src/Frontend.elm",
              "errors": [
                {
                  "rule": "NoDebug.Log",
                  "message": "Remove the use of `Debug.log` before shipping to production",
                  "ruleLink": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log",
                  "details": [
                    "`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "region": {
                    "start": {
                      "line": 56,
                      "column": 13
                    },
                    "end": {
                      "line": 56,
                      "column": 22
                    }
                  },
                  "fix": [
                    {
                      "range": {
                        "start": {
                          "line": 56,
                          "column": 13
                        },
                        "end": {
                          "line": 56,
                          "column": 30
                        }
                      },
                      "string": ""
                    }
                  ],
                  "formatted": [
                    {
                      "string": "(fix) ",
                      "color": "#33BBC8"
                    },
                    {
                      "string": "NoDebug.Log",
                      "color": "#FF0000",
                      "href": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log"
                    },
                    ": Remove the use of `Debug.log` before shipping to production\n\n55|         NoOpFrontendMsg ->\n56|             Debug.log \"BBBB\" ( model, Cmd.none )\n                ",
                    {
                      "string": "^^^^^^^^^",
                      "color": "#FF0000"
                    },
                    "\n\n`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "suppressed": false,
                  "originallySuppressed": false
                },
                {
                  "rule": "NoDebug.Log",
                  "message": "Remove the use of `Debug.log` before shipping to production",
                  "ruleLink": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log",
                  "details": [
                    "`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "region": {
                    "start": {
                      "line": 53,
                      "column": 17
                    },
                    "end": {
                      "line": 53,
                      "column": 26
                    }
                  },
                  "fix": [
                    {
                      "range": {
                        "start": {
                          "line": 53,
                          "column": 17
                        },
                        "end": {
                          "line": 53,
                          "column": 34
                        }
                      },
                      "string": ""
                    }
                  ],
                  "formatted": [
                    {
                      "string": "(fix) ",
                      "color": "#33BBC8"
                    },
                    {
                      "string": "NoDebug.Log",
                      "color": "#FF0000",
                      "href": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log"
                    },
                    ": Remove the use of `Debug.log` before shipping to production\n\n52|         UrlChanged url ->\n53|                 Debug.log \"AAAA\" ( model, Cmd.none )\n                    ",
                    {
                      "string": "^^^^^^^^^",
                      "color": "#FF0000"
                    },
                    "\n\n`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "suppressed": false,
                  "originallySuppressed": false
                }
              ]
            }
          ]
        }""".trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT

        assertEquals(
            listOf(
                ElmReviewError(
                    suppressed = false,
                    path = "src/Frontend.elm",
                    rule = "NoDebug.Log",
                    message = NO_DEBUG_LOG_MESSAGE,
                    region = Region(Location(56, 13), Location(56, 22)),
                    formattedText = noDebugLogFormattedTextBbbb(),
                    formattedChunks = noDebugLogFormattedChunksBbbb()
                ),
                ElmReviewError(
                    suppressed = false,
                    path = "src/Frontend.elm",
                    rule = "NoDebug.Log",
                    message = NO_DEBUG_LOG_MESSAGE,
                    region = Region(Location(53, 17), Location(53, 26)),
                    formattedText = noDebugLogFormattedTextAaaa(),
                    formattedChunks = noDebugLogFormattedChunksAaaa()
                )
            ),
            reader.readErrorReport()
        )
    }

    @Test
    fun `parses type review-errors, one suppressed`() {
        @Language("JSON")
        val json = """
        {
          "type": "review-errors",
          "errors": [
            {
              "path": "src/Frontend.elm",
              "errors": [
                {
                  "rule": "NoDebug.Log",
                  "message": "Remove the use of `Debug.log` before shipping to production",
                  "ruleLink": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log",
                  "details": [
                    "`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "region": {
                    "start": {
                      "line": 56,
                      "column": 13
                    },
                    "end": {
                      "line": 56,
                      "column": 22
                    }
                  },
                  "fix": [
                    {
                      "range": {
                        "start": {
                          "line": 56,
                          "column": 13
                        },
                        "end": {
                          "line": 56,
                          "column": 30
                        }
                      },
                      "string": ""
                    }
                  ],
                  "formatted": [
                    {
                      "string": "(fix) ",
                      "color": "#33BBC8"
                    },
                    {
                      "string": "NoDebug.Log",
                      "color": "#FF0000",
                      "href": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log"
                    },
                    ": Remove the use of `Debug.log` before shipping to production\n\n55|         NoOpFrontendMsg ->\n56|             Debug.log \"BBBB\" ( model, Cmd.none )\n                ",
                    {
                      "string": "^^^^^^^^^",
                      "color": "#FF0000"
                    },
                    "\n\n`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "suppressed": true,
                  "originallySuppressed": false
                },
                {
                  "rule": "NoDebug.Log",
                  "message": "Remove the use of `Debug.log` before shipping to production",
                  "ruleLink": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log",
                  "details": [
                    "`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "region": {
                    "start": {
                      "line": 53,
                      "column": 17
                    },
                    "end": {
                      "line": 53,
                      "column": 26
                    }
                  },
                  "fix": [
                    {
                      "range": {
                        "start": {
                          "line": 53,
                          "column": 17
                        },
                        "end": {
                          "line": 53,
                          "column": 34
                        }
                      },
                      "string": ""
                    }
                  ],
                  "formatted": [
                    {
                      "string": "(fix) ",
                      "color": "#33BBC8"
                    },
                    {
                      "string": "NoDebug.Log",
                      "color": "#FF0000",
                      "href": "https://package.elm-lang.org/packages/jfmengels/elm-review-debug/1.0.6/NoDebug-Log"
                    },
                    ": Remove the use of `Debug.log` before shipping to production\n\n52|         UrlChanged url ->\n53|                 Debug.log \"AAAA\" ( model, Cmd.none )\n                    ",
                    {
                      "string": "^^^^^^^^^",
                      "color": "#FF0000"
                    },
                    "\n\n`Debug.log` is useful when developing, but is not meant to be shipped to production or published in a package. I suggest removing its use before committing and attempting to push to production."
                  ],
                  "suppressed": false,
                  "originallySuppressed": false
                }
              ]
            }
          ]
        }""".trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT

        assertEquals(
            listOf(
                ElmReviewError(
                    suppressed = true,
                    path = "src/Frontend.elm",
                    rule = "NoDebug.Log",
                    message = NO_DEBUG_LOG_MESSAGE,
                    region = Region(Location(56, 13), Location(56, 22)),
                    formattedText = noDebugLogFormattedTextBbbb(),
                    formattedChunks = noDebugLogFormattedChunksBbbb()
                ),
                ElmReviewError(
                    suppressed = false,
                    path = "src/Frontend.elm",
                    rule = "NoDebug.Log",
                    message = NO_DEBUG_LOG_MESSAGE,
                    region = Region(Location(53, 17), Location(53, 26)),
                    formattedText = noDebugLogFormattedTextAaaa(),
                    formattedChunks = noDebugLogFormattedChunksAaaa()
                )
            ),
            reader.readErrorReport()
        )
    }

    @Test
    fun `parses type error`() {
        @Language("JSON")
        val json = """
{
  "type": "error",
  "title": "INCORRECT CONFIGURATION",
  "path": "/home/jw/LamderaProjects/test/elm.json",
  "message": [
    "I could not find a review configuration. I was expecting to find an elm.json file and a ReviewConfig.elm file in /home/jw/LamderaProjects/test/review/.\n\nI can help set you up with an initial configuration if you run elm-review init."
  ]
}
        """.trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT
        val report = reader.readErrorReport()
        assertEquals(
            listOf(
                ElmReviewError(
                    path = "/home/jw/LamderaProjects/test/elm.json",
                    rule = "INCORRECT CONFIGURATION",
                    message = "I could not find a review configuration. I was expecting to find an elm.json file and a ReviewConfig.elm file in /home/jw/LamderaProjects/test/review/.\n\nI can help set you up with an initial configuration if you run elm-review init.",
                    region = null,
                    formattedText = null
                )
            ),
            report
        )
    }

    @Test
    fun `parses type compile-errors`() {
        @Language("JSON")
        val json = """{
  "type": "compile-errors",
  "errors": [
    {
      "path": "/home/jw/LamderaProjects/test/review/src/ReviewConfig.elm",
      "name": "ReviewConfig",
      "problems": [
        {
          "title": "UNFINISHED IMPORT",
          "region": {
            "start": {
              "line": 23,
              "column": 9
            },
            "end": {
              "line": 23,
              "column": 9
            }
          },
          "message": [
            "I am partway through parsing an import, but I got stuck here:\n\n23| --     ]\n            ",
            {
              "bold": false,
              "underline": false,
              "color": "RED",
              "string": "^"
            },
            "\nHere are some examples of valid `import` declarations:\n\n    ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "import"
            },
            " Html\n    ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "import"
            },
            " Html ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "as"
            },
            " H\n    ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "import"
            },
            " Html ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "as"
            },
            " H ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "exposing"
            },
            " (..)\n    ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "import"
            },
            " Html ",
            {
              "bold": false,
              "underline": false,
              "color": "CYAN",
              "string": "exposing"
            },
            " (Html, div, text)\n\nYou are probably trying to import a different module, but try to make it look\nlike one of these examples!\n\nRead <https://elm-lang.org/0.19.1/imports> to learn more."
          ]
        }
      ]
    }
  ]
}""".trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT
        val report = reader.readErrorReport()
        assertEquals(
            listOf(
                ElmReviewError(
                    path = "/home/jw/LamderaProjects/test/review/src/ReviewConfig.elm",
                    rule = "UNFINISHED IMPORT",
                    message = null,
                    region = null,
                    formattedText = null
                )
            ),
            report
        )
    }

    @Test
    fun `parses review-errors with cliVersion metadata`() {
        @Language("JSON")
        val json = """
{
  "type": "review-errors",
  "cliVersion": "2.13.0",
  "errors": [
    {
      "path": "src/Main.elm",
      "errors": [
        {
          "rule": "NoUnused.Variables",
          "message": "Unused variable `x`",
          "region": {
            "start": { "line": 1, "column": 1 },
            "end": { "line": 1, "column": 2 }
          },
          "formatted": ["Unused variable `x`"],
          "suppressed": false
        }
      ]
    }
  ]
}
        """.trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT
        val report = reader.readErrorReport()

        TestCase.assertEquals(1, report.size)
        TestCase.assertEquals("src/Main.elm", report[0].path)
        TestCase.assertEquals("NoUnused.Variables", report[0].rule)
        TestCase.assertEquals("Unused variable `x`", report[0].message)
    }

    @Test
    fun `parses top-level error object without type`() {
        @Language("JSON")
        val json = """
{
  "cliVersion": "2.13.0",
  "title": "INCORRECT CONFIGURATION",
  "path": "/tmp/elm.json",
  "message": "Something went wrong"
}
        """.trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT
        val report = reader.readErrorReport()

        assertEquals(
            listOf(
                ElmReviewError(
                    path = "/tmp/elm.json",
                    rule = "INCORRECT CONFIGURATION",
                    message = "Something went wrong",
                    region = null,
                    formattedText = null
                )
            ),
            report
        )
    }

    @Test
    fun `parses top-level error message array without type`() {
        @Language("JSON")
        val json = """
{
  "cliVersion": "2.13.0",
  "title": "ERROR",
  "path": "/tmp/review/ReviewConfig.elm",
  "message": ["Line one", "Line two"]
}
        """.trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT
        val report = reader.readErrorReport()

        TestCase.assertEquals(1, report.size)
        TestCase.assertEquals("ERROR", report[0].rule)
        TestCase.assertEquals("/tmp/review/ReviewConfig.elm", report[0].path)
        TestCase.assertEquals("Line oneLine two", report[0].message)
    }

    @Test
    fun `ignores unknown type with errors payload`() {
        @Language("JSON")
        val json = """
{
  "type": "future-errors",
  "errors": [
    {
      "path": "src/Main.elm",
      "errors": [
        {
          "rule": "NoUnused.Variables",
          "message": "Unused variable `x`"
        }
      ]
    }
  ]
}
        """.trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT

        assertEquals(emptyList<ElmReviewError>(), reader.readErrorReport())
    }

    @Test
    fun `parses location with extra properties`() {
        @Language("JSON")
        val json = """
{
  "type": "review-errors",
  "errors": [
    {
      "path": "src/Main.elm",
      "errors": [
        {
          "rule": "NoUnused.Variables",
          "message": "Unused variable `x`",
          "region": {
            "start": { "line": 1, "column": 1, "offset": 0 },
            "end": { "line": 1, "column": 2 }
          }
        }
      ]
    }
  ]
}
        """.trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT
        val report = reader.readErrorReport()

        assertEquals(1, report.size)
        assertEquals(Region(Location(1, 1), Location(1, 2)), report[0].region)
    }

    // TODO: complete this test, then add @Test annotation
    fun `parses type 'compile-errors' with errors array`() {
        @Language("JSON")
        val json = """
  [{
    "type": "compile-errors",
    "errors": [
      {
        "path": "/home/jw/LamderaProjects/test/review/src/ReviewConfig.elm",
        "name": "ReviewConfig",
        "problems": [
          {
            "title": "WEIRD DECLARATION",
            "region": {
              "start": {
                "line": 25,
                "column": 1
              },
              "end": {
                "line": 25,
                "column": 1
              }
            },
            "message": [
              "I am trying to parse a declaration, but I am getting stuck here:\n\n25| \n    ",
              {
                "bold": false,
                "underline": false,
                "color": "RED",
                "string": "^"
              },
              "\nWhen a line has no spaces at the beginning, I expect it to be a declaration like\none of these:\n\n    greet : String -> String\n    greet name =\n      ",
              {
                "bold": false,
                "underline": false,
                "color": "yellow",
                "string": "\"Hello \""
              },
              " ++ name ++ ",
              {
                "bold": false,
                "underline": false,
                "color": "yellow",
                "string": "\"!\""
              },
              "\n    \n    ",
              {
                "bold": false,
                "underline": false,
                "color": "CYAN",
                "string": "type"
              },
              " User = Anonymous | LoggedIn String\n\nTry to make your declaration look like one of those? Or if this is not supposed\nto be a declaration, try adding some spaces before it?"
            ]
          }
        ]
      }
    ]
  }
]""".trimIndent()

        val reader = JsonReader(json.byteInputStream().bufferedReader())
        reader.strictness = Strictness.LENIENT
        val report = reader.readErrorReport()
        assertEquals(
            emptyList<ElmReviewError>(),
            report
        )
    }

    private fun noDebugLogFormattedTextBbbb(): String =
        """
        (fix) NoDebug.Log: $NO_DEBUG_LOG_MESSAGE

        55|         NoOpFrontendMsg ->
        56|             Debug.log "BBBB" ( model, Cmd.none )
                        ^^^^^^^^^

        $NO_DEBUG_LOG_DETAILS
        """.trimIndent()

    private fun noDebugLogFormattedTextAaaa(): String =
        """
        (fix) NoDebug.Log: $NO_DEBUG_LOG_MESSAGE

        52|         UrlChanged url ->
        53|                 Debug.log "AAAA" ( model, Cmd.none )
                            ^^^^^^^^^

        $NO_DEBUG_LOG_DETAILS
        """.trimIndent()

    private fun noDebugLogFormattedChunksBbbb(): List<Chunk> =
        listOf(
            Chunk.Styled(string = "(fix) ", color = "#33BBC8"),
            Chunk.Styled(string = "NoDebug.Log", color = "#FF0000", href = NO_DEBUG_LOG_RULE_LINK),
            Chunk.Unstyled(
                str = ": $NO_DEBUG_LOG_MESSAGE\n\n55|         NoOpFrontendMsg ->\n56|             Debug.log \"BBBB\" ( model, Cmd.none )\n                "
            ),
            Chunk.Styled(string = "^^^^^^^^^", color = "#FF0000"),
            Chunk.Unstyled(str = "\n\n$NO_DEBUG_LOG_DETAILS")
        )

    private fun noDebugLogFormattedChunksAaaa(): List<Chunk> =
        listOf(
            Chunk.Styled(string = "(fix) ", color = "#33BBC8"),
            Chunk.Styled(string = "NoDebug.Log", color = "#FF0000", href = NO_DEBUG_LOG_RULE_LINK),
            Chunk.Unstyled(
                str = ": $NO_DEBUG_LOG_MESSAGE\n\n52|         UrlChanged url ->\n53|                 Debug.log \"AAAA\" ( model, Cmd.none )\n                    "
            ),
            Chunk.Styled(string = "^^^^^^^^^", color = "#FF0000"),
            Chunk.Unstyled(str = "\n\n$NO_DEBUG_LOG_DETAILS")
        )
}
