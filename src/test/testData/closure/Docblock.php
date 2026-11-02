<?php

namespace Tequila\Tq\Docs;

final class Docblock
{
    /** @var \Closure(string, int=): void */
    private \Closure $log;

    /** @var \Closure(string...): bool */
    private \Closure $accept;

    /** @var \Closure(int|string): \Tequila\Tq\Docs\Docblock */
    private \Closure $find;
}
