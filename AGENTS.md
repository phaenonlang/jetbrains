# Phaenon for JetBrains IDEs — agent guide

The PhpStorm plugin for `.phn` files (Kotlin, IntelliJ Platform Gradle Plugin, PhpStorm 2026.1.4+).
It makes PhpStorm read a `.phn` file as PHP code without the open tag, erases Phaenon's additions to
PHP on the way to PhpStorm's parser and gives the erased declarations their types back (`list<User>`
as `User[]`, `class<T>` as `class-string<Bound>`, closure signatures); the README explains how and
why it is not a separate language. The map of every Phaenon repo, the working method and
the commit rules are in the compiler repo: read `../phaenon/AGENTS.md` first.

```sh
./gradlew test           # inside a headless PhpStorm; the first run downloads it
./gradlew buildPlugin    # → build/distributions/phaenon-jetbrains-<version>.zip
./gradlew verifyPlugin   # JetBrains Plugin Verifier across the declared range, then verifyInternalApi
./gradlew runIde         # sandboxed PhpStorm with the plugin
./gradlew test -PtestIdePath=/Applications/PhpStorm.app/Contents   # build and test against an installed, newer IDE
./gradlew testInIde -PtestInIdePath=/path/to/PhpStorm/Contents     # the suite inside another IDE, the plugin as platformVersion builds it

PHAENON_TREES=/path/to/a/phaenon/project:/path/to/another \
  ./gradlew test --tests '*RealTree*'   # parse every .phn of any Phaenon trees named; skipped when unset
PHAENON_PHP_TREES=/path/to/a/php/project/vendor \
  ./gradlew test --tests '*TreeEquality*'   # the PHP files there (open tag first, no other tag) as .phn: their trees must be PhpStorm's; skipped when unset
PHAENON_XDEBUG=/path/to/xdebug.so ./gradlew test --tests '*Xdebug*'   # the line map against a real Xdebug over DBGp; skipped when unset
PHAENON_PHN=../phaenon/bin/phn ./gradlew test --tests '*PhaenonLayoutTest*'   # Reformat Code with phn fmt --stdin, on the compiler's format goldens
```

- JDK 21; Gradle provisions one if none is installed. `./gradlew test` is a no-op when nothing
  changed (build cache): `--rerun` forces it. The results are in `build/test-results/test/*.xml`.
