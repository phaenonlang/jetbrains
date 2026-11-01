<?php

/**
 * PHP's Closure as PhpStorm's bundled stubs declare it (stubs/Core/Core_c.php): the IDE indexes
 * those stubs only for a project with a PHP module, which the test project is not.
 */
final class Closure
{
    private function __construct() {}

    public function __invoke(...$_) {}

    public function bindTo(?object $newThis, object|string|null $newScope = 'static'): ?Closure {}

    public static function bind(Closure $closure, ?object $newThis, object|string|null $newScope = 'static'): ?Closure {}

    public function call(object $newThis, mixed ...$args): mixed {}

    public static function fromCallable(callable $callback): Closure {}
}
