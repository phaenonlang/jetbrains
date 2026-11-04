# Phaenon for JetBrains IDEs

PhpStorm support for [Phaenon](https://github.com/phaenonlang) source files (`.phn`), a typed dialect
of PHP that compiles to PHP.

A Phaenon file is PHP code from the first byte: no `<?php` open tag, no `?>`, no inline HTML, and
`declare(strict_types=1)` is implied. This plugin teaches PhpStorm that contract, so `.phn` files get
the **full PHP toolchain** rather than a syntax-colouring approximation of it:

- highlighting, code folding, brace matching, and PhpStorm's PHP formatting in Phaenon's canonical
  layout (opening braces of functions and methods on their line, of class-likes on a line of their
  own, an empty body `{}`): Reformat Code, auto-indent, docblock stubs, and the layout of
  code an action inserts
- completion, parameter info, quick documentation — with Phaenon's types printed as Phaenon writes them
  (`class<T>`, `list<User>`, `Closure(int): string`)
- go to declaration / usages / implementations across `.phn` and `.php` files, in both directions
- refactorings (rename, move, extract, ...)
- every PHP inspection and intention, plus one Phaenon-specific error for stray `<?php` / `?>` tags
- **debugging `.phn` with Xdebug** — breakpoints in the source, the current line and the frames in
  the source, stepping by source line — and every position in compiled code (a stack trace in a
  console, a PHPUnit failure, a tool's finding) opening the `.phn` at its line, through the line map
  each compiled file carries ([Line map](#line-map-navigation-and-debugging))

Phaenon's own syntax parses too — generics, `list<T>`, `map<K, V>`, `bytes`, tuples, `class<T>`,
`Closure(T): U`, `is` / `as`, `type` aliases, expression bodies `=> expr;` and `throws A|B`, and PHP
8.6's partial application, which no PhpStorm parses yet — and its types reach PhpStorm: a
`list<User>` is a `User[]` to completion and the inspections, a `class<User>` names `User` behind
`::` and `new`, a `Closure(int): string` checks the calls made through it. A list, a map and bytes
are the array and the string they compile to, so `->` on them means what it means in PHP: they are
transformed with PHP's functions and chained with pipes and partial application.

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
| `PhaenonLexer` | the parser's lexer | PhpStorm's `PhpWithOptionalOpenTagLexer` (starts in scripting mode unless the first token is an open tag) wrapped in a `LookAheadLexer` that erases Phaenon-only syntax into `PhaenonTokenTypes.ERASED` tokens — comments to the parser (`PhaenonParserDefinition` adds the type to PHP's comment set), text and offsets untouched. See [Phaenon syntax](#phaenon-syntax). `fun` before a name is handed over as the `function` keyword (text kept), so a `fun` method parses as a method and, without a visibility, as public — as PHP reads it and as the compiler writes it (design/functions.md §1). A PHP 8.6 placeholder `?` standing alone as an argument is handed over as an identifier, so `f(?, 1)` parses as a call with a constant for an argument (see [PHP 8.6](#php-86)). `concurrently` used as a value and `spawn` are handed over as the closures they compile to, their own characters split into `function ( )` and `fn ( ) =>` tokens (`PhaenonBlocks`). A named function's `throws A\|B` is erased whole, and its expression body's arrow is handed over as `{` and `return` (see [D18](#d18-expression-bodies-throws-layout)). |
| `PhaenonPsiBuilder` | the parser's `PsiBuilder` | A `PsiBuilderAdapter` that intercepts PhpStorm's lazy function bodies and parses them on the spot, so the whole file goes through one lexer. PhpStorm expands a lazy block by re-lexing its text with a plain PHP lexer, which would discard the erasures inside every body. An expression body's block, which the lexer opens at the arrow, it closes after its one statement: the source has no `}` for it. |
| `PhaenonBaseLexerProvider` | `com.jetbrains.php.baseLexerProvider` | Editor highlighting does not go through the parser: PhpStorm's editor highlighter is keyed by file type and builds its own lexer, asking this EP first. It gets the plain open-tag-optional lexer, so Phaenon syntax is coloured as the PHP tokens it is made of. Wrapped in `PhaenonHighlightingLexer`, which colours `fun` as the keyword where it declares something. |
| `PhaenonTypeProvider` | `com.jetbrains.php.typeProvider4` | Reads the erased Phaenon types back from the erased leaves (`PhaenonErasedTypes`, `PhaenonTypeSyntax`, `PhaenonTypeText`) and gives parameters, properties and return types their types: `list<User>` and `map<string, User>` → `User[]`, `bytes` → `string`, the array and the string they erase to; a type parameter → its bound, as the compiler writes it; `class<T>` → `class-string<Bound>` alone, the type PhpStorm gives a docblock-only `@return class-string<Bound>`, on which `$c::m()`, `new $c` and `$c::CONST` resolve; `Closure(A, B=, C...): R` → `\Closure` plus the signature in PhpStorm's own encoding. Types a partial application as the closure it creates and a pipe stage that is one as the call it becomes. |
| `PhaenonErasedReferenceContributor` | `psi.referenceContributor` for PHP | Every erased construct is a `PhaenonErasedLeaf` (a comment leaf the token type creates itself), and each class name in it — `DomainEvent` in `list<DomainEvent>`, `User` in `Box<User>`, `Closure` in `Closure(int): string` — gets a `PhaenonClassReference` resolved as PHP would through the file's imports and namespace (`PhaenonErasedNames`), except a bare `Closure` before a signature, which is `\Closure` in any namespace, as the compiler reads it. Navigation, find-usages, rename and the unused-import inspection see them as they see a docblock type. A type parameter used in one (`T` in `id(): T`) gets a `PhaenonTypeParameterReference` to its declaration in the enclosing `<T of …>` list (`PhaenonTypeParameter`, a stand-in element at the name's offset). The declaration itself is one to the platform (`PhaenonTypeParameterDeclarations`, `psi.declarationProvider`): Cmd+click on it shows the usages, found by the default find-usages handler plus `PhaenonDocTypeReferenceSearcher`, which adds the docblock types naming it (`@var T $x`); such a docblock type also navigates to the declaration and is not an "undefined class". |
| `PhaenonImportOptimizer` | `lang.importOptimizer` for PHP, `order="first"` | Optimize Imports in `.phn` files — the action, Reformat Code with imports, the save action and the pre-commit step all take the first optimizer of the language that supports a file. Runs PhpStorm's optimizer, which counts only PHP's own references and would remove `use App\Event\DomainEvent;` that only `list<DomainEvent>` uses, then puts back every import an erased construct uses (the rule the unused-import filter follows) where the optimizer's sorting places it. The PHP code style lays the block out, as in a `.php` file. `.php` files keep PhpStorm's optimizer alone; the PHP plugin has no import optimization on the fly. |
| `PhaenonFormattingModelBuilder` | `lang.formatter` for PHP, a `CustomFormattingModelBuilder` | The formatter of `.phn` files. PhpStorm's `PhpFormattingModelBuilder` is a template formatter: for a `.php` file it builds the HTML model of the template view provider and asks for PHP blocks inside it, and for any other view provider it falls back to one read-only block for the whole file — which is what a `.phn` file, single-rooted, got: Reformat Code, the indent of a new line, the `*` of a docblock and the reformatting after generated code all did nothing. The plugin's builder, engaged for `.phn` files only, hands the file's PSI to the PHP builder's public `createTemplateFormattingModel`, the call the template path makes for each PHP region, so every rule is PhpStorm's with the project's PHP code style. Its blocks are wrapped (`PhaenonFormattingBlock`) to decide the spacing next to an erased construct, which PhpStorm's rules see as a token of unknown kind: a `<…>` list stays against its name and the `(` of a call, an erased return type against the `)`, a union member against its neighbour; a standalone construct (`list<User> $users`, `$x as T`, `spawn $task`) is one space from its neighbours or keeps its line breaks, and an erased type keeps the wrap and alignment of the parameter it types. The construct's own text is never changed. Two blocks that meet inside a word — a keyword split into PHP tokens — stay together, and a `concurrently` block used as a statement is laid out as one statement with its body one level in, as an `if`'s. The PHP code style is the project's with one change, Phaenon's canonical layout: the opening braces of functions, methods and closures stand on the declaration's line, a class-like's on a line of its own, an empty body of a function, method or class-like — nothing but whitespace between its braces — is `{}` one space after the declaration or header (`ErasedSpacing.emptyBody`; PhpStorm's own options for empty bodies would reach closures and anonymous classes, which stay as the project's style lays them out), and an expression body stays one space after its signature or on a line of its own one level in. |
| `PhaenonDocCommentEnterHandler` | `enterHandlerDelegate`, `order="before phpEnterHandler"` | Enter after the opening of a docblock in a `.phn` file: PhpStorm's registered PHP enter handler writes its stub, then the `@param` of every parameter with a type, the `@return` of a declared return type and the `@var` of a typed property are taken out, erased types included (PhpStorm took `class<T>` for no type and wrote `@return mixed`). Untyped parameters keep PhpStorm's tags; a `@throws` goes when the signature's `throws` clause names its exception; a stub of one line or none is one line, `/** <caret> */`, whatever it documents (design/functions.md §5). |
| `PhaenonImplementMethodsHandler`, `PhaenonOverrideMethodsHandler` (`PhaenonOverrides`) | `codeInsight.implementMethod` / `codeInsight.overrideMethod` for PHP, `order="first"` | Code \| Implement Methods and Override Methods: PhpStorm's (its member chooser, bodies, docblock options, formatting, imports), then each generated method of a `.phn` class rewritten: `fun`, `protected fun`, `static` kept; every parameter type and return type the parent declared with an erased part taken from the parent's source; the parent's type parameters replaced by the class's type arguments, followed through every `extends` / `implements` in between (`fun get(Id $id): ?T` in a class that `extends AbstractEventSourcedRepository<UserId, User>` is `fun get(UserId $id): ?User`); class names of those types imported or qualified for the class's file; the parent's `throws` clause, and the bare `@throws` tags of a PHP parent's docblock, written as the method's `throws` clause; the docblock left on one line when one line of content is left, gone when none is; the brace on the declaration's line by the formatter. The platform asks only the first handler of a language, so for other PHP files these are PhpStorm's handlers. |
| `PhaenonErasedCompletionContributor` | `completion.contributor` for PHP, `order="first, before liveTemplates"` | Completion inside an erased construct: the project's classes, interfaces and traits (a class from another namespace gets its `use` statement on insertion), the type parameters in scope, and the type keywords; after a qualifier, the classes and child namespaces of that namespace; after `throws`, only the Throwables, the type parameters and `self` / `static` / `parent`. The dummy identifier is the trimmed one, so the copy still scans as a type list. Also offers `fun` where a declaration may start in a `.phn` file, and sees the items of every contributor after it there: PhpStorm's override and implement items are shown and inserted as Phaenon methods (`fun get(UserId $id): ?User {...}`, rewritten as by the handlers above), and PhpStorm's bundled PHP live template `fun`, which showed as a second `fun` and expands to `function`, is left out. It comes before the live-template contributor, which wraps the contributors after it. |
| `PhaenonSpellcheckingStrategy` | `spellchecker.support` for PHP | An erased leaf is a comment to the platform, which would spell-check it as prose; it is code and is not checked, as PhpStorm checks no class reference. |
| `PhaenonThrows` | used by the filter and the doc-comment handler | Whether the `throws` clauses in effect answer an exception finding of PhpStorm's as the compiled `@throws` tags would: the function's own clause, an enclosing function's for a closure (a `concurrently` block or a `spawn` too), and for a method with neither a docblock nor a clause the clause of the method it overrides, the way PhpStorm reads a parent's tags. The exceptions are the ones PhpStorm's inspections compute for the site or the function. |
| `PhaenonBlocks` | used by the type provider, the filter and the formatter | Knows the closures `PhaenonLexer` makes of `concurrently` blocks used as values and of `spawn` (`function ( )` and `fn ( ) =>` spelt with the keyword's own characters), the function a variable really belongs to through them, and which variables they are assigned to. |
| `PhaenonDocumentationProvider` | `lang.documentationProvider` for PHP, `order="first"` | The definition line of quick documentation and of the Ctrl+hover hint for Phaenon elements, where PhpStorm prints the erasure: PhpStorm prints every class-string as `string` (and a native `string` beside a docblock class-string as `string\|string` in 2026.2), drops an erased parameter type from a signature and pastes an erased return type in raw, its `<T>` read as an HTML tag. A function or method of a `.phn` with an erased part in its signature prints as written (`abstract protected static fun aggregateClass(): class<T>`), a parameter or property with an erased type as `$users: class<User>`, and a variable of a `.phn` whose type holds a class-string or a closure signature as that type in Phaenon's spelling (`$class: class<EventSourcedAggregate>`, `PhaenonTypePresentation`). The rest of the popup — the class, the docblock sections, the source — is PhpStorm's; `.php` files and everything else are PhpStorm's alone. |
| `PhaenonParameterInfoHandler` | `codeInsight.parameterInfo` for PHP, `order="first"` | Parameter info for a call of a Phaenon function with an erased parameter type, PhpStorm's layout with the type as written: `users: class<User>, id: \App\AggregateId` where PhpStorm prints `users: string`. It takes a call only when one of its targets is such a function and hands the rest — finding the call, the current argument, named arguments — to PhpStorm's handler. |
| `PhaenonGotoDeclarationHandler` | `gotoDeclarationHandler` | Sends a reference that resolves both to a `.phn` declaration and to the `.php` compiled from it (`@generated by phaenon` in its header) to the source only, from `.phn` and `.php` files alike; a hand-written `.php` with the same class stays a target. |
| `PhaenonHighlightFilter` | `daemon.highlightInfoFilter` | Drops PhpStorm's "readonly property must have type" error and the "missing type declaration" inspections for properties, parameters and return types where the type was erased: it had one. Drops "Other declarations of class 'X' exist at X.php" when every other declaration is the compiled copy, and "Import 'X' is never used" when the import's name appears in an erased type. "Return type declaration must be compatible with…" and "Declaration must be compatible with…" are dropped on an override where either side has an erased type, in a `.phn` or in a `.php` implementing a Phaenon interface: PhpStorm reads `save(T $aggregate)` as `save(mixed $aggregate)`, and `PhaenonHierarchyInspection` judges the pair instead. PhpStorm's "Parameter type 'string' is not compatible with 'string[]'" on a call of a `Closure(string...)` property is dropped where the argument fits the declared Phaenon parameter (PhpStorm encodes the variadic of a closure literal or first-class callable assigned to it as an array). The PSR-12 "missing visibility" finding is dropped on a `fun` method, a class constant, and a property or parameter whose only visibility is `private(set)`-style, all public by the language's rule and written out by the compiler; the annotator's "expression is not allowed as …" findings are dropped on a closure in an attribute argument, a constant or a default, which the compiler lowers to PHP 8.5's `static function`. PhpStorm's findings on PHP 8.6 code go as [PHP 8.6](#php-86) describes, some only where the file's target (`PhaenonTarget`) is 8.6. In a `concurrently` block or a `spawn`, closures to PhpStorm without a `use` clause, an "undefined variable" the enclosing function defines (or a block assigns before it is read after), an "unused" parameter or variable used across a block's boundary, "Return value is expected to be …" on `return concurrently { … };` and whatever PhpStorm reports at the keyword are dropped. "Unhandled X" and "Unhandled exceptions" go where the `throws` clauses in effect name every exception reported, or a parent of it (`PhaenonThrows`), and "PHPDoc comment doesn't contain all the necessary @throws tags" where they name what the docblock lacks. |
| `PhaenonHierarchyInspection` | `localInspection` for PHP, "Phaenon override compatibility" | Signature compatibility for overrides with an erased type on either side, in PhpStorm's words ("Declaration must be compatible with AggregateRepository->save(aggregate: \App\Aggregate)"). PhpStorm's own check compares `Parameter.getDeclaredType()`, read from the PSI's type declaration or its stub, which no extension point reaches; this one reads the parent's signature as the child sees it, the parent's type parameters replaced by the arguments written on the `extends` / `implements` path (`PhaenonErasure`), and compares as the checker does (`P2502`): a type parameter by its bound, a native child type equal to the parent's erasure as inheriting the parent's type (`generics.md` §11.1), parameters contravariant, returns covariant, counts, optional, variadic and by-reference parameters as in PHP. What passes compiles: the override-erasure rule (§4.3) widens the child's parameter to the parent's erased type. |
| `PhaenonExcludePolicy` | `directoryIndexExcludePolicy` | Excludes the output directories of every `phaenon.json` — at each content root and in each `vendor/*/*` package — from the project, so the compiled tree is never indexed and a class is declared once, in its source. `PhaenonProjectFileListener` re-applies it when a project file changes. |
| `PhaenonLineMap`, `PhaenonLineMaps` | used by the debugger and navigation | The line map a compiled file ends with (design/line-map.md §3): parsed, and both lookups — forward (compiled line → source line) and reverse (source line → the first compiled line of a run covering it, never a hold). `PhaenonLineMaps` reads a compiled file's map from disk (so a tree `phn build` just rewrote is right before the IDE refreshes) once per modification time and size, resolves its source path lexically, and finds the compiled file of a source as the compiler names it: the mirrored path in its root's output (`phaenon.json`), or beside it — only when that file's map names the source back. Tested against the compiler's reference vectors. |
| `PhaenonDebugPathMapper` | `com.intellij.php.debug.templateLanguage` | PhpStorm's template debugging, the mechanism of Twig and Blade, with the line map: a frame in a compiled file is shown in the `.phn` at the forward line (`mapToTemplate`, as a `PhaenonMappedPosition` that remembers the compiled line), a breakpoint in a `.phn` goes to the compiled file at the reverse line (`mapToPhp`). See [Line map](#line-map-navigation-and-debugging) for how it is gated. |
| `PhaenonSourceStepping`, `PhaenonStepActionListener` | `XDebuggerManagerListener`, `AnActionListener` | Stepping by source line: a Step Over or Step Into that lands on a hold of the line it started from, in the same frame, is repeated. The step taken is read from the debugger action that resumed the session. |
| `PhaenonCompiledLinkFilter` | `consoleFilterProvider` (`order="first"`), `analyzeStacktraceFilter` | Links `path.php:42`, `path.php(42)` and `in path.php on line 42` of compiled files in consoles, the terminal and Analyze Stack Trace to the `.phn` at the forward line, ahead of PhpStorm's own link on the same text. |
| `PhaenonCompiledEditorProvider` | `fileEditorProvider` | Compiled files with a map open in PhpStorm's text editor wrapped so that a navigation to a position in them — from anywhere but their own focused editor — opens the `.phn` at the forward line instead. |
| `PhaenonCompiledNotificationProvider` | `editorNotificationProvider` | A banner on a compiled file: its source, a link to the source at the caret's line, a warning when the source changed after the build. |
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
| `list<T>` | erased | `T[]` |
| `map<K, V>` | erased | `V[]` |
| `bytes` | erased | `string` |
| `(A, B)` tuple | erased | `A[]\|B[]` |
| `class<T>` | erased | `class-string<Bound>` (`T`'s bound; `class<User>` names `User`, `class<A\|B>` is `class-string<A,B>`), printed `class<…>` |
| `fun name(): T => expr;` (D18) | `{ return expr;` — the arrow split into `{` and `return`, closed after the statement; `{ expr;` under `void` and `never` | the declared return type; the value is checked as a `return`'s |
| `throws A\|B` after the parameters or the return type (D18) | erased | — (the names are references) |
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

## D18: expression bodies, `throws`, layout

`fun name(): string => $this->name;`, `fun load(Id $id): T throws NotFound` and the opening brace on
the declaration's line (design/functions.md §3–§5). No PhpStorm parses the first two: an arrow
after a signature and a name after a return type are syntax errors in any PHP file.

- **Expression bodies** reach the parser as the block they compile to: the arrow's two characters
  become `{` and `return` (`{` and an erased `>` under `void` and `never`), and `PhaenonPsiBuilder`
  closes the block after its statement. So the expression is code — navigation, completion,
  inspections, types — its value is checked as a `return` is (PhpStorm marks the statement from
  the arrow's `>` on), nothing reports a missing `return`, and the return type is the declared
  one, erased types included. The arrow is recognised only after a named function's signature; an
  arrow function's `fn () =>` and an array's `=>` stay PHP's, below a signature still being typed
  too: the first token no return type has ends it.
- **`throws A|B`** after a named function's parameters or return type, before `{`, `=>` or `;`, is
  erased whole, so its names are references like any erased type's: navigation, find usages,
  rename, and imports counted as used by Optimize Imports and the unused-import inspection.
  Completion after `throws` offers the Throwables. Elsewhere `throws` is a name. PhpStorm's
  exception analysis reads `@throws` tags only and has no extension point; the plugin cannot feed
  it the clause, so it drops the findings the compiled tags would answer: "Unhandled X" where the
  clauses in effect name X or a parent of it — the function's own, an enclosing function's for a
  closure, a parent method's for a method with neither a docblock nor a clause, as PhpStorm reads
  tags — and the missing-`@throws` finding where they name what the docblock lacks. A call of a
  function whose clause names an exception is not reported as unhandled: PhpStorm does not know
  the callee throws.
- **The canonical layout** is the `.phn` code style: the formatter is handed the project's PHP code
  style with the brace placement of functions, methods and closures set to the end of the
  declaration's line and that of class-likes to the next line (amended with D19,
  design/functions.md §5), so Reformat Code, the indent of Enter and generated code (Implement /
  Override Methods, override completion) put them there. An empty body is `{}` on the declaration's
  line (§5.1, as PER-CS 2.0 writes it): a function or method without statements, after a
  multi-line parameter list as `) {}`, and a class, interface, trait or enum without members, after
  its header (`final class AggregateNotFound extends \DomainException {}`, `enum Level: int {}`).
  Reformat joins `{` … `}` whichever line the brace was on; a body with a comment, a statement or a
  member keeps its lines, and so does one whose brace follows a line comment; closures and
  anonymous classes are laid out as in PHP. Complete Current
  Statement and Enter between `{}` still open the body to type into. A wrapped arrow
  stays on its line, one level in; Enter before an arrow wraps it so. Docblock stubs and generated
  overrides' docblocks are one line when they hold one; Reformat does not collapse a written
  docblock.

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

## Line map: navigation and debugging

Since D19 (design/line-map.md) `phn build` lays out what it generates as PER Coding Style PHP
(PSR-12 until D22) — the header on lines of its own, expanded `=> expr` bodies, multi-line
docblocks, braces where PER puts them —
so line N of `.src/Foo.php` is no longer line N of `src/Foo.phn`. Every compiled file ends with a
line map instead:

```php
// phaenon-map v1 ../src/Domain/Foo.phn 1-6=1 7:1 13-15=6 16:7
```

`O:S` is a run (output line `O` is source line `S`, the following lines follow one for one), `O-P=S`
a hold (the lines the compiler wrote for source line `S`). The plugin reads the map of a compiled
file when a position in it is needed (`PhaenonLineMaps`), and does the rest with PhpStorm's own
machinery:

- **Console links.** `/app/.src/Foo.php:42` (PHPUnit, PHPStan's raw format, php-cs-fixer),
  `/app/.src/Foo.php(42)` (`getTraceAsString()`) and `in /app/.src/Foo.php on line 42` (PHP's
  warnings and errors) link to the `.phn` at the mapped line, in Run, Debug and Test consoles, the
  terminal and Analyze Stack Trace. The filter runs first, and the platform keeps the first link on
  a stretch of text, so it replaces PhpStorm's link to the compiled line; a path the plugin cannot
  resolve (a container's `/app/…`) keeps PhpStorm's link, mapped through the server's path
  mappings, and the next point takes over.
- **Opening a compiled file at a position** — PhpStorm's own console links, the PHPUnit test tree
  and its failed line (PHPUnit reports the compiled file it ran), inspection and quality-tool
  results, Find Usages, Navigate | File with `Foo.php:42` — opens the `.phn` at the mapped line: all of
  them end in the opened editor's `navigateTo`, and compiled files with a map open in PhpStorm's text
  editor wrapped (`PhaenonCompiledEditorProvider`). A compiled file opened without a position (the
  Project view) shows as itself, with a banner naming its source and linking to it at the caret's
  line (and a warning when the source changed after the build); a navigation inside its focused
  editor stays there.
- **Breakpoints** are set in the `.phn`. When a debug session registers one, it goes to Xdebug as a
  breakpoint on the compiled file at the reverse-mapped line — the first compiled line of the source
  line, never a docblock or brace the compiler wrote; Xdebug moves a breakpoint on a signature to the
  body's first statement, which maps back to the same source line. A `.phn` that has no compiled
  file with a map yet gets PhpStorm's "No mapping to PHP line" on its breakpoints. Conditions, log
  expressions and "suspend" apply as written: PhpStorm recognises a hit by the stop's mapped
  position.
- **The current position and the frames** of compiled files show in the `.phn` at the mapped
  line: the execution point, the editor's inline values, each frame the Frames list navigates to.
  Variables, watches and Evaluate are PhpStorm's and work unchanged — the compiled code keeps every
  identifier (D9); a watch is edited against the `.phn`.
- **Stepping is by source line.** A Step Over or Step Into that stops on a line the compiler wrote
  for the line it started from, in the same frame, steps again: a void expression body
  `fun log(string $m): void => …;` stops on its statement and on its compiled closing brace, and the
  second stop is not shown. A stop on a line of plain PHP is shown as in a `.php`, even on the same
  line (a loop on one line stops once per iteration).

Nothing needs configuring beyond PHP debugging itself: the run configuration runs the compiled code
(PHPUnit with the compiled tests, `bin/console`, a web server), Xdebug 3 is loaded, and a remote
server's path mappings map the project root, which holds the sources and the output directories
alike.

How the debugger is reached: PhpStorm debugs Twig and Blade through the PHP plugin's extension
point `com.intellij.php.debug.templateLanguage` (`PhpTemplateLanguagePathMapper`). PhpStorm builds
every frame through it (`mapToTemplate`) and maps a breakpoint through it (`mapToPhp`) when the
mapper accepts the breakpoint file's *type*. A `.phn` is of the PHP file type, and the breakpoint
gutter asks the same question about every `.php` (a yes would allow a breakpoint on any line of
it), so `PhaenonDebugPathMapper` says yes only to the breakpoint handler's question about a `.phn`:
`PhpLineBreakpointHandler.registerBreakpoint` must be the caller, and the file looked up just before
on the same thread a `.phn`. A breakpoint type of its own would reach no handler of PhpStorm's PHP
debugger, and Xdebug 3.5's own path maps (`xdebug.path_mapping`) would need Xdebug 3.5 and an ini
setting wherever the code runs, and still step by compiled line.

## The checker in the editor: `phn lsp`

With a `phn` at hand, the plugin starts the compiler's language server, `phn lsp`, when a `.phn`
opens (design/tooling.md §6.2, §6.5):
- **Its findings show as you type**, in the file being edited, against the whole project with the
  open buffers as they are. A save re-checks the whole project. P1005, a tab where Phaenon wants
  spaces, comes with a quick-fix.
- **Build on save** writes the outputs after each save, as `phn build` does; a file with errors is
  withheld. The status bar shows the last build (`phn: built`, `phn: 2 of 800 withheld`).
- **The server answers nothing else.** The plugin asks for diagnostics only
  (`"features": "diagnostics"`), and the server then advertises nothing else. So navigation,
  completion, hover, refactoring and formatting stay PhpStorm's, through the plugin's PSI, in
  every PhpStorm version.

The `phn` is, in order, the setting under **Settings | Languages & Frameworks | Phaenon**,
`$PHN_BINARY`, the project's `vendor/bin/phn` (the compiler the project builds with, from the
`phaenon/phn` Composer package), or `phn` on the PATH. The same page turns build on save off. The
LSP client API is the commercial IDEs' (`com.intellij.modules.ultimate`): elsewhere the plugin works
without the server.

**Reformat Code** writes Phaenon's layout. It is PhpStorm's PHP style with Phaenon's braces, and
`phn fmt --stdin` (the first layer of `phn fmt`) runs before and after it:
- before, so PhpStorm's line wrapping measures the spacing Phaenon writes;
- after, as an edit of its own.

That layer lays out what only Phaenon knows: the spacing inside `<T of Item>`,
`map<string, list<int>>`, `throws`, `as?`, one-line docblocks, no blank line before the first
line, and spaces for tabs. Only a reformat of the whole file runs it. A `.phn` always indents with
spaces (D21), whatever the PHP code style or an `.editorconfig` says.

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

The layout tests run `phn fmt --stdin` and are skipped without a compiler; with one they also
reformat the compiler's `internal/format/testdata` goldens:

```bash
PHAENON_PHN=../phaenon/bin/phn ./gradlew test --tests '*PhaenonLayoutTest*'   # go build -o bin/phn ./cmd/phn there first
```

To run the debugger tests against a real Xdebug (built for the local `php`, loaded only with
`-d zend_extension`, never through a php.ini):

```bash
PHAENON_XDEBUG=/path/to/xdebug.so ./gradlew test --tests '*Xdebug*'
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
- `PhaenonErasedTypesTest`: erased declarations get their types (`list<User>` and
  `map<string, User>` as `User[]`, `bytes` as `string`, tuples, `class<User>`, closure signatures,
  type parameters); `[0]` and `foreach` yield the element type; a method call on a `string`, a
  `list` or `bytes` resolves to nothing, completes nothing and is judged as PhpStorm judges a call
  on a string in a `.php` file (D20); erased types are not missing type declarations, do not make
  an override incompatible, and `readonly` properties with them are not errors (`testData/types/`).
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
  `new`, constants and `::class` resolve against the class, a class-string passes for a `string`;
  hover, quick documentation and Ctrl+hover show `aggregateClass(): class<T>`,
  `$class: class<EventSourcedAggregate>`, `$users: class<User>`; Ctrl+hover and Ctrl+click on the
  getter land on the method; parameter info prints `users: class<User>`; a `.php` file keeps
  PhpStorm's presentation (`classstring/Plain.php` records it: `string`, and `string|string` with a
  native `string` on 2026.2).
- `PhaenonDocumentationTest`: `testData/docs/Types.phn`: declarations print their written types,
  variables `?class<User>` and `Closure(int): string`, while a variable holding a list, a map or
  bytes is PhpStorm's (`User[]`, `string`); a function prints its signature as written, one
  parameter per line in the popup, and keeps PhpStorm's docblock sections.
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
  PhpStorm's; PHP in a `.phn` file is formatted exactly as in a `.php` file formatted with the
  canonical brace placement (`formatting/Plain.phn`, tequila/core's compiled repository class with
  its indentation lost); functions', methods' and closures' braces move to their line in a `.phn`,
  a class's, an interface's, a trait's and an enum's to a line of their own (an anonymous class
  keeps its brace on the line), and a `.php` keeps PSR-12's; an empty function or method body is
  `{}` on the declaration's line from either brace placement, `{ }` and after a multi-line
  parameter list or a `throws` clause, while a body with a comment or a statement, a body after a
  line comment and a closure keep their lines, and an abstract method stays; an empty class-like is
  `{}` on its header's line — with type parameters and a generic parent, a backing type, an
  attribute, from either brace placement — while one with a member or a comment, or after a line
  comment, keeps its brace on a line of its own and an anonymous class is PhpStorm's; under
  PhpStorm's PER style the result is the same; `formatting/Erased.phn`, every
  erased construct with its spacing and indentation spoilt, gives `Erased.after.phn` with each
  construct as written; tequila/core's `AbstractEventSourcedRepository.phn` comes back from lost
  indentation as Reformat Code leaves the original; Enter indents the new line; an import added by
  completion gets a line of its own.
- `PhaenonDocCommentTest`: Enter after a docblock's opening over a method whose types are declared,
  erased ones included, gives `/** <caret> */`; an untyped parameter keeps its `@param`, on one line;
  a typed property gets no `@var`; a `throws` clause takes PhpStorm's `@throws` for the exceptions
  it names and leaves the others; a class's empty stub is one line; a `.php` file keeps PhpStorm's
  stub.
- `PhaenonOverridesTest`: Implement Methods and Override Methods in `testData/implement/` (an
  interface with type parameters and an abstract class passing them on, with expression bodies,
  shaped after tequila/core's repositories) give `UserRepository.implement.after.phn`, `UserRepository.override.after.phn` and
  `Repo.after.phn`: `fun`, `protected fun`, the parent's types with the class's type arguments, a
  method's own type parameter kept, a class a type names imported, the parent's `throws` clause and
  a PHP parent's bare `@throws` written as a clause, braces on the declarations' lines, a docblock of
  one line on one line, a generated body with nothing in it as `{}` (an empty body template); without
  type arguments the
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
- `PhaenonD18Test`: `testData/d18/Bodies.phn` — an interface, a trait, an enum, a class and top-level
  functions with expression bodies and `throws` clauses, attributes and docblocks around them —
  parses without a syntax error; each expression body is one `return` (one statement under `void`
  and `never`), the return type stays declared (erased types too) and the expression resolves; a
  wrong value is PhpStorm's "Return value is expected to be …", and nothing else is reported (no
  missing `return`, no value under `void` or `never`); completion works in the body; the clause's
  names are found by find usages, renamed with their class and keep their imports through Optimize
  Imports and the unused-import inspection; completion after `throws` offers Throwables only; an
  "Unhandled" exception the clause names is not reported and one it does not name is, in a closure
  the enclosing function's clause answers, a method without a docblock inherits the clause of the
  method it implements and one with a docblock does not, and a call reported for two exceptions
  stays unless the clause names both; a missing-`@throws` finding stays only for what the clause
  does not name; Reformat Code gives the fixture back from itself, from lost indentation and from
  PSR-12 braces; Enter before an arrow wraps it one level in, Complete Current Statement opens a
  body on the declaration's line (left open, not `{}`), and Enter between the braces of `{}` opens
  the body; the hint shows the clause; a `.php` file keeps its syntax errors.
- `PhaenonLineMapTest`: the reader against `src/test/resources/linemap/vectors.json`, a verbatim
  copy of the compiler's `internal/linemap/testdata/vectors.json` (the compiler's freshness test
  fails when they differ; refresh it by copying): every valid map's source, forward and reverse
  queries, every unsupported version and every malformed map; holds, the inline-HTML spelling, a
  trailing `\r`, the map before `__halt_compiler()`. `PhaenonLineMapsTest`: `testData/linemap/` —
  a project with `phaenon.json` (`src/Domain/Greeter.phn` and its hand-compiled
  `.src/Domain/Greeter.php`, laid out as design/line-map.md §2 says), a compiled file beside its
  source, and a map naming another source — resolve in both directions, and a rebuilt file is read
  again.
- `PhaenonDebugTest`: the template mapper claims `.phn` files and mapped compiled files only; a stop
  in the compiled file maps to the `.phn` line (and remembers the compiled line and whether it is a
  hold), a breakpoint in the `.phn` to the compiled file's reverse line or stays unmapped; asked from
  anywhere but the breakpoint handler, the PHP file type is no template type and a `.php` keeps
  PhpStorm's breakpoint rules (no breakpoint on a blank line); asked from a
  `PhpLineBreakpointHandler`'s `registerBreakpoint`, a `.phn` is mapped and a compiled or plain
  `.php` is not. `PhaenonSteppingTest`: when a step is repeated.
- `PhaenonLspTest`: the server is the project's compiler, `phn lsp` in the project's directory,
  asked for diagnostics only, with build on save as the setting says; the compiler is the setting,
  else the project's `vendor/bin/phn`; the project is the nearest `phaenon.json`; a build on save
  reads in the status bar.
- `PhaenonLayoutTest`: a `.phn` indents with spaces when the PHP style uses tabs (a `.php` keeps
  them); without a `phn`, Reformat Code is PhpStorm's alone; with one (`PHAENON_PHN`), it writes the
  first layer, a reformat of a range leaves the rest alone, and the compiler's format goldens and
  their inputs reformat to one text, in Phaenon's layout, differing from the golden in PHP's
  whitespace only.
- `PhaenonXdebugTest`: the line map against a real Xdebug, over DBGp, with `PHAENON_XDEBUG` set to an
  `xdebug.so` (loaded with `php -n -d zend_extension=…` only; `PHAENON_PHP` picks the php): the
  breakpoints the mapper sends stop at lines that map back to their source lines, and Step Into and
  Step Over through `testData/linemap/project`, repeated as the IDE repeats them, show each source
  line once. Passes trivially without the variable.
- `PhaenonNavigationTest`: the three spellings of a compiled position in console text link to the
  `.phn` at the mapped line, ahead of PhpStorm's own filter on the same text; other `.php` files are
  left alone; opening the compiled file at a line, and PHPUnit's test location of a compiled test,
  land in the `.phn`; the banner names the source.
- `PhaenonReindexActivityTest`: a project whose stored PSI version is absent or older asks the
  index to redo exactly its `.phn` files and stores the current version; the current version, a
  second start and a project without `.phn` files request nothing; after a real re-index the
  classes of a `.phn` still resolve. Runs off the EDT, as the IDE runs a project activity, and
  checks that the activity's coroutine metadata is a version the bundled Kotlin library reads.
- `PhaenonRealTreeTest`: volume. Every `.phn` file under the directories named by `PHAENON_TREES`
  (colon-separated; `vendor` excluded) is added to the test project; the test fails listing
  `file:line: message` for every syntax error and every stub tree that does not match its text,
  checks that every erased `list<…>` / `map<…>` declaration is typed as an array, and that
  `PhaenonHierarchyInspection` reports no override in the trees (they pass `phn check`). Files using
  PHP syntax above the IDE's highest language level (`|>` on PhpStorm 2025.1) are skipped and
  listed. A second test reformats every file with the PSR-12 style and fails when an erased
  construct changed, moved off what it belongs to, or a file stopped parsing, when a function's or
  method's opening brace is left on a line of its own (a comment before it aside), a class-like's on
  its declaration's line (an empty one's `{}` aside) or an empty class-like's not, or when an expression
  body's arrow moved between the signature's line and its own line or is wrapped other than one
  level in; it counts the braces it moved, none in a tree `phn migrate` laid out; files a second pass
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

`./gradlew test` is a no-op when nothing changed (build cache); `./gradlew test --rerun` forces a run.

## Limitations

- **Phaenon types are modelled as PhpStorm can hold them.** A `list<User>` is `User[]`; a
  `map<K, V>` is `V[]`, its key type lost; `bytes` is `string`, so a variable holding one of them
  reads `User[]` or `string` in its hover, where a declaration reads as written; a tuple is
  the union of its element arrays, so `$pair[0]` and `[$code] = $kubectl(…)` are `A|B` rather
  than `A` (PhpStorm keeps positions only for docblock shapes it reads from the PSI, and types the
  compiled `@var \Closure(string...): array{int, string}` call as `mixed`); a type parameter is its
  bound; type arguments of user generics (`Box<User>`) are dropped, so `$box->get()` has the bound
  of `Box`'s parameter. The Phaenon checker (`phn check`) is the authority on types; the editor approximates.
- **PhpStorm's exception analysis does not read `throws`.** It reads `@throws` tags, and no
  extension point gives it another source: a call of a function whose clause names an exception
  is not "unhandled" to it, and the plugin only drops the findings the clauses answer where the
  compiled `@throws` tags would (the function, the closures in it, the overrides that inherit its
  tags). Its quick-fixes add `@throws` tags, not clause members. PhpStorm highlights a wrong
  expression body's value from the arrow's `>` on, the statement it stands for. A clause naming a
  type parameter is taken to cover any exception.
- **An expression body takes no second statement.** It is a block of one statement to PhpStorm,
  closed where the source has no `}`, so a refactoring that adds a statement to the body —
  Introduce Variable on part of the expression — writes it between the arrow's two characters and
  breaks the arrow. Write the body as a block first; nothing converts one into the other yet.
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
- **Formatting is PhpStorm's PHP code style, around erased constructs**, with functions' and
  methods' braces on their line, class-likes' on a line of their own, and an empty body as `{}`. The text of an erased
  construct is not formatted: `fun get(): ?T` keeps whatever spacing is inside `: ?T`, and
  `type X = map<A,B>;` stays as written, since the formatter sees each construct as one token. A
  construct counts towards the line length, so a line PHP would not wrap can wrap in `.phn` (an
  `implements` list with type arguments past 120 columns). PhpStorm's PSR-12 style is not the
  php-cs-fixer configuration of the Tequila packages (concatenation and cast spacing, `fn (`, line
  wrapping), and its formatter re-indents the arguments of a call it wrapped when run a second
  time; both are so in `.php` files too. Two spots in PhpStorm compare the root node against PHP's
  own file element type by identity (top-level folding, dead-code entry points); a corner case
  there would show up as a folding difference, not as broken analysis.
- **The line map reaches positions, not every label.** The Frames list labels a compiled frame with
  the compiled file's name and line (`Greeter.php:14`): PhpStorm's PHP frames draw their own label
  and offer no extension; selecting the frame opens the `.phn` line. A breakpoint set before its
  `.phn` is compiled stays "No mapping to PHP line" until it is toggled or a new session starts, and
  a map is as old as the last build (the compiled file's banner warns when the source is newer).
  Stepping by source line knows which step was taken from the debugger action; a step issued another
  way (another plugin's action) steps by compiled line. In a web debugging session the Variables view
  of a compiled frame says the script is outside the project (the output directories are excluded);
  the variables load all the same. Coverage still reports compiled lines (design/line-map.md §5). A
  compiled file opens in PhpStorm's text editor wrapped by the plugin; a feature that expects
  PhpStorm's own editor class may not work in compiled files.
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
- The debugger support implements `PhpTemplateLanguagePathMapper` (`com.intellij.php.debug.templateLanguage`),
  PHP plugin API marked internal; Twig and Blade, bundled with PhpStorm, are its other implementations,
  and it is the same in 2025.1 and 2026.2. Its gate relies on how PhpStorm asks: that
  `PhpLineBreakpointHandler.registerBreakpoint` calls `isTemplateFileType` itself (checked on the
  stack) right after `getInstance` asked `isGeneratedFile` about the breakpoint's file. If PhpStorm
  changes that, `.phn` breakpoints are no longer mapped (and not hit), while `.php` files are
  unaffected. Source-line stepping reads the platform's debugger action ids (`StepOver`, `StepInto`,
  …, `XDebuggerActions`) as strings. The compiled file's wrapped editor forwards every method of
  `TextEditor`, `isEditorLoaded()` among them, which is marked internal.
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
