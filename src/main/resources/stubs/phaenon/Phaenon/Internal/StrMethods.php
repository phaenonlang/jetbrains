<?php

/*
 * Editor-only declaration, bundled with the Phaenon plugin for PhpStorm. This class does not
 * exist at runtime: a Phaenon `string` is a PHP string, and `$s->upper()` compiles to
 * `\Phaenon\Str::upper($s)`. The plugin adds this type to a `string` (or `\Stringable`) value
 * where it is the receiver of a method call, so that the IDE can complete, resolve and type the
 * method form. The methods mirror `\Phaenon\Str` (phaenon/stdlib/runtime/Str.phn) without the
 * receiver parameter.
 */

namespace Phaenon\Internal;

final class StrMethods implements \Stringable
{
    private function __construct()
    {
    }

    /**
     * Code points.
     * @see \Phaenon\Str::length()
     */
    public function length(): int {}

    /** @see \Phaenon\Str::isEmpty() */
    public function isEmpty(): bool {}

    /** @see \Phaenon\Str::upper() */
    public function upper(): string {}

    /** @see \Phaenon\Str::lower() */
    public function lower(): string {}

    /** @see \Phaenon\Str::upperFirst() */
    public function upperFirst(): string {}

    /** @see \Phaenon\Str::trim() */
    public function trim(string $chars = " \t\n\r\0\x0B"): string {}

    /** @see \Phaenon\Str::trimStart() */
    public function trimStart(string $chars = " \t\n\r\0\x0B"): string {}

    /** @see \Phaenon\Str::trimEnd() */
    public function trimEnd(string $chars = " \t\n\r\0\x0B"): string {}

    /** @see \Phaenon\Str::startsWith() */
    public function startsWith(string $prefix): bool {}

    /** @see \Phaenon\Str::endsWith() */
    public function endsWith(string $suffix): bool {}

    /** @see \Phaenon\Str::contains() */
    public function contains(string $needle): bool {}

    /**
     * Null, never false.
     * @see \Phaenon\Str::indexOf()
     */
    public function indexOf(string $needle, int $from = 0): ?int {}

    /** @see \Phaenon\Str::lastIndexOf() */
    public function lastIndexOf(string $needle): ?int {}

    /**
     * Code points; a negative start counts from the end, as `substr` does.
     * @see \Phaenon\Str::slice()
     */
    public function slice(int $start, ?int $length = null): string {}

    /**
     * @return list<string>|ListMethods<string>
     * @see \Phaenon\Str::split()
     */
    public function split(string $separator, int $limit = PHP_INT_MAX): array {}

    /**
     * @return list<string>|ListMethods<string>
     * @see \Phaenon\Str::lines()
     */
    public function lines(): array {}

    /** @see \Phaenon\Str::replace() */
    public function replace(string $search, string $replacement): string {}

    /**
     * @param array<string, string> $pairs
     * @see \Phaenon\Str::replaceAll()
     */
    public function replaceAll(array $pairs): string {}

    /** @see \Phaenon\Str::repeat() */
    public function repeat(int $times): string {}

    /** @see \Phaenon\Str::padStart() */
    public function padStart(int $length, string $pad = ' '): string {}

    /** @see \Phaenon\Str::padEnd() */
    public function padEnd(int $length, string $pad = ' '): string {}

    /**
     * @return list<string>|ListMethods<string>
     * @see \Phaenon\Str::chars()
     */
    public function chars(): array {}

    /**
     * Needs ext-intl.
     * @return list<string>|ListMethods<string>
     * @see \Phaenon\Str::graphemes()
     */
    public function graphemes(): array {}

    /**
     * Needs ext-intl.
     * @see \Phaenon\Str::graphemeLength()
     */
    public function graphemeLength(): int {}

    /** @see \Phaenon\Str::truncate() */
    public function truncate(int $graphemes, string $ellipsis = '…'): string {}

    /**
     * `preg_match() === 1`.
     * @see \Phaenon\Str::matches()
     */
    public function matches(string $pattern): bool {}

    /**
     * The same bytes, retyped as binary data.
     * @return string|BytesMethods
     * @see \Phaenon\Str::bytes()
     */
    public function bytes(): string {}

    public function __toString(): string {}
}
