# Phaenon for JetBrains IDEs — agent guide

The PhpStorm plugin for `.phn` files (Kotlin, IntelliJ Platform Gradle Plugin, PhpStorm 2025.1+).
It makes PhpStorm read a `.phn` file as PHP code without the open tag; the README explains how and
why it is not a separate language. The map of every Phaenon repo, the working method and the commit
rules are in the compiler repo: read `../phaenon/AGENTS.md` first.

```sh
./gradlew test           # inside a headless PhpStorm; the first run downloads it
./gradlew buildPlugin    # → build/distributions/phaenon-jetbrains-<version>.zip
./gradlew verifyPlugin   # JetBrains Plugin Verifier
./gradlew runIde         # sandboxed PhpStorm with the plugin
```

- JDK 21; Gradle provisions one if none is installed.
- `src/test/testData/reflector/*.phn` are copies of the `tequila/reflection` dogfood sources.
- Known limits (README § Limitations): Phaenon-only syntax (generics, `is` / `as`, `type` aliases)
  shows as PHP syntax errors; the implied `strict_types` is not modelled; editor colouring uses the
  internal extension point `com.jetbrains.php.baseLexerProvider`.
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `docs:`, … with a body that says
  what changed and why), with no attribution lines and no `Co-Authored-By`: the repository will be
  public.
