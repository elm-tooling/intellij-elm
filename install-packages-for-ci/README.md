# Pre-warming `~/.elm` for CI

Many tests read the Elm compiler's global package cache (`~/.elm/<version>/packages/`)
but do not download into it themselves — that only happens as a side effect of the few
tests that shell out to the toolchain (`StdlibInstallHelper`, `ElmBuildActionTest`,
`ElmTestCLITest`, `ElmReviewCLITest`). If one of those downloads flakes over the network,
or a reader test runs before its installer, hundreds of unrelated tests fail with
`ProjectLoadException` / `IllegalArgumentException`.

The fix: populate `~/.elm` once, up front, in CI, then let the Actions cache keep it
warm. These two dummy projects exist only to drive that population.

## How it works in CI (`.github/workflows/build.yml`, `test` job)

1. `actions/cache` restores `~/.elm`, keyed on the hash of every `*/elm.json`.
2. `verify.sh` fails the build if a `src/` fixture uses a package/version that no
   dummy project installs (so the cache can never silently go stale).
3. `elm make ../Main.elm` is run in each bucket subdirectory (with retry). Elm installs
   everything listed in each `elm.json` before compiling, so this fills `~/.elm` for certain.

## Why more than one project?

Some packages are pinned at **multiple versions** across the fixtures (`elm/core` 1.0.0 &
1.0.5, `elm/json` 1.0.0 & 1.1.4, `elm/parser` 1.0.0 & 1.1.0). A single `elm.json` can only
hold one version of each package, so each bucket subdirectory holds one non-conflicting set:
today `a/` has the older versions and `b/` has everything else. If a fixture ever introduces
a *third* version of some package, just add a `c/` (etc.) — no code changes needed.

## Updating when a fixture changes

If `verify.sh` fails, it prints the missing `"author/pkg": "version"` entries. Add each to
the `dependencies.direct` of a bucket that doesn't already pin that package at a different
version (or create a new bucket subdirectory with its own `elm.json`), then
run `elm make ../Main.elm` locally in that folder to confirm the solution is valid. Bumping
the compiler? Update `elm-version` in every bucket to match; that also rotates the cache key.
`lamdera/*` packages are intentionally excluded — no test compiles the Lamdera fixture.
