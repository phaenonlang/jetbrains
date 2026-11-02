# Phaenon for JetBrains IDEs

PhpStorm support for [Phaenon](https://github.com/phaenonlang) source files (`.phn`), a typed dialect
of PHP that compiles to PHP.

A Phaenon file is PHP code from the first byte: no `<?php` open tag, no `?>`, no inline HTML, and
`declare(strict_types=1)` is implied. This plugin teaches PhpStorm that contract, so `.phn` files get
the **full PHP toolchain** rather than a syntax-colouring approximation of it:

- highlighting, code folding, brace matching, and PhpStorm's PHP formatting: Reformat Code, auto-indent,
  docblock stubs, and the layout of code an action inserts
- completion, parameter info, quick documentation — with Phaenon's types printed as Phaenon writes them
  (`class<T>`, `list<User>`, `Closure(int): string`)
- go to declaration / usages / implementations across `.phn` and `.php` files, in both directions
- refactorings (rename, move, extract, ...)
- every PHP inspection and intention, plus one Phaenon-specific error for stray `<?php` / `?>` tags

Phaenon's own syntax parses too — generics, `list<T>`, `map<K, V>`, `bytes`, tuples, `class<T>`,
`Closure(T): U`, `is` / `as`, `type` aliases, and PHP 8.6's partial application, which no PhpStorm
parses yet — and the **method syntax** on collections and strings
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
| `PhaenonLexer` | the parser's lexer | PhpStorm's `PhpWithOptionalOpenTagLexer` (starts in scripting mode unless the first token is an open tag) wrapped in a `LookAheadLexer` that erases Phaenon-only syntax into `PhaenonTokenTypes.ERASED` tokens — comments to the parser (`PhaenonParserDefinition` adds the type to PHP's comment set), text and offsets untouched. See [Phaenon syntax](#phaenon-syntax). `fun` before a name is handed over as the `function` keyword (text kept), so a `fun` method parses as a method and, without a visibility, as public — as PHP reads it and as the compiler writes it (design/functions.md §1). A PHP 8.6 placeholder `?` standing alone as an argument is handed over as an identifier, so `f(?, 1)` parses as a call with a constant for an argument (see [PHP 8.6](#php-86)). `concurrently` used as a value and `spawn` are handed over as the closures they compile to, their own characters split into `function ( )` and `fn ( ) =>` tokens (`PhaenonBlocks`). |
| `PhaenonPsiBuilder` | the parser's `PsiBuilder` | A `PsiBuilderAdapter` that intercepts PhpStorm's lazy function bodies and parses them on the spot, so the whole file goes through one lexer. PhpStorm expands a lazy block by re-lexing its text with a plain PHP lexer, which would discard the erasures inside every body. |
| `PhaenonBaseLexerProvider` | `com.jetbrains.php.baseLexerProvider` | Editor highlighting does not go through the parser: PhpStorm's editor highlighter is keyed by file type and builds its own lexer, asking this EP first. It gets the plain open-tag-optional lexer, so Phaenon syntax is coloured as the PHP tokens it is made of. Wrapped in `PhaenonHighlightingLexer`, which colours `fun` as the keyword where it declares something. |
| `PhaenonTypeProvider` | `com.jetbrains.php.typeProvider4` | Reads the erased Phaenon types back from the erased leaves (`PhaenonErasedTypes`, `PhaenonTypeSyntax`, `PhaenonTypeText`) and gives parameters, properties and return types their types: `list<User>` → `User[]` plus `\Phaenon\Internal\ListMethods<User>`; a type parameter → its bound, as the compiler writes it; `class<T>` → `class-string<Bound>` alone, the type PhpStorm gives a docblock-only `@return class-string<Bound>`, on which `$c::m()`, `new $c` and `$c::CONST` resolve; `Closure(A, B=, C...): R` → `\Closure` plus the signature in PhpStorm's own encoding. Adds `StrMethods` to `string` and `\Stringable` receivers of `->` (never of `::`; a class-string is a string to it), and keeps the element type through chains (`->filter(...)->map(...)`). Types a partial application as the closure it creates and a pipe stage that is one as the call it becomes. |
| `stubs/phaenon` | `com.jetbrains.php.libraryRoot` | Bundled, editor-only pseudo-classes, **generated** by `phn stdlib --plugin-stubs src/main/resources/stubs/phaenon/Phaenon/Internal` from the compiler's `stdlib/runtime/*.phn` (never edited by hand; the compiler's `TestPluginStubsFresh` fails when they are stale): `\Phaenon\Internal\{List,Map,Str,Bytes}Methods`: the stdlib's `Seq`, `Map`, `Str` and `Bytes` functions as instance methods without the receiver parameter, with PHPStan docblocks. They are what the method syntax resolves to, so completion, parameter info, quick documentation and the undefined-method inspection are PhpStorm's own. |
| `PhaenonErasedReferenceContributor` | `psi.referenceContributor` for PHP | Every erased construct is a `PhaenonErasedLeaf` (a comment leaf the token type creates itself), and each class name in it — `DomainEvent` in `list<DomainEvent>`, `User` in `Box<User>`, `Closure` in `Closure(int): string` — gets a `PhaenonClassReference` resolved as PHP would through the file's imports and namespace (`PhaenonErasedNames`), except a bare `Closure` before a signature, which is `\Closure` in any namespace, as the compiler reads it. Navigation, find-usages, rename and the unused-import inspection see them as they see a docblock type. A type parameter used in one (`T` in `id(): T`) gets a `PhaenonTypeParameterReference` to its declaration in the enclosing `<T of …>` list (`PhaenonTypeParameter`, a stand-in element at the name's offset). The declaration itself is one to the platform (`PhaenonTypeParameterDeclarations`, `psi.declarationProvider`): Cmd+click on it shows the usages, found by the default find-usages handler plus `PhaenonDocTypeReferenceSearcher`, which adds the docblock types naming it (`@var T $x`); such a docblock type also navigates to the declaration and is not an "undefined class". |
| `PhaenonImportOptimizer` | `lang.importOptimizer` for PHP, `order="first"` | Optimize Imports in `.phn` files — the action, Reformat Code with imports, the save action and the pre-commit step all take the first optimizer of the language that supports a file. Runs PhpStorm's optimizer, which counts only PHP's own references and would remove `use App\Event\DomainEvent;` that only `list<DomainEvent>` uses, then puts back every import an erased construct uses (the rule the unused-import filter follows) where the optimizer's sorting places it. The PHP code style lays the block out, as in a `.php` file. `.php` files keep PhpStorm's optimizer alone; the PHP plugin has no import optimization on the fly. |
| `PhaenonFormattingModelBuilder` | `lang.formatter` for PHP, a `CustomFormattingModelBuilder` | The formatter of `.phn` files. PhpStorm's `PhpFormattingModelBuilder` is a template formatter: for a `.php` file it builds the HTML model of the template view provider and asks for PHP blocks inside it, and for any other view provider it falls back to one read-only block for the whole file — which is what a `.phn` file, single-rooted, got: Reformat Code, the indent of a new line, the `*` of a docblock and the reformatting after generated code all did nothing. The plugin's builder, engaged for `.phn` files only, hands the file's PSI to the PHP builder's public `createTemplateFormattingModel`, the call the template path makes for each PHP region, so every rule is PhpStorm's with the project's PHP code style. Its blocks are wrapped (`PhaenonFormattingBlock`) to decide the spacing next to an erased construct, which PhpStorm's rules see as a token of unknown kind: a `<…>` list stays against its name and the `(` of a call, an erased return type against the `)`, a union member against its neighbour; a standalone construct (`list<User> $users`, `$x as T`, `spawn $task`) is one space from its neighbours or keeps its line breaks, and an erased type keeps the wrap and alignment of the parameter it types. The construct's own text is never changed. Two blocks that meet inside a word — a keyword split into PHP tokens — stay together, and a `concurrently` block used as a statement is laid out as one statement with its body one level in, as an `if`'s. |
| `PhaenonDocCommentEnterHandler` | `enterHandlerDelegate`, `order="before phpEnterHandler"` | Enter after the opening of a docblock in a `.phn` file: PhpStorm's registered PHP enter handler writes its stub, then the `@param` of every parameter with a type, the `@return` of a declared return type and the `@var` of a typed property are taken out, erased types included (PhpStorm took `class<T>` for no type and wrote `@return mixed`). Untyped parameters keep PhpStorm's tags; a stub left empty is a line for the description. |
| `PhaenonImplementMethodsHandler`, `PhaenonOverrideMethodsHandler` (`PhaenonOverrides`) | `codeInsight.implementMethod` / `codeInsight.overrideMethod` for PHP, `order="first"` | Code \| Implement Methods and Override Methods: PhpStorm's (its member chooser, bodies, docblock options, formatting, imports), then each generated method of a `.phn` class rewritten: `fun`, `protected fun`, `static` kept; every parameter type and return type the parent declared with an erased part taken from the parent's source; the parent's type parameters replaced by the class's type arguments, followed through every `extends` / `implements` in between (`fun get(Id $id): ?T` in a class that `extends AbstractEventSourcedRepository<UserId, User>` is `fun get(UserId $id): ?User`); class names of those types imported or qualified for the class's file. The platform asks only the first handler of a language, so for other PHP files these are PhpStorm's handlers. |
| `PhaenonErasedCompletionContributor` | `completion.contributor` for PHP, `order="first, before liveTemplates"` | Completion inside an erased construct: the project's classes, interfaces and traits (a class from another namespace gets its `use` statement on insertion), the type parameters in scope, and the type keywords; after a qualifier, the classes and child namespaces of that namespace. The dummy identifier is the trimmed one, so the copy still scans as a type list. Also offers `fun` where a declaration may start in a `.phn` file, and sees the items of every contributor after it there: PhpStorm's override and implement items are shown and inserted as Phaenon methods (`fun get(UserId $id): ?User {...}`, rewritten as by the handlers above), and PhpStorm's bundled PHP live template `fun`, which showed as a second `fun` and expands to `function`, is left out. It comes before the live-template contributor, which wraps the contributors after it. |
| `PhaenonSpellcheckingStrategy` | `spellchecker.support` for PHP | An erased leaf is a comment to the platform, which would spell-check it as prose; it is code and is not checked, as PhpStorm checks no class reference. |
| `PhaenonBlocks` | used by the type provider, the filter and the formatter | Knows the closures `PhaenonLexer` makes of `concurrently` blocks used as values and of `spawn` (`function ( )` and `fn ( ) =>` spelt with the keyword's own characters), the function a variable really belongs to through them, and which variables they are assigned to. |
| `PhaenonDocumentationProvider` | `lang.documentationProvider` for PHP, `order="first"` | The definition line of quick documentation and of the Ctrl+hover hint for Phaenon elements, where PhpStorm prints the erasure: PhpStorm prints every class-string as `string` (and a native `string` beside a docblock class-string as `string\|string` in 2026.2), drops an erased parameter type from a signature and pastes an erased return type in raw, its `<T>` read as an HTML tag. A function or method of a `.phn` with an erased part in its signature prints as written (`abstract protected static fun aggregateClass(): class<T>`), a parameter or property with an erased type as `$users: class<User>`, and a variable of a `.phn` whose type holds a class-string, a pseudo-class or a closure signature as that type in Phaenon's spelling (`$class: class<EventSourcedAggregate>`, `PhaenonTypePresentation`), the pseudo-class `StrMethods` of a receiver left out. The rest of the popup — the class, the docblock sections, the source — is PhpStorm's; `.php` files and everything else are PhpStorm's alone. |
| `PhaenonParameterInfoHandler` | `codeInsight.parameterInfo` for PHP, `order="first"` | Parameter info for a call of a Phaenon function with an erased parameter type, PhpStorm's layout with the type as written: `users: class<User>, id: \App\AggregateId` where PhpStorm prints `users: string`. It takes a call only when one of its targets is such a function and hands the rest — finding the call, the current argument, named arguments — to PhpStorm's handler. |
| `PhaenonGotoDeclarationHandler` | `gotoDeclarationHandler` | Sends a reference that resolves both to a `.phn` declaration and to the `.php` compiled from it (first line `@generated by phaenon`) to the source only, from `.phn` and `.php` files alike; a hand-written `.php` with the same class stays a target. Redirects navigation from a pseudo-class method to the stdlib function (`\Phaenon\Seq::map()`) when the `phaenon/runtime` package is in the project. |
| `PhaenonHighlightFilter` | `daemon.highlightInfoFilter` | Drops PhpStorm's "readonly property must have type" error and the "missing type declaration" inspections for properties, parameters and return types where the type was erased: it had one. Drops "Other declarations of class 'X' exist at X.php" when every other declaration is the compiled copy, and "Import 'X' is never used" when the import's name appears in an erased type. "Return type declaration must be compatible with…" and "Declaration must be compatible with…" are dropped on an override where either side has an erased type, in a `.phn` or in a `.php` implementing a Phaenon interface: PhpStorm reads `save(T $aggregate)` as `save(mixed $aggregate)`, and `PhaenonHierarchyInspection` judges the pair instead. PhpStorm's "Parameter type 'string' is not compatible with 'string[]'" on a call of a `Closure(string...)` property is dropped where the argument fits the declared Phaenon parameter (PhpStorm encodes the variadic of a closure literal or first-class callable assigned to it as an array). The PSR-12 "missing visibility" finding is dropped on a `fun` method, a class constant, and a property or parameter whose only visibility is `private(set)`-style, all public by the language's rule and written out by the compiler; the annotator's "expression is not allowed as …" findings are dropped on a closure in an attribute argument, a constant or a default, which the compiler lowers to PHP 8.5's `static function`. PhpStorm's findings on PHP 8.6 code go as [PHP 8.6](#php-86) describes, some only where the file's target (`PhaenonTarget`) is 8.6. In a `concurrently` block or a `spawn`, closures to PhpStorm without a `use` clause, an "undefined variable" the enclosing function defines (or a block assigns before it is read after), an "unused" parameter or variable used across a block's boundary, "Return value is expected to be …" on `return concurrently { … };` and whatever PhpStorm reports at the keyword are dropped. |
| `PhaenonHierarchyInspection` | `localInspection` for PHP, "Phaenon override compatibility" | Signature compatibility for overrides with an erased type on either side, in PhpStorm's words ("Declaration must be compatible with AggregateRepository->save(aggregate: \App\Aggregate)"). PhpStorm's own check compares `Parameter.getDeclaredType()`, read from the PSI's type declaration or its stub, which no extension point reaches; this one reads the parent's signature as the child sees it, the parent's type parameters replaced by the arguments written on the `extends` / `implements` path (`PhaenonErasure`), and compares as the checker does (`P2502`): a type parameter by its bound, a native child type equal to the parent's erasure as inheriting the parent's type (`generics.md` §11.1), parameters contravariant, returns covariant, counts, optional, variadic and by-reference parameters as in PHP. What passes compiles: the override-erasure rule (§4.3) widens the child's parameter to the parent's erased type. |
| `PhaenonExcludePolicy` | `directoryIndexExcludePolicy` | Excludes the output directories of every `phaenon.json` — at each content root and in each `vendor/*/*` package — from the project, so the compiled tree is never indexed and a class is declared once, in its source. `PhaenonProjectFileListener` re-applies it when a project file changes. |
| `PhaenonPipeConversionIntention`, `PhaenonPipeQuickFixFilter` | `intentionAction` for PHP, `daemon.intentionActionFilter` | PhpStorm releases with PHP 8.5 (2026.2; not 2025.1) report nested calls that can be a pipe, and its quick-fix writes each one-argument call as its first PSI child plus `(...)` — for a method call the object or class it is called on: `$this->output->writeln(Style::line(sprintf(…)))` becomes `sprintf(…) \|> Style(...) \|> $this->output(...)`, in `.php` files too. In a `.phn` file the filter hides that fix (matched by class name) and the intention converts correctly: `sprintf(…) \|> Style::line(...) \|> $this->output->writeln(...)`, a call with other arguments as a parenthesised arrow function. Available where the project's language level has `\|>`. |
| `PhaenonPhpTagAnnotator` | `annotator` for PHP | Reports `<?php`, `<?=` and `?>` as errors in Phaenon files. |
| `PhaenonFileIconProvider` | `fileIconProvider` | Gives `.phn` files their own icon despite sharing the PHP file type. |
| `PhaenonTextMateBundleProvider` | `textmate.bundleProvider` | Registers `textmate/phaenon`, a grammar that includes `source.php` (the PHP *code* grammar, not the `text.html.php` wrapper). Only used when the PHP plugin is absent. |

Both plugin dependencies (`com.jetbrains.php`, `org.jetbrains.plugins.textmate`) are optional in
`plugin.xml`; the PHP part lives in `phaenon-php.xml`, the fallback in `phaenon-textmate.xml`.

One consequence of sharing the PHP file type: the stub trees PhpStorm stores for `.phn` files
carry PhpStorm's stub version, not the plugin's (the platform versions stubs by language, through
the PHP parser definition's file node type, and never asks a file's root element type). When a
plugin update changes what a `.phn` parses into — a new erasure, `fun` handed over as `function` —
the stored stubs no longer match the PSI and PhpStorm reports "PSI and index do not match". So the
plugin keeps its own PSI version (`PhaenonFileElementType.PSI_VERSION`), and on the first start of
a project after an update that changed it, `PhaenonReindexActivity` requests a re-index of the
project's `.phn` files, once. An install older than this mechanism has no stored version and is
re-indexed the same way; if the error still shows, **File | Invalidate Caches** is the manual remedy.

Why not a Phaenon *language*? A PHP dialect (a `Language` with PHP as base) was the first attempt.
It works for completion and inspections through the base-language fallback, but PhpStorm's file
element type is bound to the PHP language and the platform asserts that a file's root element type
and its language agree, so a dialect needs its own file element type and its own stub registration
mirroring PHP's. Keeping the file in the PHP language and only swapping the root element type
removes that whole class of problems: nothing in PhpStorm can tell a `.phn` file apart from PHP
unless it looks at the root node's type.

## Phaenon syntax

Everything Phaenon adds to PHP erases to PHP in the compiler, and the plugin performs the same
erasure one level earlier, on tokens: a construct the PHP parser would reject reaches it as a
comment-kind token it skips. The erased leaves keep the Phaenon text at its offsets; the type
provider reads it back, and the class names in it are references.

| Phaenon | Parser sees | PhpStorm type |
|---|---|---|
| `class Box<T of Bound = Default>`, `function f<T>(…)`, `fn<T>(…)` | the name, the list erased | the names become type parameters for the rest of the file |
| `new Box<int>(…)`, `f<int>(…)`, `$o->m<T>(…)`, `C::m<T>(…)`, `implements I<T>` | the call or name, the list erased | — |
| `Foo<T>` in a type position | `Foo` | `\Ns\Foo` (type arguments of user classes are not modelled) |
| `list<T>` | erased | `T[]` and `\Phaenon\Internal\ListMethods<T>` |
| `map<K, V>` | erased | `V[]` and `\Phaenon\Internal\MapMethods<K, V>` |
| `bytes` | erased | `string` and `\Phaenon\Internal\BytesMethods` |
| `(A, B)` tuple | erased | `A[]\|B[]` |
| `class<T>` | erased | `class-string<Bound>` (`T`'s bound; `class<User>` names `User`, `class<A\|B>` is `class-string<A,B>`), printed `class<…>` |
| `f(?, 1)`, `f(b: ?)`, `$o->m(?)`, `C::m(1, ...)` (PHP 8.6) | the call; `?` an identifier, a constant named `?`; `...` PhpStorm's spread without an operand | the callee's result, which PhpStorm adds, and the closure: `Closure(int): string` from the placeholders' parameters and the callee's return type |
| `Closure(A, B=, C...): R` | erased: to PhpStorm a bare `Closure` is a class of the file's namespace | `\Closure`, in any namespace, and the signature as PhpStorm encodes the docblock `\Closure(A, B, C): R` |
| `\Closure(A): R` | `\Closure`, the signature erased | the same as the bare spelling |
| a type parameter `T` | erased | its bound, `mixed` without one |
| `?T`, `T\|null` with `T` erased | `?` and `\|` erased with it; a return type takes its `:` | the native members stay PhpStorm's |
| `$x is Foo` | `$x instanceof Foo` (the `is` token re-typed) | PhpStorm narrows as for `instanceof` |
| `$x is list<int>`, `$x is ?Foo`, `$x as T`, `$x as? T` | `$x` — the test is erased | the operand's type |
| `(bytes) $raw` | one `(string)` cast token | `string` |
| `type Name = …;` | erased whole | — (uses of `Name` are unknown classes) |
| `concurrently (opts) { … }` as a statement | the keyword and options erased: a block | the statements inside are ordinary statements |
| `concurrently (opts) { … }` as a value: `$x = …;`, `return …;`, an argument | `function () { … }`: the keyword's twelve characters split into `function`, `(`, `)`; the options erased | the body's return type beside PhpStorm's `\Closure`, on the variables it is assigned to |
| `spawn { … }`, `spawn expr` | `function () { … }`, `fn () => expr`: the keyword's five characters split into those tokens | `Amp\Future<T>` of the body beside PhpStorm's `\Closure`, on the variables it is assigned to (an array of them for `$tasks[$k] = spawn …`) |
| `array<K, V>` (M3 spelling in migrated sources) | `array` | `array` |

The rule for `Name<` is the compiler's (`generics.md` §2.4): PHP's relational operators are
non-associative, so `A < B > C` has no PHP reading and a `<…>` that scans as a type list is a type
list. The one PHP shape this misreads, `f(A < B, C > ($d))`, the compiler misreads too.

Function bodies are parsed eagerly (see `PhaenonPsiBuilder` above), so an edit inside a body
reparses the file rather than the block — how PhpStorm worked before lazy blocks existed.

## PHP 8.6

PHP 8.6 ships on 2026-11-19 and the compiler supports it (design/php-versions.md). No PhpStorm
does yet: the language levels of 2025.1 stop at PHP 8.4 and those of 2026.2.3 at PHP 8.5, so in
plain PHP each 8.6 construct is a finding, the same in both:

| PHP 8.6 | PhpStorm 2025.1 and 2026.2.3 in a `.php` file | `.phn`, target 8.6 | `.phn`, target 8.5 |
|---|---|---|---|
| partial application: `f(?, 1)`, `f(b: ?)`, `$o->m(?)`, `C::m(?)`, in pipes and constant expressions | syntax errors from the `?` on ("Expected: )"); `f(1, ...)`: "Expected: expression after spread operator"; in a constant: "Expression is not allowed as constant value" | parses, no finding, typed (below) | the same: the compiler lowers it for 8.5 |
| `public readonly string $name = 'x';` | "'readonly' property cannot have default value" | no finding | PhpStorm's finding stays (the compiler: "needs PHP 8.6") |
| `#[\Override]` on a class constant, an enum case | on a constant: "Attribute cannot be applied to a class constant because it does not contain the 'Attribute::TARGET_CLASS CONSTANT' flag" (the stubs' targets); on a case: nothing | no finding | no finding: the compiler checks it and drops the attribute |
| `__debugInfo()` in an enum | "Enum may not include '__debugInfo'" | no finding | PhpStorm's finding stays |
| `CONFIG->prop = $v` | "Can't use temporary expression in write context" | no finding | PhpStorm's finding stays |

The target is the compiler's (§2): `phaenon.json`'s `"php"`, otherwise the lowest version
composer.json's `require.php` admits (Composer's constraint grammar, ported from the compiler's
`phpversion.LowestAdmitted`), otherwise 8.5; the nearest of each file at or above the source;
clamped to 8.5…8.6 (`PhaenonTarget`).

A partial application is a call to PhpStorm, with a constant named `?` for each placeholder, so
its bound arguments are code: they complete, navigate and are type-checked. The plugin types it as
the closure it creates — parameters from the placeholders in PHP's order (positional, then named
as written, then what `...` leaves, a variadic by its element), return type the callee's — and
PhpStorm adds the callee's return type, as it does for any call: `$g = format(?, 'kg')` is
`string|(Closure(int): string)`, and `$g(5)` is checked against the closure. In a pipe, the stage
`$x |> Style::width(2, ?)` is typed as the call it becomes, so the next stage sees `int`; PhpStorm
types a function stage that way itself and a method stage not at all.

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

To build and run the tests against an installed IDE instead of the declared one — for behaviour only a
newer PhpStorm has, such as the PHP 8.5 pipe operator and its inspection:

```bash
./gradlew test -PtestIdePath=/Applications/PhpStorm.app/Contents
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
  without errors or error highlighting; the erased text is an erased leaf at its original offsets;
  `is` on a class name is `instanceof`; `(bytes)` is a string cast; bodies are not lazy; PHP without
  Phaenon syntax lexes exactly as PhpStorm lexes it; reformatting leaves each erased construct as written.
- `PhaenonMethodsTest`: completion on `list`, `map`, `string`, `bytes` and `\Stringable` receivers;
  the calls resolve to the pseudo-classes and navigate to `\Phaenon\Seq::map()`; the
  undefined-method inspection accepts the method form and still reports a misspelt name, also at
  the end of a chain; erased declarations get their types; chains and `foreach` keep the element
  type; `readonly` properties with erased types are not errors.
- `PhaenonClosureTypeTest`: a bare `Closure(…)` in a namespaced file (`testData/closure/`) — as a
  property, promoted parameter, parameter, nullable parameter, union member and return type — is
  no undefined class, is typed `\Closure`, navigates to `\Closure` even beside a namespace class
  named `Closure`, completes the `\Closure` methods, and declares the same types as the
  `\Closure(…)` spelling; an import of `Closure` stays used; a `Closure` without a signature
  resolves as in PHP. The signature is PhpStorm's own reading of the docblock spelling; a call on
  tq-cli's `Closure(string...): (int, string) $kubectl` is checked per argument, though PhpStorm also
  holds its `string[]` encoding of the literal assigned to it, and `[$status] = …` is `int|string`.
  PhpStorm indexes its bundled PHP stubs only for a project with a PHP module, which the test
  project is not: `closure/stubs/Closure.php` stands in for PHP's `\Closure`.
- `PhaenonHierarchyTest`: `testData/hierarchy/` after tequila/core's `AggregateRepository<Id of
  AggregateId, T of Aggregate>` and its event-sourced implementation: a type parameter is typed as
  its bound (`?T`, `int|T`, `list<T>`, a property) and resolves members against it; the repository
  as it reads today (`save(Aggregate $aggregate)`) and with `save(T $aggregate)` raise no
  hierarchy finding; real incompatibilities are reported with the erased types (a `string`
  parameter or return, a narrower bound against a non-generic parent, a raw `implements`, a
  missing parameter), in `.phn` and in a `.php` implementing the interface; type arguments
  substitute through `Box<int>` and `Middle<U> extends Box<list<U>>`; a native type equal to the
  parent's erasure inherits (mongodb-odm's normalizers, core's `Command<TResult>` buses).
- `PhaenonClassStringTest`: `class<T>` and `class<User>` as return, parameter and property: the
  type is `class-string<Bound>` alone for the method, the call and the variable; static calls,
  `new`, constants and `::class` resolve against the class, a class-string passes for a `string`,
  `$c->length()` resolves against the `Str` methods, and no `::` access gets them; hover, quick
  documentation and Ctrl+hover show `aggregateClass(): class<T>`, `$class: class<EventSourcedAggregate>`,
  `$users: class<User>`; Ctrl+hover and Ctrl+click on the getter land on the method; parameter info
  prints `users: class<User>`; a `.php` file keeps PhpStorm's presentation (`classstring/Plain.php`
  records it: `string`, and `string|string` with a native `string` on 2026.2).
- `PhaenonDocumentationTest`: `testData/docs/Types.phn`: declarations print their written types,
  variables `list<User>`, `map<string, User>`, `bytes`, `?class<User>`, `Closure(int): string`; the
  `Str` methods of a receiver are not part of its type; a function prints its signature as written,
  one parameter per line in the popup, and keeps PhpStorm's docblock sections.
- `PhaenonPhp86Test`: `testData/php86/`: partial applications parse without a syntax error or a
  finding on either target, are typed as their closures and come back from Reformat Code as
  written; on 2026.2 a pipe stage is typed as its call (`PartialPipe.phn`); readonly defaults,
  `#[\Override]` on constants and enum cases, `__debugInfo()` in an enum and a write through a
  constant raise nothing on an 8.6 target, and on an 8.5 target PhpStorm's three findings stay; the
  target follows `phaenon.json` and composer.json, and Composer constraints read as the compiler's
  `phpversion` tests read them. PhpStorm's bundled `Core_c.php` stub is copied in from the PHP
  plugin for `Attribute` and `Override`.
- `PhaenonImportOptimizerTest`: Optimize Imports on `testData/imports/X.phn` gives `X.after.phn`:
  an import used only in a type argument, union member, return type, closure signature (both
  spellings) or `is` / `as` type stays, as one a docblock or PHP code uses does, and goes where
  the sorting puts it; an unused one goes, a block left empty is rebuilt, each import has a line of
  its own, a braced namespace keeps its indentation, and the block is laid out by the PHP code style
  exactly as in the same `.php` file (PSR-12's blank line before function imports); an alias and a
  group member stay; the notification counts what was really removed. A `.php` file gets
  PhpStorm's optimizer alone, and highlighting with "optimize imports on the fly" switched on keeps
  the imports.
- `PhaenonFormattingTest` (PSR-12 style): `.phn` files get the plugin's formatter and `.php` files
  PhpStorm's; PHP in a `.phn` file is formatted exactly as in a `.php` file (`formatting/Plain.phn`,
  tequila/core's compiled repository class with its indentation lost); `formatting/Erased.phn`, every
  erased construct with its spacing and indentation spoilt, gives `Erased.after.phn` with each
  construct as written; tequila/core's `AbstractEventSourcedRepository.phn` comes back from lost
  indentation as Reformat Code leaves the original; Enter indents the new line; an import added by
  completion gets a line of its own.
- `PhaenonDocCommentTest`: Enter after a docblock's opening over a method whose types are declared,
  erased ones included, gives an empty stub with the caret on its line; an untyped parameter keeps
  its `@param`; a typed property gets no `@var`; a `.php` file keeps PhpStorm's stub.
- `PhaenonOverridesTest`: Implement Methods and Override Methods in `testData/implement/` (an
  interface with type parameters and an abstract class passing them on, shaped after tequila/core's
  repositories) give `UserRepository.implement.after.phn`, `UserRepository.override.after.phn` and
  `Repo.after.phn`: `fun`, `protected fun`, the parent's types with the class's type arguments, a
  method's own type parameter kept, a class a type names imported; without type arguments the
  parent's spelling stays; completion lists and inserts the override items as Phaenon methods,
  completes a name after `fun` the same way, and offers `fun` once although PhpStorm's `fun` live
  template is shown; a `.php` file keeps PhpStorm's methods.
- `PhaenonPipeConversionTest`: no conversion below PHP 8.5. Against a PhpStorm with PHP 8.5
  (`-PtestIdePath`, 2026.2): PhpStorm's own fix in a `.php` file writes `Style(...)` for `Style::line(...)`
  (recorded, so the replacement can go once PhpStorm fixes it); in a `.phn` file only the plugin's
  conversion is offered and keeps the methods; functions become callables and a call with more
  arguments an arrow function.
- `PhaenonConcurrencyTest`: a statement block parses as a block and `concurrently(…)` calls stay;
  `testData/concurrency/Blocks.phn`, after tq-cli's `CiIdentityCommand`, parses blocks used as
  values and spawns as closures without a syntax error, raises no undefined or unused variable,
  return-type or unused-result finding for the enclosing scope's variables while a typo in a block
  is still undefined, types the block's value, `spawn expr` as `Amp\Future<User>` (and its
  `->await()` as `User`) and spawns written into an array as an array of Futures, and comes back
  from Reformat Code as written, from lost indentation too. The highlighter colours both keywords.
- `PhaenonReindexActivityTest`: a project whose stored PSI version is absent or older asks the
  index to redo exactly its `.phn` files and stores the current version; the current version, a
  second start and a project without `.phn` files request nothing; after a real re-index the
  classes of a `.phn` still resolve. Runs off the EDT, as the IDE runs a project activity, and
  checks that the activity's coroutine metadata is a version the bundled Kotlin library reads.
- `PhaenonRealTreeTest`: volume. Every `.phn` file under the directories named by `PHAENON_TREES`
  (colon-separated; `vendor` excluded) is added to the test project; the test fails listing
  `file:line: message` for every syntax error and every stub tree that does not match its text,
  checks that every erased `list<…>` / `map<…>` declaration got its pseudo-class, and that
  `PhaenonHierarchyInspection` reports no override in the trees (they pass `phn check`). Files using
  PHP syntax above the IDE's highest language level (`|>` on PhpStorm 2025.1) are skipped and
  listed. A second test reformats every file with the PSR-12 style and fails when an erased
  construct changed, moved off what it belongs to, or a file stopped parsing; files a second pass
  changes again are listed, not counted (PhpStorm re-indents the arguments of a call it wrapped in
  `.php` files as well). The test framework's recursion assertion is off in this test: PhpStorm's own inference
  has cycles in real code which the IDE resolves silently. Without the variable the test passes
  trivially:
`PHAENON_DEBUG_FILES=a.phn:b.phn ./gradlew test --tests '*FileDebug*'` prints each file's parse errors with line
numbers (`PhaenonFileDebugTest`), which the real-tree test only counts; read them from the test report's
`system-out`.

  ```bash
  PHAENON_TREES=~/labs/tequila/core:~/labs/tequila/mongodb-odm:~/labs/tequila/object-mapper:~/labs/tequila/reflector:~/labs/tequila/tq-cli-phaenon-dogfood \
    ./gradlew test --tests '*RealTree*'
  ```

`./gradlew test` is a no-op when nothing changed (build cache); `./gradlew test --rerun` forces a
run. After changing a bundled stub, a stale index in the test sandbox can report "Outdated stub in
index": delete `.intellijPlatform/sandbox/phaenon-jetbrains/<IDE>/system-test/index`.

## Limitations

- **Phaenon types are modelled as PhpStorm can hold them.** A `list<User>` is `User[]` plus the
  pseudo-class; a `map<K, V>` keeps its value type and loses its key type in `foreach`; a tuple is
  the union of its element arrays, so `$pair[0]` and `[$code] = $kubectl(…)` are `A|B` rather
  than `A` (PhpStorm keeps positions only for docblock shapes it reads from the PSI, and types the
  compiled `@var \Closure(string...): array{int, string}` call as `mixed`); a type parameter is its
  bound; type arguments of user generics (`Box<User>`) are dropped, so `$box->get()` has the bound
  of `Box`'s parameter. The Phaenon checker (`phn check`) is the authority on types; the editor approximates.
- **A partial application is also its callee's result to PhpStorm.** It parses as a call, and the
  extension point that types it can add the closure but not take PhpStorm's own type away:
  `format(?, 'kg')` is `string|(Closure(int): string)`. The compiler's refusals for an 8.5 target
  (a bound argument by reference, a computed receiver, …) are not reported in the editor, nor is
  `#[\Override]` on a constant that overrides nothing (`P2503`). On an 8.5 target the findings
  that stay are PhpStorm's, in its wording, not the compiler's "… needs PHP 8.6 (target 8.5)".
- **Phaenon's spelling reaches the definition line only.** Quick documentation and the Ctrl+hover
  hint print a Phaenon declaration's types as written and a variable's in Phaenon's spelling;
  PhpStorm's docblock sections below it (Parameters, Returns) are PhpStorm's and show an erased
  parameter type as missing. A variable prints what PhpStorm holds, so a `class<T>` value reads
  `class<Bound>`. Inline type hints, the completion popup's type column and inspection messages are
  PhpStorm's and print a class-string as `string`: no extension point reaches PhpStorm's type
  printer (`PhpType` presentation and its quick-doc renderer are internal), and a plugin-printed
  `class<T>` there would need one.
- **`is` and `as` do not narrow**, except `$x is Foo` on a plain class name, which PhpStorm sees as
  `instanceof`. The other forms are erased with their type, so the expression keeps its operand's
  type and a `$y = $x as map<string, Money>` is typed as `$x`.
- **Completion inside erased constructs is the plugin's own**, not PhpStorm's: short class
  names (imported on insertion), the members of a namespace after a qualifier (`Event\Dom…`,
  `\App\Event\…`), type parameters and keywords.
- **`type` aliases are erased whole**: the alias name is an unknown class wherever it is used.
- **Concurrency is approximated with PHP's own shapes** (`concurrency.md` §11). A `concurrently`
  block used as a statement is a bare block to PhpStorm, so a `return` in it reads as the
  function's. A block used as a value and a `spawn` are the closures they compile to: no PHP
  shape gives a block of statements a value other than a closure, and the immediately invoked
  closure the compiler's output amounts to (`Scope::run(function () { … })`) would need tokens
  after the closing brace that the source does not have. So PhpStorm holds a `\Closure` for
  them; the plugin adds the block's value and the task's `Amp\Future<T>` to the variables they are
  assigned to — PhpStorm types a closure without asking the type providers, so the closure
  expression itself, an argument and an array literal's element keep only the `\Closure` (a block
  passed straight to a typed parameter meets PhpStorm's parameter check as one). That typing is per
  variable, not per assignment. The closures' scopes are PhpStorm's: the filter
  drops "undefined" and "unused" findings that cross a block's boundary, a `return` is checked
  against the closure's (absent) return type, and the options of a block are erased, so a
  variable used only there is not seen.
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
- **A bare `Closure(T): U` leaves no PHP type.** It is erased whole so that it means `\Closure`
  whatever the namespace, as in the compiler; PhpStorm then adds to the `\Closure` the plugin
  supplies what it infers from assignments and bodies (the closure's own `\Closure<…>`, `mixed`
  through an untyped expression); its own encoding of a closure literal's or a first-class
  callable's variadic is an array (`string[]`, a PhpStorm defect seen in plain PHP too), and when
  it checks a call against that one, the plugin drops the finding where the argument fits the
  declared parameter. An optional parameter (`int=`) has no encoding in PhpStorm, which never asks
  for a missing argument. Quick documentation of the call shows the stub's `Closure::__invoke(...$_)`.
  `\Closure(T): U` keeps its native `\Closure`.
- **Overrides with an erased type are judged by the plugin.** No extension point gives
  PhpStorm's hierarchy check a declared type, so its findings on such overrides are dropped and
  `PhaenonHierarchyInspection` reports instead, with the types the compiler writes. A method's own
  type parameters (`fun first<U>(…)`) are compared by their bounds; overrides of vendor classes are
  checked only as far as the vendor code is indexed. Batch Inspect Code still runs PhpStorm's check
  unfiltered. A `Closure` without a signature is PHP's: it resolves through the imports and the
  namespace, as in a `.php` file.
- A `<…>` that scans as a type list is one: `f(A < B, C > ($d))` and `A < B & C > $d` with constants
  are misread, as the compiler misreads the former (`generics.md` §2.4).
- **The plugin erases Phaenon, not PHP.** PHP syntax newer than the IDE's highest language level
  is a syntax error in `.phn` exactly as in `.php`: on PhpStorm 2025.1 (levels up to PHP 8.4)
  that is the PHP 8.5 pipe operator `|>`, which breaks the enclosing class body. A newer PhpStorm
  parses it. PHP 8.6 is the exception the compiler's support makes: its placeholders are handed
  over as identifiers in both, and its other constructs are valid PHP to the parser already.
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
- **Formatting is PhpStorm's PHP code style, around erased constructs.** The text of an erased
  construct is not formatted: `fun get(): ?T` keeps whatever spacing is inside `: ?T`, and
  `type X = map<A,B>;` stays as written, since the formatter sees each construct as one token. A
  construct counts towards the line length, so a line PHP would not wrap can wrap in `.phn` (an
  `implements` list with type arguments past 120 columns). PhpStorm's PSR-12 style is not the
  php-cs-fixer configuration of the Tequila packages (concatenation and cast spacing, `fn (`, line
  wrapping), and its formatter re-indents the arguments of a call it wrapped when run a second
  time; both are so in `.php` files too. Two spots in PhpStorm compare the root node against PHP's
  own file element type by identity (top-level folding, dead-code entry points); a corner case
  there would show up as a folding difference, not as broken analysis.
- **PhpStorm's `fun` live template still expands to `function` on Tab** in a `.phn` file; the
  plugin only keeps it out of completion, where it doubled the `fun` keyword.
- **The pipe conversion replaces PhpStorm's quick-fix in the editor only.** Applying the fix from
  the Inspect Code results is PhpStorm's fix, which mistakes method calls in every file type.
- The editor features above call PHP plugin classes that are public but not documented API:
  `PhpFormattingModelBuilder.createTemplateFormattingModel` (inherited from the platform's XML
  template formatter), `PhpEnterHandlerAdapter` (the registered delegate the doc-comment handler
  hands Enter to), `PhpImplementMethodsHandler` / `PhpOverrideMethodsHandler` and
  `getMethodsToOverride`, and an `instanceof` of `PhpOverriddenMethodInsertHandler` to recognise
  override items. PhpStorm's pipe quick-fix is matched by its class name, a string, because the
  class does not exist in 2025.1.
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
  so do `PhaenonTypeText` (`PhpExpressionImpl.serializeAdvancedClosureParameter` and its separator,
  the encoding of closure signatures) and `PhaenonHighlightFilter`
  (`PhpParamsInspection.isCallTypeConvertibleFromDeclaredType`, the convertibility PhpStorm's own
  closure-call check uses). The verifier reports them compatible with 2025.1 and 2026.2.
  `PhaenonTypePresentation` decodes closure signatures with
  `PhpExpressionImpl.getTypeFromSerializedClosureParameter`, and `PhaenonDocumentationProvider`
  styles its definition with the PHP plugin's `PhpHighlightingData` keys and the platform's
  `QuickDocHighlightingHelper`, and replaces the block PhpStorm writes as `<div class='definition'><pre>`
  — when PhpStorm stops writing it, its popup is shown unchanged. `PhaenonParameterInfoHandler` finds
  PhpStorm's handler in the `codeInsight.parameterInfo` extension point by its class name, a string,
  and reproduces its presentation of a parameter (`[`optional`]`, `&`, `...`, `name: type`,
  ` = default`). The filter's PHP 8.6 messages are PhpStorm's bundle keys; the pipe's
  ("… is not a valid callable") are read only where the bundle has them (2026.2).

## VS Code

`textmate/phaenon` is laid out as a VS Code extension (`package.json` with `contributes.languages`
and `contributes.grammars`). Copying that directory into `~/.vscode/extensions/phaenon` gives `.phn`
files PHP highlighting in VS Code.

## License

MIT, see `LICENSE`.
