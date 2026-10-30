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
```

- JDK 21; Gradle provisions one if none is installed. `./gradlew test` is a no-op when nothing
  changed (build cache): `--rerun` forces it. The results are in `build/test-results/test/*.xml`.
- `src/main/kotlin`: `PhaenonLexer` (erasure) and `PhaenonPsiBuilder` (no lazy bodies) feed the
  parser; `PhaenonErasedTypes` and `PhaenonTypeText` read the erased text back for
  `PhaenonTypeProvider`; `src/main/resources/stubs/phaenon` holds the editor-only pseudo-classes
  `\Phaenon\Internal\*Methods` that mirror `../phaenon/stdlib/runtime/*.phn` without the receiver
  parameter — keep them in step when the stdlib changes.
- `src/test/testData/reflector/*.phn` are copies of the `tequila/reflection` dogfood sources;
  `syntax/Constructs.phn` uses every Phaenon construct; `methods/` holds the method-syntax fixtures.
  Tests set the IDE's highest PHP level: the test project defaults to PHP 5.6.
- Known limits (README § Limitations): `is` / `as` do not narrow (except `is` on a class name),
  `type` aliases are erased whole, type arguments of user generics are dropped, tuples type as
  element-array unions; the implied `strict_types` is not modelled; editor colouring uses the
  internal extension point `com.jetbrains.php.baseLexerProvider`.
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `docs:`, … with a body that says
  what changed and why), with no attribution lines and no `Co-Authored-By`: the repository will be
  public.
