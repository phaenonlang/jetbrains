# Changelog

From 0.2.1 on, release-please writes each entry from the Conventional Commits merged since the
previous release (`release-please-config.json`); the entry is the Marketplace's change notes for
that version. The first release was written by hand.

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
