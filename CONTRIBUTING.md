# Contributing

This is the PhpStorm plugin for Phaenon's `.phn` files; the [README](README.md) explains how it
works, what it covers and its limits. The language is designed in
[phaenonlang/design](https://github.com/phaenonlang/design) (`language-overview.md` for the scope,
then each feature's document, whose decision sections win over the draft text above them), and its
compiler, `phn`, is [phaenonlang/phaenon](https://github.com/phaenonlang/phaenon). A change to
what Phaenon means starts there; the plugin follows the compiler.

## Build and test

JDK 21 (Gradle provisions one if none is installed); the first run downloads PhpStorm.

```sh
./gradlew test           # the suite, inside a headless PhpStorm
./gradlew verifyPlugin   # the JetBrains Plugin Verifier
./gradlew buildPlugin    # → build/distributions/phaenon-jetbrains-<version>.zip
./gradlew runIde         # a sandboxed PhpStorm with the plugin
```

The layout tests run the compiler's `phn fmt --stdin` and reformat its format goldens; without a
compiler they are skipped. With the compiler checked out beside this repository:

```sh
(cd ../phaenon && go build -o bin/phn ./cmd/phn)
PHAENON_PHN="$PWD/../phaenon/bin/phn" ./gradlew test
```

The README's [Build](README.md#build) and [Tests](README.md#tests) sections list the other
variables (a real Xdebug, whole Phaenon trees, a newer installed PhpStorm) and what each test
covers.

## Pull requests

- CI passes: `./gradlew test` with the compiler built from phaenonlang/phaenon's main, and
  `./gradlew verifyPlugin`.
- A change of behaviour comes with a test that fails without it, and its fixtures under
  `src/test/testData`. Fixtures are code that may be published under this repository's licence.
- The copies of other repositories' files are refreshed by copying, never edited:
  `src/test/resources/linemap/vectors.json` (the compiler's `internal/linemap/testdata/vectors.json`)
  and the grammar and language configuration in `textmate/phaenon/` (phaenonlang/vscode's).
- The title and every commit follow [Conventional Commits](https://www.conventionalcommits.org):
  `type(scope)!: subject`, the type one of `feat` `fix` `docs` `style` `refactor` `perf` `test`
  `build` `ci` `chore` `revert`, and a body that says what changed and why.
- Pull requests are squash-merged, so the title becomes the commit on `main` that the next
  release is read from. Its release impact equals the highest of the commits': major for `!` or a
  `BREAKING CHANGE:` footer, minor for `feat`, patch for `fix` and `revert`, none for the rest. The
  PR semantics check holds every pull request to this (`.github/scripts/pr-semantics.sh`); merge
  commits are exempt, and `fixup!` / `squash!` commits are squashed before the merge.
- The base is `main`, never the branch of another open pull request: CI and the PR semantics
  check run only on pull requests into `main`, and GitHub closes a stacked pull request when the
  branch under it is deleted. A change that depends on an unmerged one goes into the same pull
  request, or waits for its merge.

## Releases

A release is cut by merging the release PR that release-please keeps open on `main`
(`chore(main): release X.Y.Z`): it bumps the version in `build.gradle.kts` and writes the
`CHANGELOG.md` entry from the merged commits. The merge tags `vX.Y.Z`, makes the GitHub release
with the zip and publishes the version to the JetBrains Marketplace, with that entry as its change
notes (`.github/workflows/release.yml`).

## Licence

Contributions are licensed under the repository's [MIT licence](LICENSE).
