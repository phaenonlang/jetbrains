# Changelog

From 0.2.1 on, release-please writes each entry from the Conventional Commits merged since the
previous release (`release-please-config.json`); the entry is the Marketplace's change notes for
that version. The first release was written by hand.

## [0.3.0](https://github.com/phaenonlang/jetbrains/compare/v0.2.0...v0.3.0) (2026-11-06)


### ⚠ BREAKING CHANGES

* **types:** drop the method syntax on values that are not objects

### Features

* a .phn declaration wins over its compiled copy; phaenon.json outputs are excluded ([dd2af30](https://github.com/phaenonlang/jetbrains/commit/dd2af30b640ef1d370cb45e84afecaf6d5ed2426))
* class names inside erased types navigate and count as import uses ([d611201](https://github.com/phaenonlang/jetbrains/commit/d6112019b0bd780651946d42a30198a075b5b509))
* completion inside erased constructs; no spell-checking of them ([65a70f3](https://github.com/phaenonlang/jetbrains/commit/65a70f35d6f39ee0a2f7a1b96c9b03c8e10c5804))
* **completion:** generated overrides in Phaenon's spelling ([e8f1469](https://github.com/phaenonlang/jetbrains/commit/e8f1469ef6b9f9a3e6cdeec7d3100210de1bacb9))
* **concurrency:** concurrently and spawn as values in the editor ([0db2717](https://github.com/phaenonlang/jetbrains/commit/0db27170ac64cffb0fb9219e81df59b278facc62))
* **d18:** expression bodies and throws clauses in the parser ([4db044d](https://github.com/phaenonlang/jetbrains/commit/4db044d8a8279ddcca5fabb0fb3bd311575226ee))
* **d18:** generated code with throws clauses and one-line docblocks ([3de6c95](https://github.com/phaenonlang/jetbrains/commit/3de6c95f8b1c90e8ec323ad1411177c8c822a390))
* **d18:** throws clauses in references, completion and exception findings ([4451c65](https://github.com/phaenonlang/jetbrains/commit/4451c654ad2b645035877b4a39e0c21fdb7e5cd1))
* **debug:** debug .phn files through the line map ([1b0bbf9](https://github.com/phaenonlang/jetbrains/commit/1b0bbf9d475a5a3fb83ea91448bd0752c4a622a2))
* **editor:** Phaenon's canonical layout in the .phn formatter ([05e2afd](https://github.com/phaenonlang/jetbrains/commit/05e2afda084e710cb7b89d2948bba79e99369037))
* erased constructs are PSI leaves whose class names are references ([0a22b5d](https://github.com/phaenonlang/jetbrains/commit/0a22b5d25538f4184cc349e190b29cb39bf1e64c))
* **erased:** a type parameter's declaration shows its usages ([6c6d5a0](https://github.com/phaenonlang/jetbrains/commit/6c6d5a0efe1c3df2b39c441cca46edcf2abc70a4))
* **erased:** type parameters navigate and are not "incompatible" ([dc9b647](https://github.com/phaenonlang/jetbrains/commit/dc9b6477fe90aa9662a8672e96bd206c4d08fc3d))
* **format:** Reformat Code writes Phaenon's layout, in spaces ([b04fc9b](https://github.com/phaenonlang/jetbrains/commit/b04fc9b6fc9baf2b12602c592370bb5d294f9fa9))
* **lexer:** concurrently blocks and spawn at the token level ([2b2962b](https://github.com/phaenonlang/jetbrains/commit/2b2962b1149a47ef568e7fd3958f7dce88a6bae1))
* **lexer:** fun declarations and closures in constant expressions ([603db11](https://github.com/phaenonlang/jetbrains/commit/603db11dc584ad566dee0a70bc9a9e4b124d4e01))
* **lexer:** optional and variadic markers in closure types ([9f3225c](https://github.com/phaenonlang/jetbrains/commit/9f3225c473f71e04942daf3d7cbc77a236ce4666))
* **linemap:** read the line map of compiled files ([17c3def](https://github.com/phaenonlang/jetbrains/commit/17c3def44577d81c0c02e6387e8d497e2608570c))
* **lsp:** phn lsp's diagnostics and build on save in PhpStorm ([2c1e1a4](https://github.com/phaenonlang/jetbrains/commit/2c1e1a437ded58fda98c8d82c296b0ba1d9130be))
* **navigation:** positions in compiled files open the .phn ([f2ae1d9](https://github.com/phaenonlang/jetbrains/commit/f2ae1d94c087bb1f14cab210a105e457108eafbf))
* **php86:** PHP 8.6 in .phn files without false findings ([b573956](https://github.com/phaenonlang/jetbrains/commit/b573956ffb4b443deea4c75e6586e239cd370b80))
* **plugin:** complete, resolve and type the method syntax on collections and strings ([bbb8bc4](https://github.com/phaenonlang/jetbrains/commit/bbb8bc4f34609da07780ba314a5ac1ac7acd1951))
* **plugin:** parse Phaenon syntax by erasing it before PhpStorm's parser ([43db968](https://github.com/phaenonlang/jetbrains/commit/43db968c0cf8d161e5918c8a6c9999dfa2766c43))
* qualified names complete inside erased constructs ([0f9eda3](https://github.com/phaenonlang/jetbrains/commit/0f9eda36b53954e2d9f3c0422f049f6d1314fb03))
* **textmate:** Phaenon's syntax in the TextMate bundle ([4d633d1](https://github.com/phaenonlang/jetbrains/commit/4d633d14c95ba2e4c294dc44ad34802743012056))
* **types:** drop the method syntax on values that are not objects ([77b1d1b](https://github.com/phaenonlang/jetbrains/commit/77b1d1bf0dfd90295096470f5df2ec99951d3693))


### Bug Fixes

* **debug:** read positions without the API 2026.2 deprecates ([702058a](https://github.com/phaenonlang/jetbrains/commit/702058a620a40b592bcdd0d262a7f307f1fe444b))
* **editor:** a docblock stub leaves out the types a signature declares ([b61b652](https://github.com/phaenonlang/jetbrains/commit/b61b6529f5bcf676fcb4731e95a38ce2d4708bd8))
* **editor:** run PhpStorm's PHP formatter on .phn files ([ce18349](https://github.com/phaenonlang/jetbrains/commit/ce183495324d483e2d81e2a8113edfd02c43f8c6))
* erased types are not missing type declarations ([2a9b153](https://github.com/phaenonlang/jetbrains/commit/2a9b1539a8d8d08c7100c53507ce9d50de1c0b2b))
* **erased:** a return type erased whole is typed from its text ([ff58df3](https://github.com/phaenonlang/jetbrains/commit/ff58df3bf1dbee67f55859a86b76ac10291693b5))
* **format:** a class-like's opening brace keeps its own line ([0523269](https://github.com/phaenonlang/jetbrains/commit/05232697dcece1dad0406f7f1017d1754cdce8ed))
* **format:** an empty class-like is {} on its header's line ([bc1d3c0](https://github.com/phaenonlang/jetbrains/commit/bc1d3c032789038321be6d9a2b1e918ca8ae87ca))
* **format:** an empty function or method body is {} on its line ([f627f42](https://github.com/phaenonlang/jetbrains/commit/f627f424decb3d71bcc6a4f27cadbf13f216b230))
* **highlight:** a class constant without visibility is public ([4689034](https://github.com/phaenonlang/jetbrains/commit/46890348c6f7dca89ec77d208281f119a63df46f))
* **lexer:** erase type-argument lists after keyword-named methods ([38307ca](https://github.com/phaenonlang/jetbrains/commit/38307cadce63ce34c59d7f50eda70abff3833e4b))
* **linemap:** leading zeros, unknown escapes and line ends as the spec says ([ff3dcf4](https://github.com/phaenonlang/jetbrains/commit/ff3dcf41062f8f12e6a99a12cedcdebf1587ad1e))
* **lsp:** one owner per file, and no server for installed code ([899cd24](https://github.com/phaenonlang/jetbrains/commit/899cd243bdedb6957be4cd83a86418c10e4e6b91))
* **navigation:** forward the editor's default methods, read maps from the end ([730b67c](https://github.com/phaenonlang/jetbrains/commit/730b67cc3fc864183c1bd4ca95681fb0e90960fe))
* **plugin:** a bare Closure(…) type is \Closure in any namespace ([a25f1c5](https://github.com/phaenonlang/jetbrains/commit/a25f1c5e39ef054d9e7deff1287b4770516c98ed))
* **plugin:** Optimize Imports keeps the imports erased constructs use ([f630655](https://github.com/phaenonlang/jetbrains/commit/f630655f4faac1bd406d98a16288b776ee43a6d4))
* **plugin:** re-index .phn files when the plugin's PSI changes ([14adbfc](https://github.com/phaenonlang/jetbrains/commit/14adbfc7e2793af1548866f7cf33966d6ea15297))
* **types:** a type parameter erases to its bound in overrides too ([6be6db9](https://github.com/phaenonlang/jetbrains/commit/6be6db96a07d4bda9712d0fca379e35f5f5ed382))
* **types:** class&lt;T&gt; is its class-string alone, printed as class&lt;T&gt; ([16b7897](https://github.com/phaenonlang/jetbrains/commit/16b78973b7d4be0f0d6eb68684d98f109ca62e58))
* **types:** class&lt;T&gt; names its class to static access ([97c3c9c](https://github.com/phaenonlang/jetbrains/commit/97c3c9c444ec786b08300dbadd4ec41ff0a065f2))
* **types:** closure types carry their signature to call checks ([5c14fd5](https://github.com/phaenonlang/jetbrains/commit/5c14fd5ced4275204c13c304bbaec6eb4db37563))

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
