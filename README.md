# Phaenon for JetBrains IDEs

PhpStorm support for [Phaenon](https://github.com/phaenonlang) source files (`.phn`), a typed dialect
of PHP that compiles to PHP.

A Phaenon file is PHP code from the first byte: no `<?php` open tag, no `?>`, no inline HTML, and
`declare(strict_types=1)` is implied. This plugin teaches PhpStorm that contract, so `.phn` files get
the **full PHP toolchain** rather than a syntax-colouring approximation of it:

- highlighting, code folding, brace matching, formatting
- completion, parameter info, quick documentation
- go to declaration / usages / implementations across `.phn` and `.php` files, in both directions
- refactorings (rename, move, extract, ...)
- every PHP inspection and intention, plus one Phaenon-specific error for stray `<?php` / `?>` tags

In IDEs without the PHP plugin (IntelliJ IDEA Community, WebStorm, ...) the plugin falls back to a
bundled TextMate grammar and provides highlighting only.

## How it works

PhpStorm's PHP lexer starts in HTML mode and only enters PHP mode after an open tag. Pointing `.phn`
at the PHP file type alone would therefore paint every Phaenon file as one big inline-HTML blob. The
plugin changes the lexer at the two places where PhpStorm picks one, and leaves everything else to
PhpStorm. A `.phn` file *is* a PHP file to the IDE: PHP file type, PHP language, PhpStorm's own
`PhpFileImpl`, PhpStorm's stubs and indices.

| Piece | Registration | What it does |
|---|---|---|
| `fileType name="PHP" extensions="phn"` | PHP file type | `.phn` joins the PHP file type. The bean has no `implementationClass`, which the platform reads as "add an extension to an existing type". |
| `PhaenonFileViewProviderFactory` | `lang.fileViewProviderFactory` for PHP, `order="first"` | Claims `.phn` files and hands every other PHP file to PhpStorm's own factory. |
| `PhaenonFileViewProvider` | created by the factory | A single-root PHP view provider that, right after creating the `PhpFileImpl`, sets its content element type to `PhaenonFileElementType`. That is the same hook PhpStorm's template view provider uses to give the HTML side of a `.php` file its own parser, and it happens before any AST exists. Single-rooted on purpose: PhpStorm's provider also hosts an HTML tree built from inline-HTML tokens, which for a Phaenon file would only yield spurious HTML inspections. |
| `PhaenonFileElementType` | root AST node of `.phn` files | An `IFileElementType` of the PHP language whose `parseContents` builds the `PsiBuilder` with PhpStorm's `PhpWithOptionalOpenTagLexer` (starts in scripting mode unless the first token is an open tag) and runs the stock PHP parser, the way PhpStorm parses its own code fragments. Everything below the root is ordinary PHP PSI, so the stub builder, indices, inspections and refactorings never notice. |
| `PhaenonBaseLexerProvider` | `com.jetbrains.php.baseLexerProvider` | Editor highlighting does not go through the parser: PhpStorm's editor highlighter is keyed by file type and builds its own lexer, asking this EP first. It gets the same open-tag-optional lexer, so what you see while typing matches the PSI. |
| `PhaenonPhpTagAnnotator` | `annotator` for PHP | Reports `<?php`, `<?=` and `?>` as errors in Phaenon files. |
| `PhaenonFileIconProvider` | `fileIconProvider` | Gives `.phn` files their own icon despite sharing the PHP file type. |
| `PhaenonTextMateBundleProvider` | `textmate.bundleProvider` | Registers `textmate/phaenon`, a grammar that includes `source.php` (the PHP *code* grammar, not the `text.html.php` wrapper). Only used when the PHP plugin is absent. |

Both plugin dependencies (`com.jetbrains.php`, `org.jetbrains.plugins.textmate`) are optional in
`plugin.xml`; the PHP part lives in `phaenon-php.xml`, the fallback in `phaenon-textmate.xml`.

Why not a Phaenon *language*? A PHP dialect (a `Language` with PHP as base) was the first attempt.
It works for completion and inspections through the base-language fallback, but PhpStorm's file
element type is bound to the PHP language and the platform asserts that a file's root element type
and its language agree, so a dialect needs its own file element type and its own stub registration
mirroring PHP's. Keeping the file in the PHP language and only swapping the root element type
removes that whole class of problems: nothing in PhpStorm can tell a `.phn` file apart from PHP
unless it looks at the root node's type.

## Install

Download `phaenon-jetbrains-<version>.zip` from the releases page (or build it, see below), then in
PhpStorm: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart. Any `.phn`
file opens as Phaenon.

Requires PhpStorm 2025.1 or newer (or another JetBrains IDE 2025.1+ with the PHP plugin for full
support).

## Build

```bash
./gradlew buildPlugin        # → build/distributions/phaenon-jetbrains-<version>.zip
./gradlew test               # runs against a headless PhpStorm
./gradlew verifyPlugin       # JetBrains Plugin Verifier against the targeted PhpStorm build
./gradlew runIde             # starts a sandboxed PhpStorm with the plugin installed
```

The first build downloads the PhpStorm distribution declared in `gradle.properties`
(`platformVersion`). A JDK 21 is required; Gradle provisions one if none is installed.

To also verify against a locally installed IDE:

```bash
./gradlew verifyPlugin -PlocalIdePath=/Applications/PhpStorm.app/Contents
```

## Tests

`src/test` runs the plugin inside the IntelliJ test framework with the real PHP plugin loaded. The
fixtures under `src/test/testData/reflector` are real Phaenon sources from the Tequila platform. The
tests check that `.phn` files are PHP files with a Phaenon root, that every fixture lexes as PHP code
(no inline-HTML token), parses without errors and builds stubs consistent with its text, that the
editor highlighter starts in code mode, that completion offers PHP members, that classes declared in
`.phn` files resolve from both `.phn` and `.php` files, that a stray `<?php` is reported, that
reformatting leaves a well-formatted file untouched, and that plain `.php` files still start in HTML
mode.

## Limitations

- **Phaenon-only syntax is not understood yet.** Today a `.phn` file is plain PHP 8.5 without the
  open tag, and that is all the plugin covers. Generics (`class Foo<T>`, `list<int>`), `is` / `as`
  and `type` aliases from later Phaenon milestones will show up as PHP syntax errors until the
  plugin grows its own parser extensions.
- The implied `declare(strict_types=1)` is not modelled: inspections that depend on strict types
  behave as they would in a PHP file without the declaration.
- A few PHP features look at the *file type* and will treat `.phn` as a regular PHP file (for
  example the "PHP file" run configuration will try to run it with the PHP interpreter, which is
  not meaningful before compilation).
- Three spots in PhpStorm compare the root node against PHP's own file element type by identity
  (top-level folding, top-level indentation, dead-code entry points). Reformatting a well-formed
  `.phn` file is covered by a test and comes out unchanged, but a corner case there would show up
  as a formatting or folding difference, not as broken analysis.
- The TextMate fallback is highlighting only, and it needs the IDE's own PHP TextMate bundle
  (bundled with every JetBrains IDE) for `source.php`.

## VS Code

`textmate/phaenon` is laid out as a VS Code extension (`package.json` with `contributes.languages`
and `contributes.grammars`). Copying that directory into `~/.vscode/extensions/phaenon` gives `.phn`
files PHP highlighting in VS Code.

## License

MIT, see `LICENSE`.
