#!/usr/bin/env bash
#
# Verifies that the dummy Elm projects in this directory install every Elm
# package + version that the test fixtures under src/ depend on. If a fixture
# starts using a package/version that neither dummy project installs, the CI
# `~/.elm` pre-warm would be incomplete and tests could flake, so this fails loudly.
#
# See README.md for more details.

set -euo pipefail

cd "$(dirname "$0")/.."

# Packages the test fixtures need in ~/.elm: every exact "author/pkg": "x.y.z"
# pin anywhere under src/, minus lamdera/* (the Lamdera fixture is parse-only; no
# test compiles it, so those packages never end up in ~/.elm).
needed="$(
    grep -rhoE '"[A-Za-z0-9_-]+/[A-Za-z0-9_-]+": "[0-9]+\.[0-9]+\.[0-9]+"' src/ \
        | sed -E 's/"([^"]+)": "([^"]+)"/\1 \2/' \
        | grep -v '^lamdera/' \
        | sort -u
)"

# Packages the dummy projects install (direct + indirect, both dep sections).
# One elm.json per bucket subdirectory; add more buckets by adding more subdirs.
provided="$(
    jq -r '( (.dependencies.direct // {}) + (.dependencies.indirect // {})) | to_entries[] | "\(.key) \(.value)"' \
        install-packages-for-ci/*/elm.json \
        | sort -u
)"

missing="$(comm -23 <(printf '%s\n' "$needed") <(printf '%s\n' "$provided"))"

if [ -n "$missing" ]; then
    echo "ERROR: the install-packages-for-ci/*/elm.json manifests are out of date."
    echo
    echo "These packages are used by test fixtures under src/ but are not installed"
    echo "by any dummy project, so CI would not pre-warm them into ~/.elm:"
    echo
    printf '%s\n' "$missing" | sed 's/^/  - /'
    echo
    echo "Add each missing \"author/pkg\": \"version\" to the 'direct' section of one"
    echo "install-packages-for-ci/*/elm.json (a new bucket subdir if versions conflict;"
    echo "see README.md)."
    exit 1
fi

echo "OK: dummy projects cover all $(printf '%s\n' "$needed" | grep -c . || true) fixture packages."
