# Phaenon for JetBrains IDEs — agent guide

The PhpStorm plugin for `.phn` files (Kotlin, IntelliJ Platform Gradle Plugin, PhpStorm 2025.1+).
It makes PhpStorm read a `.phn` file as PHP code without the open tag, erases Phaenon's additions to
PHP on the way to PhpStorm's parser and gives the erased declarations their types back, so the
method syntax on `list`, `map`, `string` and `bytes` completes and resolves; the README explains
how and why it is not a separate language. The map of every Phaenon repo, the working method and
the commit rules are in the compiler repo: read `../phaenon/AGENTS.md` first.

```sh
./gradlew test           # inside a headless PhpStorm; the first run downloads it
./gradlew buildPlugin    # → build/distributions/phaenon-jetbrains-<version>.zip
./gradlew verifyPlugin   # JetBrains Plugin Verifier
./gradlew runIde         # sandboxed PhpStorm with the plugin
./gradlew test -PtestIdePath=/Applications/PhpStorm.app/Contents   # tests against an installed, newer IDE

PHAENON_TREES=~/labs/tequila/core:~/labs/tequila/mongodb-odm:~/labs/tequila/object-mapper \
  ./gradlew test --tests '*RealTree*'   # parse every .phn of the dogfood trees; skipped when unset
```

- JDK 21; Gradle provisions one if none is installed. `./gradlew test` is a no-op when nothing
  changed (build cache): `--rerun` forces it. The results are in `build/test-results/test/*.xml`.
- `src/main/kotlin`: `PhaenonLexer` (erasure) and `PhaenonPsiBuilder` (no lazy bodies) feed the
  parser; `PhaenonErasedTypes` and `PhaenonTypeText` read the erased text back for
  `PhaenonTypeProvider`; `PhaenonExcludePolicy` keeps the output directories of `phaenon.json`
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
  `testData/formatting/`, `testData/implement/`); `src/main/resources/stubs/phaenon` holds the editor-only pseudo-classes
  (generated: `phn stdlib --plugin-stubs src/main/resources/stubs/phaenon/Phaenon/Internal` in the
  compiler repo rewrites them from `stdlib/runtime/*.phn`; a PR never edits them by hand)
  `\Phaenon\Internal\*Methods` that mirror `../phaenon/stdlib/runtime/*.phn` without the receiver
  parameter — keep them in step when the stdlib changes.
- `src/test/testData/reflector/*.phn` are copies of the `tequila/reflection` dogfood sources;
  `syntax/Constructs.phn` uses every Phaenon construct; `methods/` holds the method-syntax fixtures,
  `closure/` the closure-type ones, `imports/` the Optimize Imports ones (`X.phn` → `X.after.phn`).
  Tests set the IDE's highest PHP level: the test project defaults to PHP 5.6. It also lacks
  PhpStorm's bundled PHP stubs (they are indexed only for a project with a PHP module), so a test
  that needs `\Closure` and the like copies a stand-in (`closure/stubs/Closure.php`).
- `PhaenonRealTreeTest` runs the plugin over whole trees (`PHAENON_TREES`, above) and reports
  per tree: files, files with syntax errors, errors, stub mismatches, files skipped for PHP syntax
  the IDE's level lacks. Run it after every lexer or builder change; a `.phn` the compiler parses
  (`../phaenon/bin/phn check`) and PhpStorm does not is a plugin bug unless plain PHP fails alike
  (the test checks that itself and lists such files instead of counting them). A change that
  alters what a `.phn` parses into also bumps `PhaenonFileElementType.PSI_VERSION`: the stubs of
  `.phn` files carry PhpStorm's stub version, and the bump is what re-indexes them after an update
  (README § How it works).
- Known limits (README § Limitations): `is` / `as` do not narrow (except `is` on a class name),
  `type` aliases are erased whole, type arguments of user generics are dropped, tuples type as
  element-array unions; the implied `strict_types` is not modelled; editor colouring uses the
  internal extension point `com.jetbrains.php.baseLexerProvider`. Code a feature inserts is laid out
  by PhpStorm's formatter, as in PHP: insert it unformatted and let the reformatting that follows
  the command do it. Behaviour of a newer PhpStorm (the PHP 8.5 pipe and its inspection) is tested
  with `-PtestIdePath`; on 2025.1 those tests return early.
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `docs:`, … with a body that says
  what changed and why), with no attribution lines and no `Co-Authored-By`: the repository will be
  public.
