<?php

namespace Acme\Shop\Docs;

final class Docblock
{
    /** @var \Closure(string, int=): void */
    private \Closure $log;

    /** @var \Closure(string...): bool */
    private \Closure $accept;

    /** @var \Closure(int|string): \Acme\Shop\Docs\Docblock */
    private \Closure $find;
}
