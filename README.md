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

Phaenon's own syntax parses too — generics, `list<T>`, `map<K, V>`, `bytes`, tuples, `class<T>`,
`Closure(T): U`, `is` / `as`, `type` aliases — and the **method syntax** on collections and strings
is understood: `$users->filter(fn (User $u) => $u->active)->map(fn (User $u) => $u->name)`
completes, resolves, shows parameter info and is type-checked like a method call on an object, and
"go to declaration" lands on `\Phaenon\Seq::map()` when `phaenon/runtime` is installed.

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
| `PhaenonFileElementType` | root AST node of `.phn` files | An `IFileElementType` of the PHP language whose `parseContents` builds the `PsiBuilder` with `PhaenonLexer` and runs the stock PHP parser, the way PhpStorm parses its own code fragments. Everything below the root is ordinary PHP PSI, so the stub builder, indices, inspections and refactorings never notice. |
| `PhaenonLexer` | the parser's lexer | PhpStorm's `PhpWithOptionalOpenTagLexer` (starts in scripting mode unless the first token is an open tag) wrapped in a `LookAheadLexer` that erases Phaenon-only syntax into whitespace tokens, text and offsets untouched. See [Phaenon syntax](#phaenon-syntax). |
| `PhaenonPsiBuilder` | the parser's `PsiBuilder` | A `PsiBuilderAdapter` that intercepts PhpStorm's lazy function bodies and parses them on the spot, so the whole file goes through one lexer. PhpStorm expands a lazy block by re-lexing its text with a plain PHP lexer, which would discard the erasures inside every body. |
| `PhaenonBaseLexerProvider` | `com.jetbrains.php.baseLexerProvider` | Editor highlighting does not go through the parser: PhpStorm's editor highlighter is keyed by file type and builds its own lexer, asking this EP first. It gets the plain open-tag-optional lexer, so Phaenon syntax is coloured as the PHP tokens it is made of. |
| `PhaenonTypeProvider` | `com.jetbrains.php.typeProvider4` | Reads the erased Phaenon types back from the whitespace leaves (`PhaenonErasedTypes`, `PhaenonTypeText`) and gives parameters, properties and return types their types: `list<User>` → `User[]` plus `\Phaenon\Internal\ListMethods<User>`. Adds `StrMethods` to `string` and `\Stringable` receivers of `->`, and keeps the element type through chains (`->filter(...)->map(...)`). |
| `stubs/phaenon` | `com.jetbrains.php.libraryRoot` | Bundled, editor-only pseudo-classes, **generated** by `phn stdlib --plugin-stubs src/main/resources/stubs/phaenon/Phaenon/Internal` from the compiler's `stdlib/runtime/*.phn` (never edited by hand; the compiler's `TestPluginStubsFresh` fails when they are stale): `\Phaenon\Internal\{List,Map,Str,Bytes}Methods`: the stdlib's `Seq`, `Map`, `Str` and `Bytes` functions as instance methods without the receiver parameter, with PHPStan docblocks. They are what the method syntax resolves to, so completion, parameter info, quick documentation and the undefined-method inspection are PhpStorm's own. |
| `PhaenonGotoDeclarationHandler` | `gotoDeclarationHandler` | Resolves a class name inside an erased type (`list<DomainEvent>`, whitespace to PhpStorm) through the file's imports and namespace (`PhaenonErasedNames`), so Cmd+click works there. Sends a reference that resolves both to a `.phn` declaration and to the `.php` compiled from it (first line `@generated by phaenon`) to the source only, from `.phn` and `.php` files alike; a hand-written `.php` with the same class stays a target. Redirects navigation from a pseudo-class method to the stdlib function (`\Phaenon\Seq::map()`) when the `phaenon/runtime` package is in the project. |
| `PhaenonHighlightFilter` | `daemon.highlightInfoFilter` | Drops PhpStorm's "readonly property must have type" error and the "missing type declaration" inspections for properties, parameters and return types where the type was erased: it had one. Drops "Other declarations of class 'X' exist at X.php" when every other declaration is the compiled copy, and "Import 'X' is never used" when the import's name appears in an erased type. |
| `PhaenonExcludePolicy` | `directoryIndexExcludePolicy` | Excludes the output directories of every `phaenon.json` — at each content root and in each `vendor/*/*` package — from the project, so the compiled tree is never indexed and a class is declared once, in its source. `PhaenonProjectFileListener` re-applies it when a project file changes. |
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

## Phaenon syntax

Everything Phaenon adds to PHP erases to PHP in the compiler, and the plugin performs the same
erasure one level earlier, on tokens: a construct the PHP parser would reject reaches it as
whitespace. The `PsiWhiteSpace` leaves keep the Phaenon text at its offsets, and the type provider
reads it back.

