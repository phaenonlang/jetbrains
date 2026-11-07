# Changelog

From 0.2.1 on, release-please writes each entry from the Conventional Commits merged since the
previous release (`release-please-config.json`); the entry is the Marketplace's change notes for
that version. The first release was written by hand.

## [0.3.0](https://github.com/phaenonlang/jetbrains/compare/v0.2.1...v0.3.0) (2026-11-07)


### ⚠ BREAKING CHANGES

* PhpStorm 2025.1 and 2025.2 are no longer supported.

### Features

* PhpStorm 2025.3 is the floor ([6480d52](https://github.com/phaenonlang/jetbrains/commit/6480d52b1aefc862c1d693ef0d2dce3b2d2f36b8))


### Bug Fixes

* a closure-valued default is not one that "must be declared static" ([1b271b5](https://github.com/phaenonlang/jetbrains/commit/1b271b55265db2d1ac1c51a99ba885bcfbeba110))

## [0.2.1](https://github.com/phaenonlang/jetbrains/compare/v0.2.0...v0.2.1) (2026-11-07)


### Bug Fixes

* PhpStorm 2026.3 drops a bundle key and deprecates the PATH lookup ([7d5631a](https://github.com/phaenonlang/jetbrains/commit/7d5631a96c8378220f106beb65dc59234e523f6e))
* PhpStorm 2026.3 drops a bundle key and deprecates the PATH lookup ([ab942c0](https://github.com/phaenonlang/jetbrains/commit/ab942c08ece74d8218cccafd7bbeb626c0250d91))

## [0.2.0](https://github.com/phaenonlang/jetbrains/releases/tag/v0.2.0) (2026-11-06)

The first release, for PhpStorm 2025.1 and newer, with the compiler's 0.2.0.

A `.phn` file is PHP to PhpStorm, so its lexer, parser, indexes and inspections run on it.
Phaenon's syntax (generics, `list<T>`, `map<K, V>`, tuples, `class<T>`, closure types, expression
bodies and `throws`, `concurrently` and `spawn`, PHP 8.6's partial application) is erased on the
way to the parser and its types are given back to completion, navigation, quick documentation and
the inspections. In the commercial IDEs the compiler's diagnostics come from `phn lsp`, which also
builds on save; Reformat Code writes Phaenon's layout through `phn fmt`; and the line map every
compiled file carries takes Xdebug's breakpoints, frames and steps, and every position in compiled
code a console or a test run shows, to the `.phn` line.
