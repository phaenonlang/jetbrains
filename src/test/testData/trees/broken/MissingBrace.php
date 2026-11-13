<?php

namespace App\Trees;

final class MissingBrace
{
    public function a(int $x): int
    {
        if ($x) {
            return 1;

    }

    public function b(): int
    {
        return 2;
    }
}

function tail(): int
{
    return 3;
}
