<?php

namespace Phaenon\Concurrent;

use Amp\Future;

/**
 * A stand-in for phaenon/concurrent's Scope, as `phn stdlib` writes it.
 */
final class Scope
{
    /**
     * @template T
     * @param \Closure(): T $task
     * @return Future<T>
     */
    public static function spawn(\Closure $task): Future
    {
    }

    /**
     * @template K of int|string
     * @template T
     * @param iterable<K, Future<T>> $tasks
     * @return array<K, T>
     */
    public static function all(iterable $tasks): array
    {
    }
}