| Phaenon | Parser sees | PhpStorm type |
|---|---|---|
| `class Box<T of Bound = Default>`, `function f<T>(…)`, `fn<T>(…)` | the name, the list erased | the names become type parameters for the rest of the file |
| `new Box<int>(…)`, `f<int>(…)`, `$o->m<T>(…)`, `C::m<T>(…)`, `implements I<T>` | the call or name, the list erased | — |
| `Foo<T>` in a type position | `Foo` | `\Ns\Foo` (type arguments of user classes are not modelled) |
| `list<T>` | erased | `T[]` and `\Phaenon\Internal\ListMethods<T>` |
| `map<K, V>` | erased | `V[]` and `\Phaenon\Internal\MapMethods<K, V>` |
| `bytes` | erased | `string` and `\Phaenon\Internal\BytesMethods` |
| `(A, B)` tuple | erased | `A[]\|B[]` |
| `class<T>` | erased | `class-string<T>` |
| `Closure(A): R` | `Closure`, the signature erased | `\Closure` |
| a type parameter `T` | erased | `mixed` |
| `?T`, `T\|null` with `T` erased | `?` and `\|` erased with it; a return type takes its `:` | the native members stay PhpStorm's |
| `$x is Foo` | `$x instanceof Foo` (the `is` token re-typed) | PhpStorm narrows as for `instanceof` |
| `$x is list<int>`, `$x is ?Foo`, `$x as T`, `$x as? T` | `$x` — the test is erased | the operand's type |
| `(bytes) $raw` | one `(string)` cast token | `string` |
| `type Name = …;` | erased whole | — (uses of `Name` are unknown classes) |
| `array<K, V>` (M3 spelling in migrated sources) | `array` | `array` |

The rule for `Name<` is the compiler's (`generics.md` §2.4): PHP's relational operators are
non-associative, so `A < B > C` has no PHP reading and a `<…>` that scans as a type list is a type
list. The one PHP shape this misreads, `f(A < B, C > ($d))`, the compiler misreads too.

Function bodies are parsed eagerly (see `PhaenonPsiBuilder` above), so an edit inside a body
reparses the file rather than the block — how PhpStorm worked before lazy blocks existed.

## Install

Download `phaenon-jetbrains-<version>.zip` from the releases page (or build it, see below), then in
PhpStorm: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart. Any `.phn`
file opens as Phaenon.

Requires PhpStorm 2025.1 or newer (or another JetBrains IDE 2025.1+ with the PHP plugin for full
support).

A project built with `phaenon.json` (`{"roots": {"src": ".src", "tests": ".tests"}}`) needs no
IDE setup: the plugin excludes the output directories, so PhpStorm resolves every class to its
`.phn`. Without a project file, when `phn build` writes `Foo.php` beside `Foo.phn`, both are
indexed; navigation and the duplicate-declaration inspection then prefer the source.

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

`src/test` runs the plugin inside the IntelliJ test framework with the real PHP plugin loaded.

- `PhaenonFileTest`: `.phn` files are PHP files with a Phaenon root; the real Phaenon sources from
  the Tequila platform under `testData/reflector` lex as PHP code (no inline-HTML token), parse
  without errors and build stubs consistent with their text; the editor highlighter starts in code
  mode; completion offers PHP members; classes declared in `.phn` files resolve from both `.phn` and
  `.php` files; a stray `<?php` is reported; reformatting leaves a well-formatted file untouched;
  plain `.php` files still start in HTML mode.
- `PhaenonSyntaxTest`: `testData/syntax/Constructs.phn` uses every Phaenon construct and parses
  without errors or error highlighting; the erased text is whitespace at its original offsets;
  `is` on a class name is `instanceof`; `(bytes)` is a string cast; bodies are not lazy; PHP without
  Phaenon syntax lexes exactly as PhpStorm lexes it; reformatting leaves erased syntax in place.
- `PhaenonMethodsTest`: completion on `list`, `map`, `string`, `bytes` and `\Stringable` receivers;
  the calls resolve to the pseudo-classes and navigate to `\Phaenon\Seq::map()`; the
  undefined-method inspection accepts the method form and still reports a misspelt name, also at
  the end of a chain; erased declarations get their types; chains and `foreach` keep the element
  type; `readonly` properties with erased types are not errors.
- `PhaenonRealTreeTest`: volume. Every `.phn` file under the directories named by `PHAENON_TREES`
  (colon-separated; `vendor` excluded) is added to the test project; the test fails listing
  `file:line: message` for every syntax error and every stub tree that does not match its text,
  and checks that every erased `list<…>` / `map<…>` declaration got its pseudo-class. Files using
  PHP syntax above the IDE's highest language level (`|>` on PhpStorm 2025.1) are skipped and
  listed. The test framework's recursion assertion is off in this test: PhpStorm's own inference
  has cycles in real code which the IDE resolves silently. Without the variable the test passes
  trivially:

  ```bash
  PHAENON_TREES=~/labs/tequila/core:~/labs/tequila/mongodb-odm:~/labs/tequila/object-mapper \
    ./gradlew test --tests '*RealTree*'
  ```

`./gradlew test` is a no-op when nothing changed (build cache); `./gradlew test --rerun` forces a
run. After changing a bundled stub, a stale index in the test sandbox can report "Outdated stub in
index": delete `.intellijPlatform/sandbox/phaenon-jetbrains/<IDE>/system-test/index`.

## Limitations

