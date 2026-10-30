<?php

/*
 * Editor-only declaration, bundled with the Phaenon plugin for PhpStorm. This class does not
 * exist at runtime: a Phaenon `bytes` value is a PHP string, and `$b->hex()` compiles to
 * `\Phaenon\Bytes::hex($b)`. The plugin adds this type to every `bytes` value so that the IDE
 * can complete, resolve and type the method form. The methods mirror `\Phaenon\Bytes`
 * (phaenon/stdlib/runtime/Bytes.phn) without the receiver parameter; the constructors
 * `Bytes::fromHex()` and `Bytes::fromBase64()` have no method form.
 */

namespace Phaenon\Internal;

final class BytesMethods
{
    private function __construct()
    {
    }

    /** @see \Phaenon\Bytes::length() */
    public function length(): int {}

    /** @see \Phaenon\Bytes::isEmpty() */
    public function isEmpty(): bool {}

    /**
     * @return string|BytesMethods
     * @see \Phaenon\Bytes::slice()
     */
    public function slice(int $start, ?int $length = null): string {}

    /**
     * @return string|BytesMethods
     * @see \Phaenon\Bytes::concat()
     */
    public function concat(string ...$parts): string {}

    /** @see \Phaenon\Bytes::hex() */
    public function hex(): string {}

    /** @see \Phaenon\Bytes::base64() */
    public function base64(bool $urlSafe = false): string {}

    /**
     * Validates UTF-8 and throws \ValueError otherwise.
     * @see \Phaenon\Bytes::text()
     */
    public function text(): string {}

    /**
     * U+FFFD for every invalid sequence.
     * @see \Phaenon\Bytes::textLossy()
     */
    public function textLossy(): string {}

    /**
     * Constant time (`hash_equals`).
     * @see \Phaenon\Bytes::equals()
     */
    public function equals(string $b): bool {}

    /**
     * The byte at an index, 0–255.
     * @see \Phaenon\Bytes::at()
     */
    public function at(int $i): int {}
}
