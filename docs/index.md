This plugin provides support for the [Elm programming language](https://elm-lang.org). This plugin is developed
by the [Elm Tooling community](https://github.com/elm-tooling/intellij-elm/).

## Using the plugin

Once the plugin is installed it is advised to double-check all CLI tools are found by going to `Settings` -> `Languages & Frameworks` -> `Elm` and see the CLI tools.

After installing the plugin, restart the IDE and then [open your existing Elm project](existing-project.md) or [create a new project](new-project.md).

## Install

You may want to have some CLI tools --`elm` (the Elm compiler), [`elm-test`](features/elm-test.md), [`elm-format`](features/elm-format.md),
[`elm-review`](features/elm-review.md) and [`lamdera`](features/lamdera.md)-- installed for certain features of this plugin to work.

You can install these globally with:

```bash
sudo npm install -g elm elm-test elm-format elm-review lamdera
```

**NOTE**: if you have [node](https://nodejs.org) installed using [nvm](https://github.com/nvm-sh/nvm), make sure to read [our NVM setup guide](nvm.md).

To install the plugin itself first make sure to uninstall all other Elm plugins you may have installed (this requires a restart of the IDE).
Some have reported that having two Elm plugins installed results in the IDE not starting but showing a seemingly unrelated error
(if you have this problem, there are [ways to fix it](https://intellij-support.jetbrains.com/hc/en-us/community/posts/360000524244-Disable-Uninstall-plugin-without-launching-Idea)).

From within a JetBrains IDE, go to `Settings` -> `Plugins` -> `Marketplace` and search for "Elm Language". If you get multiple hits look
for the most recently updated version, since there are still older versions of the plugin available. Alternatively you can install it 
manually by downloading a [release](https://github.com/elm-tooling/intellij-elm/releases) (or downloading the source and building it yourself) and installing it with 
`Settings` -> `Plugins` -> `⚙️ (gear icon)` -> `Install plugin from disk...`

## Features

* [Live error checking](features/live-error-checking.md)
* [Generate JSON encoder and decoder functions](features/generate-function-json.md)
* [Rename refactoring](features/rename-refactoring.md)
* [Lamdera support](features/lamdera.md)
* [Support for `elm-review`](features/elm-review.md)
* [Type inference and type checking](features/type-inference.md)
* [Find usages](features/find-usages.md)
* [Run tests](features/elm-test.md) (elm-test)
* [Reformat code using `elm-format`](features/elm-format.md) (elm-format)
* [Cleanup unused imports](features/unused-imports.md)
* [Detect unused code](features/unused-code.md)
* ['Add Import' quick fix for unresolved references](features/add-imports.md)
* [Quick Docs](features/quick-docs.md)
* [Structure view and quick navigation](features/structure-view.md)
* [WebGL/GLSL support](features/webgl.md)
* [Code folding](features/code-folding.md)
* [Manage exposing lists](features/exposure.md)

Want to see it in action? This [10 minute video](https://www.youtube.com/watch?v=CC2TdNuZztI) demonstrates many of the features and how they work together.

## Contributing

Yes, please! See [our guide](/docs/contributing.md) on this topic.

## Development and Building