- **Phaenon types are modelled as PhpStorm can hold them.** A `list<User>` is `User[]` plus the
  pseudo-class; a `map<K, V>` keeps its value type and loses its key type in `foreach`; a tuple is
  the union of its element arrays, so `$pair[0]` is `A|B` rather than `A`; a type parameter is
  `mixed`; type arguments of user generics (`Box<User>`) are dropped, so `$box->get()` has the erased
  return type. The Phaenon checker (`phn check`) is the authority on types; the editor approximates.
- **`is` and `as` do not narrow**, except `$x is Foo` on a plain class name, which PhpStorm sees as
  `instanceof`. The other forms are erased with their type, so the expression keeps its operand's
  type and a `$y = $x as map<string, Money>` is typed as `$x`.
- **Names inside erased types are text, not references.** `list<DomainEvent>` navigates (the
  plugin resolves the name itself) and keeps its import used, but find-usages, rename and
  completion do not see the name there.
- **`type` aliases are erased whole**: the alias name is an unknown class wherever it is used.
- **A parameter description without a type is written `@param $x - prose`.** PhpStorm's PHPDoc
  parser reads `@param $x prose` as the variable followed by a type named `prose` (an undefined
  class, a type mismatch, and `prose|string` as the parameter's type); the dash, which `phn
  migrate` writes and the compiler drops, is read as description by every tool.
- **Strings get their methods only as receivers.** `$s->upper()` works for any `string` or
  `\Stringable` value; the `Str` methods are not listed among the members of a string anywhere
  else, and a `\Stringable` object offers them only when the member name is a `Str` method (or is
  being completed).
- **Chains type through what the closure returns.** `->map(fn (User $u) => $u->name)` is a
  `list<string>`; a closure whose return type PhpStorm cannot infer yields an unparametrised
  `ListMethods`, whose methods still resolve but whose element type is `mixed`.
- The union behind a `list` carries a `?` marker from PhpStorm on method calls (the array half has
  no methods); it does not silence the undefined-method inspection, which still reports a misspelt
  method at the end of a chain.
- `Closure(T): U` keeps `Closure` as a PHP class reference: it resolves only when `Closure` is
  imported or written `\Closure`, as in PHP.
- A `<…>` that scans as a type list is one: `f(A < B, C > ($d))` and `A < B & C > $d` with constants
  are misread, as the compiler misreads the former (`generics.md` §2.4).
- **The plugin erases Phaenon, not PHP.** PHP syntax newer than the IDE's highest language level
  is a syntax error in `.phn` exactly as in `.php`: on PhpStorm 2025.1 (levels up to PHP 8.4)
  that is the PHP 8.5 pipe operator `|>`, which breaks the enclosing class body. A newer PhpStorm
  parses it.
- **An erased return type leaves no PHP return type.** For `function f(): map<string, mixed>`
  PhpStorm sees a function without a return type, so besides the type the plugin supplies it also
  infers one from the body, as it does for untyped PHP functions. The union is wider than the
  declaration, and methods that call each other this way give PhpStorm an inference cycle it
  resolves by settling for a partial type, as in PHP.
- The implied `declare(strict_types=1)` is not modelled: inspections that depend on strict types
  behave as they would in a PHP file without the declaration. PhpStorm's **project language level**
  governs the PHP parser (below PHP 7.4 it reads `fn` as a function name); a Phaenon project should
  be set to PHP 8.x.
- A few PHP features look at the *file type* and will treat `.phn` as a regular PHP file (for
  example the "PHP file" run configuration will try to run it with the PHP interpreter, which is
  not meaningful before compilation).
- Three spots in PhpStorm compare the root node against PHP's own file element type by identity
  (top-level folding, top-level indentation, dead-code entry points). Reformatting a well-formed
  `.phn` file is covered by a test and comes out unchanged, but a corner case there would show up
  as a formatting or folding difference, not as broken analysis.
- The TextMate fallback is highlighting only, and it needs the IDE's own PHP TextMate bundle
  (bundled with every JetBrains IDE) for `source.php`.
- `com.jetbrains.php.baseLexerProvider` is marked internal by JetBrains. It is the only way to
  hand the editor highlighter a lexer for a file of the PHP file type, and it is present unchanged
  from 2025.1 through 2026.2 (both verified with the Plugin Verifier), but a future PhpStorm could
  rename it. If that happens, parsing and every analysis feature keep working and only the editor
  colouring of `.phn` files would regress until the plugin is updated. The verifier is configured
  to report, not fail on, internal API usage for this reason. `PhaenonPsiBuilder` and
  `PhaenonTypeProvider` also call PhpStorm parser and type-analysis classes (`PhpPsiBuilder`,
  `StatementList`, `PhpTypeAnalyserVisitor`) that are public but outside the documented open API;
  the verifier reports them compatible with 2025.1.

## VS Code

`textmate/phaenon` is laid out as a VS Code extension (`package.json` with `contributes.languages`
and `contributes.grammars`). Copying that directory into `~/.vscode/extensions/phaenon` gives `.phn`
files PHP highlighting in VS Code.

## License

MIT, see `LICENSE`.
