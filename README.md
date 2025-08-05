Elm Plugin for JetBrains IDEs
=============================

<!-- Plugin description -->
Provides support for the [Elm programming language](https://elm-lang.org). This plugin is developed
by the [Elm Tooling community](https://github.com/elm-tooling/intellij-elm/).

Features:

- Code completion
- Go to declaration
- Go to symbol
- Find usages
- Type Inference and Type Checking
- Rename refactoring
- Introduce "variable" refactoring (let/in)
- Generate JSON encoders/decoders
- Generate type annotation for un-annotated function
- Graphical UI for running elm-test
- Re-format code using elm-format
- Detect unused code
- Detect and remove unused imports
- 'Add Import' quick fix for unresolved references
- Code folding
- Structure view
- Syntax highlighting
- WebGL/GLSL support
- Spell checking
- Lamdera platform support

<!-- Plugin description end -->

Should work on most, if not all, IntelliJ Platform IDEs: IDEA (Community and Ultimate), WebStorm, PyCharm, RubyMine and more. If not please raise an issue.


## History

The original repo was [klazuka/intellij-elm](https://github.com/klazuka/intellij-elm), created by [Keith Lazuka](https://github.com/klazuka) and maintained together with [AJ Alt](https://github.com/ajalt). When Keith and AJ were unable to keep working on the plugin, the repo was later transferred to [intellij-elm/intellij-elm](https://github.com/intellij-elm/intellij-elm), managed by [Cies Breijs](https://github.com/cies). After doing some incredible work on the plugin, Cies unfortunately disappeared from the community. Failing to reach him, the community forked the repo the [elm-tooling](https://github.com/elm-tooling) organization (which is also home to the [Elm Language Server](https://github.com/elm-tooling/elm-language-server)). Then intention of this new home for the plugin is to not be tied to a single person and pass on the rights and maintenance responsibilities as contributors’ lives change.


## Install

You may want to have some CLI tools --`elm` (the Elm compiler), [`elm-test`](docs/elm-test.md), [`elm-format`](docs/features/elm-format.md),
[`elm-review`](docs/features/elm-review.md) and [`lamdera`](docs/features/lamdera.md)-- installed for certain features of this plugin to work.

You can install these globally with:

```bash
sudo npm install -g elm elm-test elm-format elm-review lamdera
```

**NOTE**: if you have [node](https://nodejs.org) installed using [nvm](https://github.com/nvm-sh/nvm), make sure to read [our NVM setup guide](docs/nvm.md).

To install the plugin itself first make sure to uninstall all other Elm plugins you may have installed (this requires a restart of the IDE).
Some have reported that having two Elm plugins installed results in the IDE not starting but showing a seemingly unrelated error
(if you have this problem, there are [ways to fix it](https://intellij-support.jetbrains.com/hc/en-us/community/posts/360000524244-Disable-Uninstall-plugin-without-launching-Idea)).

From within a JetBrains IDE, go to `Settings` -> `Plugins` -> `Marketplace` and search for "Elm Language". If you get multiple hits look 
for the most recently updated version, since there are still older versions of the plugin available. After installing the plugin, 
restart the IDE and then [open your existing Elm project](docs/existing-project.md) or [create a new project](docs/new-project.md).

Alternatively you can install it manually by downloading a [release](https://github.com/elm-tooling/intellij-elm/releases) (or downloading the source and building it yourself) and
installing it with `Settings` -> `Plugins` -> `⚙️ (gear icon)` -> `Install plugin from disk...`

Once the plugin is installed it is advised to double-check all CLI tools are found by going to
**Settings** -> **Languages & Frameworks** -> **Elm** and see the CLI tools. 


## Features

* [Live error checking](docs/features/live-error-checking.md)
* [Generate JSON encoder and decoder functions](docs/features/generate-function-json.md)
* [Rename refactoring](docs/features/rename-refactoring.md)
* [Lamdera support](docs/features/lamdera.md)
* [Support for `elm-review`](docs/features/elm-review.md)
* [Type inference and type checking](docs/features/type-inference.md)
* [Find usages](docs/features/find-usages.md)
* [Run tests](docs/features/elm-test.md) (elm-test)
* [Reformat code using `elm-fomrat`](docs/features/elm-format.md) (elm-format)
* [Cleanup unused imports](docs/features/unused-imports.md)
* [Detect unused code](docs/features/unused-code.md)
* ['Add Import' quick fix for unresolved references](docs/features/add-imports.md)
* [Quick Docs](docs/features/quick-docs.md)
* [Structure view and quick navigation](docs/features/structure-view.md)
* [WebGL/GLSL support](docs/features/webgl.md)
* [Code folding](docs/features/code-folding.md)
* [Manage exposing lists](docs/features/exposure.md)
* Detect and remove unused imports
* Go to symbol
* Go to declaration
* Syntax highlighting
* Spell checking
* and more...

Want to see it in action? This [10 minute video](https://www.youtube.com/watch?v=CC2TdNuZztI) demonstrates many of the features and how they work together.


## License

MIT licensed.


## Contributing

Yes, please! See [our guide](/docs/contributing.md) on this topic. 
