<?php

namespace App\Trees;

final class Strings
{
    private array $map = ['a' => ['b' => 'c']];

    public function interpolation(string $name, object $o): string
    {
        $simple = "hello $name and {$name}, ${name} or {$this->map['a']['b']}";
        $method = "call {$o->method(function () { return '}'; })} done";
        $dynamic = "prop {$o->{'na' . 'me'}} and ${'na' . 'me'}";
        $braces = 'single { quoted } with { unbalanced';
        $double = "double \{ escaped } and { literal";
        $shell = `ls {$name} | grep "}"`;

        return $simple . $method . $dynamic . $braces . $double . $shell;
    }

    public function heredocs(string $name): string
    {
        $here = <<<TEXT
            A heredoc with { braces } and a } closing one,
            an {$name} interpolation, a ${name} one and a {$this->map['a']['b']} one;
            a line that ends with a brace {
            TEXT;

        $now = <<<'TEXT'
            A nowdoc with { unbalanced, $name and ${name} and }}}
            function fake() { return 1;
            TEXT;

        $nested = <<<OUTER
            outer {${$this->inner(<<<INNER
                inner { text }
                INNER)}}
            OUTER;

        return $here . $now . $nested . <<<"QUOTED"
            quoted {$name}
            QUOTED;
    }

    public function variables(string $name): mixed
    {
        $$name = 1;
        ${$name . 'x'} = 2;
        ${'literal'} = 3;
        $this->{$name} = 4;
        $this->{'a' . 'b'}();
        static::{$name}();
        $closure = function () use ($name) { return ${$name}; };
        $array = [$name => ['{' => '}'], '}' => '{'];

        return $array['}'] ?? $closure();
    }

    public function comments(): int
    {
        // a line comment with an opening brace {
        $a = 1; // and a closing one }
        /* a block comment } { } */
        /**
         * A docblock with braces {@see Strings} and } {
         *
         * @return array{a: int, b: array{c: string}}
         */
        $b = static fn (): array => ['a' => 1, 'b' => ['c' => '}']];
        # a hash comment {

        return $a + \count($b());
    }

    public function labels(int $n): int
    {
        start:
        if ($n > 10) {
            goto end;
        }
        $n++;
        goto start;
        end:

        return $n;
    }
}
