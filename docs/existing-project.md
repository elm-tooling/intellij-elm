# Open Existing Project

Already have some Elm code that you want to edit in IntelliJ? First, make sure that you have the Elm plugin installed. When you open IntelliJ, you'll see a launch screen.

You might be tempted to click "Import Project" but there's a simpler way. Instead, click the "Open" button and select your existing project directory.

There are a few essential settings for the plugin:

- Enabled elm.json files.
- Build targets.
- Paths to `elm`, `elm-test`, `elm-review` and other tools.

You can edit all those things by opening Settings > Languages & Frameworks > Elm. Some of it is auto-detected, so if your project isn’t very complicated you might not need to do anything more!

## Enabled elm.json files

In the settings, all elm.json files intellij-elm could find in your project are shown. One of them has been checked (enabled) by default (the one closest to the root, basically, as a best-effort guess). If you have more `elm.json` files, you need to enable them yourself. All of them aren’t enabled by default, because you might also have some that you _don’t_ care about, such as one from a vendored package.

Whenever you open an Elm file, intellij-elm checks which enabled elm.json file it belongs to, so it knows how to resolve imports. Without this the plugin can’t do much. So this whole deal with “enabled elm.json” files is pretty important for the plugin to work properly.

If the file does not belong to any enabled elm.json, a banner is shown about this, with a link to settings.

Note that every file can only belong to _one_ elm.json in intellij-elm. In reality, multiple elm.json files can have overlapping `"source-directories"`, but that is not something that the plugin supports. Supporting that would mean that go-to-definition could give multiple answers, and that calling a function could be both an error and not an error at the same time, depending on which elm.json you use for resolving imports. Sounds pretty complicated, right? In the case of overlapping `"source-directories"`, intellij-elm will pick the “closest” elm.json. (If all are at the same distance: An arbitrary one.) It’s better to just enable one of the elm.json files in the settings in this case.

## Build targets

Build targets are used in the Elm Compiler panel, which can also be triggered via the “Build Selected Elm Target” and “Build All Elm Targets” commands. A build target lets you invoke the Elm compiler (or another compiler of choice, such as Lamdera), and see all compile errors in a clickable tree view.

Build targets let you configure:

- A name, shown in the Elm Compiler panel.
- The type: Application or Package.
- The compiler: Elm or Lamdera (or some other supported compiler).
- Mode: Default, Debug, Optimize. If you configure Output (see below) to create a JavaScript output file (rather than just compiling for type errors) it can be handy to choose if you want the debugger enabled or optimize for production.
- Compiler path: Path to `elm`, for example. Usually autodetected. This lets you use different compilers for different build targets.
- Input Elm File / elm.json File: Path to the main Elm file (resulting in `elm make src/Main.elm` for example), or the elm.json file for a package (resulting in `elm make` (without arguments) being called at that elm.json). For Lamdera projects, make one target for `src/Frontend.elm` and one for `src/Backend.elm`.
- Output: Path to a JavaScript file to compile to. Leave blank to just type check and not create any JavaScript. (Not available for packages.)

When adding a new build target, you can choose from a list of suggestions based on what was automatically found in your project (to use as a starting point), or start from an empty state.

If you have tests, build targets are added for the tests automatically, shown at the bottom of the Elm Compiler panel, allowing you to find compilation errors in all your tests. There is also a button for _running_ the tests inside IntelliJ, as well as a “Run Selected Elm Test” command. Test failures are shown in a clickable tree view, and double-clicking a test attempts to take you to the code for the test. (There is currently no configuration for tests, which is why their build targets are automatic.)

## Paths to tools

Whenever intellij-elm needs to call an external tool – such as `elm`, `elm-test` and `elm-review` – it needs to know where those tools are installed. intellij-elm tries to autodetect this, but if it failed you can specify it yourself.
