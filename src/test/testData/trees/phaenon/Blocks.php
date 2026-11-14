<?php
namespace App\Trees;

interface Shape
{
    function area(): float;
}

trait Named
{
    function name(): string { return static::class; }
}

enum Suit: string
{
    case Hearts = 'h';
    case Spades = 's';

    function label(): string { return match ($this) {
        Suit::Hearts => 'hearts',
        Suit::Spades => 'spades',
    }; }

    static function of(string $s): self { return self::from($s); }
}

abstract class Blocks implements Shape
{
    use Named;

    public int $hooked = 1 {
        get => $this->hooked + 1;
        set(int $value) {
            $this->hooked = $value;
        }
    }

    public string $short { get => $this->name(); }

    function plain(): int { return 1; }

    function closure(): \Closure { return function (int $x) use (&$y): int {
        $y = $x;
        if ($x) {
            return 1;
        }

        return $x + 1;
    }; }

    function arrow(): \Closure { return fn (int $x): int => $x + 1; }

    function arrows(): \Closure { return fn (int $x) => fn (int $y) => [$x => $y]; }

    function matched(int $x): string { return match (true) {
        $x > 1 => 'many',
        default => (function () { return 'one'; })(),
    }; }

    function heredoc(string $n): string { return <<<TXT
        Hello {$n}; not the end; { nor this }
        TXT; }

    function nowdoc(): string { return <<<'TXT'
        ; { ; }
        TXT; }

    function interpolated(string $n): string { return "a{$n}b${n}c" . "{$this->plain()};"; }

    function log(string $m): void { print("x {$m}; y"); }

    function fail(): never { throw new \LogicException('no; way'); }

    function anonymous(): object { return new class (1) {
        function __construct(private int $n) {}

        function count(): int { return $this->n; }

        function inner(): \Closure { return function () { return new class { function deep(): int { return 2; } }; }; }
    }; }

    function braces(): object
    {
        $first = new class {
            function one(): int { return 1; }

            function two(): int { return 2; }

            function three(): int { return 3; }
        };
        try {
            return new class { function four(): int { return 4; } };
        } finally {
            unset($first);
        }
    }

    abstract function area(): float;

    protected static function last(): int { return 5; }
}

function twice(int $x): int { return $x * 2; }

function outer(): \Closure
{
    function declared(): int { return 6; }

    return fn (): int => declared();
}

function generated(): \Generator { return yield from [1, 2]; }