- `src/main/kotlin`: `PhaenonLexer` (erasure, and the empty `function` of a method without a
  keyword, D27) and `PhaenonPsiBuilder` (no lazy bodies, that token read as `function`) feed the
  parser; `PhaenonErasedTypes`, `PhaenonTypeSyntax` and `PhaenonTypeText` read the erased text back
  for `PhaenonTypeProvider` (a type parameter as its bound, `class<T>` as `class-string<Bound>`,
  closure types with PhpStorm's signature encoding); `PhaenonErasure` and
  `PhaenonHierarchyInspection` judge overrides with an erased type on either side, whose PhpStorm
  finding the highlight filter drops (fixtures `testData/hierarchy/`); `PhaenonExcludePolicy` keeps the output directories of `phaenon.json`
  out of the index, and `PhaenonGotoDeclarationHandler` / `PhaenonHighlightFilter` prefer a `.phn`
  declaration over its compiled copy when both are indexed (fixtures `testData/generated/`);
  an erased construct is a `PhaenonErasedLeaf` (token `PhaenonTokenTypes.ERASED`, a comment to
  the parser through `PhaenonParserDefinition`) whose class names are references
  (`PhaenonErasedReferenceContributor`, `PhaenonErasedNames`; fixtures `testData/erased/`),
  completed by `PhaenonErasedCompletionContributor` and left alone by the spell-checker
  (`PhaenonSpellcheckingStrategy`); `PhaenonFormattingModelBuilder` runs PhpStorm's PHP formatter on
  `.phn` files and decides the spacing next to erased constructs; `PhaenonDocCommentEnterHandler`,
  `PhaenonOverrides` (Implement / Override Methods and override completion) and
  `PhaenonPipeConversionIntention` make PhpStorm's generated code Phaenon's (fixtures
  `testData/formatting/`, `testData/implement/`); `PhaenonDocumentationProvider` and
  `PhaenonParameterInfoHandler` print Phaenon's types as written in quick documentation, the
  Ctrl+hover hint and parameter info (`PhaenonTypePresentation` turns a `PhpType` back into
  Phaenon's spelling; fixtures `testData/docs/`, `testData/classstring/`); PHP 8.6 placeholders
  are identifiers to the parser (`PhaenonLexer`) and typed as closures (`PhaenonPartialApplication`)
  up to 2026.2, and PHP's own `?` from 2026.3 on, whose parser makes placeholders and types partial
  application itself; PhpStorm's findings on 8.6 code, 2026.3's language-level ones below 8.6
  included, are dropped by target (`PhaenonTarget` reads `phaenon.json` and composer.json; fixtures
  `testData/php86/`, tested at the 8.5 level and the IDE's highest); `concurrently` and `spawn` are
  names like any other
  since D25 withdrew the concurrency syntax (`PhaenonUnreservedWordsTest`); D18's `throws` clause
  is erased (`PhaenonThrows` answers the exception findings it covers) and an expression body is
  the block it compiles to, opened by the lexer and closed by `PhaenonPsiBuilder`; `.phn` is
  formatted in the canonical layout, the braces of functions and methods on their line, of
  class-likes on a line of their own, and an empty body (a function,
  method or class-like with nothing between its braces) as `{}` (fixtures `testData/d18/`,
  `PhaenonFormattingTest`);
  D19's line map (design/line-map.md) is read by `PhaenonLineMap` / `PhaenonLineMaps` (tested
  against `src/test/resources/linemap/vectors.json`, a verbatim copy of the compiler's
  `internal/linemap/testdata/vectors.json` that its freshness test compares: refresh by copying,
  never edit), and positions in compiled files reach the `.phn` through `PhaenonCompiledLinkFilter`
  (console links), `PhaenonCompiledEditorProvider` (any navigation to a position in a compiled file)
  and, in the debugger, `PhaenonDebugPathMapper` (PhpStorm's template debugging, the mechanism of Twig
  and Blade; frames forward, breakpoints reverse, gated to the breakpoint handler's question about a
  `.phn`) with `PhaenonSourceStepping` (a step that stays on a source line through holds is
  repeated); fixtures `testData/linemap/` are compiled by hand as the spec lays them out, so keep
  them in step when the compiled layout changes;
  the compiler's language server, `phn lsp`, gives the checker's diagnostics and build on save
  (`PhaenonLsp.kt`: `PhaenonLspIntegrationProvider`, `PhaenonLspClientDescriptor` asking for
  diagnostics only, `PhaenonLspClient` for the server's `phaenon/build`, `PhaenonBuildWidget` in
  the status bar; registered by `phaenon-lsp.xml`, optional on `com.intellij.modules.ultimate`);
  `PhaenonCompiler` finds the project's `phn` and runs `phn fmt --stdin`, which
  `PhaenonPreFormatProcessor` / `PhaenonPostFormatProcessor` run around PhpStorm's formatter on a
  reformat of the whole file; `PhaenonIndentOptionsProvider` keeps a `.phn` indented with spaces
  (D21); `PhaenonSettings` (Settings | Languages & Frameworks | Phaenon) holds the binary and build
  on save (`PhaenonLspTest`, `PhaenonLayoutTest`, the latter with `PHAENON_PHN`).
- `textmate/phaenon/` is the TextMate bundle for IDEs without the PHP plugin
  (`PhaenonTextMateBundleProvider`); its grammar and language configuration are verbatim copies of
  `../vscode`'s, which that repository's tests compare: refresh by copying, never edit.
- `src/test/testData/reflector/*.phn` are copies of the `tequila/reflection` dogfood sources;
  `syntax/Constructs.phn` uses every Phaenon construct, `methods/Shapes.phn` every shape of a
  method without a keyword (D27); `types/` holds the erased-type fixtures,
  `closure/` the closure-type ones, `classstring/` the `class<T>` ones, `php86/` the PHP 8.6 ones, `imports/` the Optimize Imports ones (`X.phn` → `X.after.phn`).
  Tests set the IDE's highest PHP level: the test project defaults to PHP 5.6. It also lacks
  PhpStorm's bundled PHP stubs (they are indexed only for a project with a PHP module), so a test
  that needs `\Closure` and the like copies a stand-in (`closure/stubs/Closure.php`), or the
  bundled stub itself from the PHP plugin's resources (`stubs/Core/Core_c.php`, `PhaenonPhp86Test`).
- `PhaenonRealTreeTest` runs the plugin over whole trees (`PHAENON_TREES`, above) and reports
  per tree: files, files with syntax errors, errors, stub mismatches, files skipped for PHP syntax
  the IDE's level lacks. Run it after every lexer or builder change; a `.phn` the compiler parses
  (`../phaenon/bin/phn check`) and PhpStorm does not is a plugin bug unless plain PHP fails alike
  (the test checks that itself and lists such files instead of counting them).
  `PhaenonTreeEqualityTest` is the other half: PHP that reads the same with and without its open
  tag (`testData/trees`, and the files under `PHAENON_PHP_TREES`) must parse, as a `.phn`, into
  the tree PhpStorm builds of the `.php`, and its Phaenon file (`testData/trees/phaenon`: erasures
  and expression bodies in bodies in bodies) without an error and without a lazy node; the shapes
  of broken code known to differ are pinned in `testData/trees/differing`. `PhaenonPsiBuilder`
  parses a body by running PhpStorm's parser again on the builder between the body's braces, which
  rests on how that parser drives a builder and on no API (README § Internal API): this test is
  what fails when a PhpStorm build changes it, with `PhaenonD18Test`, `PhaenonMethodsTest` and
  `PhaenonSyntaxTest` for what only Phaenon has in a body. So run the whole suite with `testInIde`
  against every new PhpStorm line and EAP (those four for a quick look), and after every builder
  change. A change that
  alters what a `.phn` parses into also bumps `PhaenonFileElementType.PSI_VERSION`: the stubs of
  `.phn` files carry PhpStorm's stub version, and the bump is what re-indexes them after an update
  (README § How it works).
- Known limits (README § Limitations): `type` aliases are erased whole, type arguments of user
  generics are dropped, tuples type as element-array unions, a partial application is also its
  callee's result to PhpStorm; the implied `strict_types` is not modelled; the lexer, the editor
  colouring (`com.jetbrains.php.baseLexerProvider`) and debugging
  (`PhpTemplateLanguagePathMapper`, `com.intellij.php.debug.templateLanguage`) use internal API of
  the PHP plugin, each use named in `verifier/internal-api.txt` with its reason (README § Internal
  API; WI-86457 is the request to make the first two public): a new one fails `verifyPlugin` until
  it has a public replacement or a line there; the Frames list labels a compiled frame with the compiled name and line. Code a feature inserts is laid out
  by PhpStorm's formatter, as in PHP: insert it unformatted and let the reformatting that follows
  the command do it. A test of behaviour only a newer PhpStorm than the floor has returns early
  on the floor and runs with `-PtestIdePath`.
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `docs:`, … with a body that says
  what changed and why), with no attribution lines and no `Co-Authored-By`: the repository will be
  public.
