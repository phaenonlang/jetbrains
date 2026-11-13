<?php

namespace App\Trees;

use InvalidArgumentException;

abstract class Control
{
    public function alternative(array $rows): string
    {
        $out = '';
        if ($rows):
            foreach ($rows as $row):
                while ($row > 0):
                    $row--;
                    for ($i = 0; $i < 2; $i++):
                        $out .= $i;
                    endfor;
                endwhile;
            endforeach;
        elseif ($out === ''):
            $out = 'none';
        else:
            switch (true):
                case $out === 'x':
                    $out = function () { return 'y'; };
                    break;
                default:
                    $out = 'z';
            endswitch;
        endif;

        declare(ticks=1) {
            $out .= '!';
        }

        return $out;
    }

    public function expressions(?object $o, array $a): mixed
    {
        $first = $o?->first?->second ?? $a['k'] ?? throw new InvalidArgumentException('k');
        [$x, [$y, $z]] = $a;
        ['p' => $p, 'q' => [$q]] = $a;
        $spread = [...$a, ...['k' => 'v'], 'tail'];
        $callable = strlen(...);
        $method = $o->method(...);
        $static = static::create(...);
        $instance = new static()->configure(named: true, other: [1, 2]);
        $ternary = $x ? ($y ?: $z) : match (true) {
            $p instanceof Control, $q instanceof \Countable => 1,
            default => throw new InvalidArgumentException(),
        };
        $cast = (int) $x + (float) $y . (string) $z;
        $pipe = $a |> array_values(...) |> (fn (array $v) => array_map(fn ($e) => $e * 2, $v)) |> array_sum(...);
        $copy = clone $o;
        $print = print 'x';
        isset($a['x'], $o->y) or exit(1);
        empty($a) and die('empty');
        list('a' => $one, 'b' => list($two)) = $a;
        unset($a[0], $o->p);
        $ref = &$a['ref'];
        $heredocInCall = sprintf(<<<FMT
            %s {and} %d
            FMT, $x, $y);
        include __DIR__ . '/x.php';
        $result = require_once 'y.php';
        global $shared;
        echo $first, $spread, $callable, $method, $static, $instance, $ternary, $cast, $pipe, $copy, $print, $ref, $heredocInCall, $result, $one, $two;

        return fn (int ...$n): int => array_sum($n) <=> 0;
    }

    public function closures(): \Closure
    {
        $a = function () {};
        $b = static function (): void {};
        $c = function &(array &$x) use (&$a, $b): array { return $x; };
        $d = fn () => fn () => function () { return fn () => 1; };
        $e = #[\Pure] static fn (int $x): int => $x;
        $f = (function () { yield 1; })();
        $g = (new class { public function __invoke() { return 1; } })();
        $h = [new class { public function m() { return function () { return new class {}; }; } }, 'm'];

        return $a ?? $b ?? $c ?? $d ?? $e ?? $f ?? $g ?? $h;
    }

    public function exceptions(): int
    {
        try {
            try {
                throw new InvalidArgumentException('inner');
            } catch (InvalidArgumentException $inner) {
                throw new \RuntimeException('outer', 0, $inner);
            } finally {
                $done = true;
            }
        } catch (\RuntimeException | \LogicException $e) {
            return $e->getCode();
        } catch (\Throwable) {
            return -1;
        } finally {
            if (isset($done)) {
                return 1;
            }
        }
    }

    abstract public function left(): void;

    public const HANDLERS = [
        'a' => [self::class, 'left'],
    ];

    final protected static function create(): static
    {
        return new static();
    }
}

interface HasHooks
{
    public string $name { get; }

    public int $count { get; set; }
}

final class Hooked extends Control implements HasHooks
{
    public string $name {
        get {
            return $this->first . ' ' . $this->last;
        }
    }

    public int $count = 0 {
        get => $this->count;
        set(int $value) => max(0, $value);
    }

    public array $items = [] {
        &get {
            return $this->items;
        }
        final set {
            $this->items = array_map(function ($item) { return (string) $item; }, $value);
        }
    }

    public function __construct(
        private readonly string $first,
        public protected(set) string $last {
            set => ucfirst($value);
        },
        protected(set) int $age = 0 {
            get {
                return max(0, $this->age);
            }
        },
    ) {
    }

    public function left(): void
    {
        $this->count++;
    }
}
