# Changelog

From 0.2.1 on, release-please writes each entry from the Conventional Commits merged since the
previous release (`release-please-config.json`); the entry is the Marketplace's change notes for
that version. The first release was written by hand.

## [0.7.0](https://github.com/phaenonlang/jetbrains/compare/v0.6.2...v0.7.0) (2026-11-15)


### Features

* **completion:** an alias completes where a type is written ([e6c6d04](https://github.com/phaenonlang/jetbrains/commit/e6c6d045369218fec3536d29ea6b8716b99cc075))
* **lexer:** a shape is erased with its type declaration ([291aa0b](https://github.com/phaenonlang/jetbrains/commit/291aa0b9ccdb59a5bcb2f3c3a219d6329ff46d44))
* named shapes and type aliases across files ([0e48717](https://github.com/phaenonlang/jetbrains/commit/0e48717708cf58c8f418c7b3fd7db14a59b083c9))
* **names:** a shape's keys are no class names ([1c31291](https://github.com/phaenonlang/jetbrains/commit/1c312916178c1f6dfa0d950c43220a0b3c62b887))
* nothrow, erased where a throws clause goes ([e14f700](https://github.com/phaenonlang/jetbrains/commit/e14f7002c50b784327e3832bbcf87bfa917af59c))
* nothrow, erased where a throws clause goes ([999cf63](https://github.com/phaenonlang/jetbrains/commit/999cf633dc6d2f7da3204cc4e140b6eefa9da039))
* **resolve:** a type alias is found from any file ([3771993](https://github.com/phaenonlang/jetbrains/commit/37719939fdbaf196bfa934d51ed55f00f8e805ec))
* the TextMate bundle reads named shapes ([426be41](https://github.com/phaenonlang/jetbrains/commit/426be410d710519ef547729156e91a58021388e0))
* the TextMate bundle reads named shapes ([f1b9e6e](https://github.com/phaenonlang/jetbrains/commit/f1b9e6eb0204c9331d82c267382f0ef52896daa5))
* the TextMate bundle reads nothrow ([d645ab1](https://github.com/phaenonlang/jetbrains/commit/d645ab1c732c01f8801b99d791b81212bef7ba62))
* **types:** a declaration typed with an alias is what the alias stands for ([92f01d7](https://github.com/phaenonlang/jetbrains/commit/92f01d7c31502812322b3b723a59bfb414bfea2c))


### Bug Fixes

* an override lifts no [@throws](https://github.com/throws) void into a throws clause ([7684859](https://github.com/phaenonlang/jetbrains/commit/768485981319f6c043082365dcda2450bc9d1608))
* **editor:** Enter in a type declaration's docblock and a shape's braces ([b6cf271](https://github.com/phaenonlang/jetbrains/commit/b6cf2711fd0fef154450ffe506df5aeefc6365e4))
* **hierarchy:** an override is judged by what an alias erases to ([cc75afc](https://github.com/phaenonlang/jetbrains/commit/cc75afc1aaffe12e2dc96e467b60b89a56caeb71))
* **inspections:** a default of an alias's type is no error ([720b03a](https://github.com/phaenonlang/jetbrains/commit/720b03ad424b0ff0a51181e0b988ba252bad17e1))
* **inspections:** an alias used as a class is an undefined class ([2ef2fd8](https://github.com/phaenonlang/jetbrains/commit/2ef2fd882718ffec0cfc6616e6e2decb4c5a0602))
* **rename:** rename is refused for a type alias ([e8501f8](https://github.com/phaenonlang/jetbrains/commit/e8501f8b9f3694c018ec8304b2f8e0a902fd2df2))
* **resolve:** a name relative to the namespace, and one that is not ASCII ([f5d476b](https://github.com/phaenonlang/jetbrains/commit/f5d476bdbec1027f2b91745bf01d7e910d156994))
* throws in any case, and only after a whole return type ([2c7df4b](https://github.com/phaenonlang/jetbrains/commit/2c7df4b507c0879d0402e44572c4b5daac9b519b))
* **types:** a partial application on a receiver of two types is typed ([8bc9479](https://github.com/phaenonlang/jetbrains/commit/8bc947978830fe681dd851910a6abe4bffd11e22))
* **types:** a partial application on a receiver of two types is typed ([430c54f](https://github.com/phaenonlang/jetbrains/commit/430c54f926b80858bc042121a9f446e5972db306))
* **types:** a partial application on a receiver of two types is typed ([53e6962](https://github.com/phaenonlang/jetbrains/commit/53e6962050cb66fdc1cbace7ba6989fce6bc35ec))
* **types:** a partial application on an entry of an alias keeps its closure ([1c3fd96](https://github.com/phaenonlang/jetbrains/commit/1c3fd96c5455ac1b0d9c5b6e313fed62c3c0d155))
* **types:** a shape's key cannot end the docblock it is spelled into ([bcd888d](https://github.com/phaenonlang/jetbrains/commit/bcd888dea420d12741fa48a6f8b2c2b11c6ff6a2))
* **types:** an exclusion the provider cannot follow leaves no type ([be12bdc](https://github.com/phaenonlang/jetbrains/commit/be12bdcdbf9154119eee56edb1022c0713455596))
* **types:** the expansion of an alias is bounded ([d6edfb8](https://github.com/phaenonlang/jetbrains/commit/d6edfb805ad0c48dd0740735626f5bc4159b8598))

## [0.6.2](https://github.com/phaenonlang/jetbrains/compare/v0.6.1...v0.6.2) (2026-11-14)


### Bug Fixes

* a class parses in time linear in its expression bodies ([0c8583e](https://github.com/phaenonlang/jetbrains/commit/0c8583e0b261ff85c2e7970fa0b8b721683fe151))
* a class parses in time linear in its expression bodies ([cddfe0a](https://github.com/phaenonlang/jetbrains/commit/cddfe0a2f721fddfdc939d6a4418e11d043a9c15))
* a skip ends for an error too, and a body's end is found once ([b4467d0](https://github.com/phaenonlang/jetbrains/commit/b4467d044ebfcc64e13fb2327b76a27030589456))
* the lexer no longer throws on a file that ends mid-construct ([ffe2415](https://github.com/phaenonlang/jetbrains/commit/ffe24152853f24c58bda9438587823c0dab6c41c))
* the lexer no longer throws on a file that ends mid-construct ([124e0d3](https://github.com/phaenonlang/jetbrains/commit/124e0d353012e39678e7425003f6ceb95b62e872))

## [0.6.1](https://github.com/phaenonlang/jetbrains/compare/v0.6.0...v0.6.1) (2026-11-13)


### Bug Fixes

* a trailing ... stands for the required parameters left ([#19](https://github.com/phaenonlang/jetbrains/issues/19)) ([fbfda26](https://github.com/phaenonlang/jetbrains/commit/fbfda269af4c860674ff03beffa09a8038c8cf9f))
* bodies and the docblock stub on Enter through public API ([#16](https://github.com/phaenonlang/jetbrains/issues/16)) ([ce43869](https://github.com/phaenonlang/jetbrains/commit/ce438693c709523f215b341cf7ba382ecdcbcca7))
* PhpStorm 2026.3's PHP 8.6 support in .phn files ([#17](https://github.com/phaenonlang/jetbrains/issues/17)) ([c3d61c4](https://github.com/phaenonlang/jetbrains/commit/c3d61c44bcfbdb78832dec993f8a88ba7b53d9a7))

## [0.6.0](https://github.com/phaenonlang/jetbrains/compare/v0.5.0...v0.6.0) (2026-11-09)


### ⚠ BREAKING CHANGES

* PhpStorm 2025.3 is no longer supported, nor are 2026.1 to 2026.1.3; the plugin needs 2026.1.4 or newer.

### Features

* PhpStorm 2026.1.4 or newer; the LSP integration on the current API ([93837e6](https://github.com/phaenonlang/jetbrains/commit/93837e6d2c5bf75fe53803363e21dbba92b35b9e))

## [0.5.0](https://github.com/phaenonlang/jetbrains/compare/v0.4.0...v0.5.0) (2026-11-09)


### ⚠ BREAKING CHANGES

* `fun`, `is`, `as` and `as?` are no longer read as Phaenon syntax; a `.phn` that still holds them shows PhpStorm's syntax errors until `phn fmt` rewrites it.

### Features

* methods without a keyword; fun, is and as leave ([464ce9b](https://github.com/phaenonlang/jetbrains/commit/464ce9bbdab2cd3faa0ce4f838a95d99140b6320))

## [0.4.0](https://github.com/phaenonlang/jetbrains/compare/v0.3.0...v0.4.0) (2026-11-09)


### ⚠ BREAKING CHANGES

* the plugin no longer understands `concurrently { }` and `spawn`; a .phn that still uses them reads as PHP, with syntax errors where the blocks stand (roadmap D25, concurrency.md §17).

### Features

* concurrently and spawn are plain identifiers ([#8](https://github.com/phaenonlang/jetbrains/issues/8)) ([17e4de1](https://github.com/phaenonlang/jetbrains/commit/17e4de1e02a479e237f37e6d27f3561cb02c0999))


### Bug Fixes

* public API where PhpStorm has one, verified across the declared range ([c398b0d](https://github.com/phaenonlang/jetbrains/commit/c398b0d767575adcb4229cd7d7d8162a1383d4b1))

## [0.3.0](https://github.com/phaenonlang/jetbrains/compare/v0.2.1...v0.3.0) (2026-11-07)


### ⚠ BREAKING CHANGES

* PhpStorm 2025.1 and 2025.2 are no longer supported.

### Features

* PhpStorm 2025.3 is the floor ([6480d52](https://github.com/phaenonlang/jetbrains/commit/6480d52b1aefc862c1d693ef0d2dce3b2d2f36b8))


### Bug Fixes

* a closure-valued default is not one that "must be declared static" ([1b271b5](https://github.com/phaenonlang/jetbrains/commit/1b271b55265db2d1ac1c51a99ba885bcfbeba110))

## [0.2.1](https://github.com/phaenonlang/jetbrains/compare/v0.2.0...v0.2.1) (2026-11-07)


### Bug Fixes

* PhpStorm 2026.3 drops a bundle key and deprecates the PATH lookup ([7d5631a](https://github.com/phaenonlang/jetbrains/commit/7d5631a96c8378220f106beb65dc59234e523f6e))
* PhpStorm 2026.3 drops a bundle key and deprecates the PATH lookup ([ab942c0](https://github.com/phaenonlang/jetbrains/commit/ab942c08ece74d8218cccafd7bbeb626c0250d91))

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
