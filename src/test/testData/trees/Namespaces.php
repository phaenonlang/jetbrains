<?php

namespace App\Trees\First {
    use App\Trees\Second\Helper;

    const LIMIT = 10;

    function first(int $x): int
    {
        return Helper::double($x) + LIMIT;
    }

    final class Uses
    {
        public function run(): int
        {
            return first(1);
        }
    }
}

namespace App\Trees\Second {
    final class Helper
    {
        public static function double(int $x): int
        {
            return namespace\twice($x);
        }
    }

    function twice(int $x): int
    {
        return $x * 2;
    }
}

namespace {
    function globalFunction(): string
    {
        return \App\Trees\Second\Helper::class;
    }

    echo globalFunction();
}
