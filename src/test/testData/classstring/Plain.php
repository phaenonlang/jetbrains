<?php

namespace App;

/**
 * The bar: what PhpStorm shows for class-strings written in PHP docblocks.
 */
abstract class Plain
{
    /** @return class-string<User> */
    abstract protected static function docOnly();

    /** @return class-string<User> */
    abstract protected static function withNative(): string;

    public function run(): void
    {
        $docOnly = static::docOnly();
        $withNative = static::withNative();
    }
}
