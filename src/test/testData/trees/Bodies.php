<?php

declare(strict_types=1);

namespace App\Trees;

use App\Trees\Attribute\Route;
use Closure;
use RuntimeException;
use Throwable;

use function array_map;
use function sprintf;

use const PHP_EOL;

#[\Attribute(\Attribute::TARGET_CLASS | \Attribute::TARGET_METHOD)]
final readonly class Marker
{
    public function __construct(public string $name = 'marker', public array $options = [])
    {
    }
}

interface Shape
{
    public const string KIND = 'shape';

    public function area(): float;
}

trait Describes
{
    private int $described = 0;

    public function describe(): string
    {
        $this->described++;

        return sprintf('%s of %.2f', static::class, $this->area());
    }

    abstract public function area(): float;

    public static function make(mixed ...$arguments): static
    {
        return new static(...$arguments);
    }
}

enum Suit: string implements Shape
{
    use Describes;

    case Hearts = 'H';
    case Spades = 'S';

    public const Suit Wild = self::Spades;

    public function area(): float
    {
        return match ($this) {
            self::Hearts => 1.0,
            self::Spades => (function (): float {
                if ($this === self::Wild) {
                    return 2.0;
                }

                return 3.0;
            })(),
        };
    }

    public static function fromChar(string $char): self
    {
        foreach (self::cases() as $case) {
            if ($case->value === $char) {
                return $case;
            }
        }

        throw new RuntimeException("no suit {$char}");
    }
}

#[Marker('circle', ['closed' => true])]
final class Circle implements Shape
{
    use Describes {
        describe as protected describeShape;
    }

    private static int $created = 0;

    public string $label {
        get => $this->label . '!';
        set(string $value) {
            if ($value === '') {
                throw new RuntimeException('empty');
            }
            $this->label = $value;
        }
    }

    public private(set) float $radius {
        get {
            return $this->radius;
        }
    }

    public int $doubled {
        #[Marker]
        get => (int) ($this->radius * 2);
    }

    public function __construct(float $radius, string $label = 'circle')
    {
        $this->radius = $radius;
        $this->label = $label;
        self::$created++;
    }

    #[Marker(name: 'area')]
    #[Route('/area', methods: ['GET'])]
    public function area(): float
    {
        return M_PI * $this->radius ** 2;
    }

    public function nested(array $rows): array
    {
        $scale = function (float $by) use (&$rows): Closure {
            return function (array $row) use ($by): array {
                $inner = static fn (float $x): float => $x * $by;

                return array_map(
                    function ($cell) use ($inner) {
                        if (\is_float($cell)) {
                            return $inner($cell);
                        } elseif (\is_array($cell)) {
                            return array_map(fn ($x) => $inner((float) $x), $cell);
                        } else {
                            return $cell;
                        }
                    },
                    $row,
                );
            };
        };

        $visitor = new class($scale) extends \ArrayObject implements \Countable {
            private array $seen = [];

            public function __construct(private readonly Closure $scale)
            {
                parent::__construct();
            }

            public function visit(array $row): array
            {
                try {
                    $this->seen[] = $row;

                    return ($this->scale)(2.0)($row);
                } catch (RuntimeException | \LogicException $e) {
                    $handler = new class {
                        public function handle(Throwable $e): never
                        {
                            throw new RuntimeException($e->getMessage(), previous: $e);
                        }
                    };
                    $handler->handle($e);
                } catch (Throwable) {
                    return [];
                } finally {
                    if ($this->seen !== []) {
                        try {
                            $this->exchangeArray($this->seen);
                        } finally {
                            unset($row);
                        }
                    }
                }
            }

            public function count(): int
            {
                return \count($this->seen);
            }
        };

        function helper(array $row): array
        {
            function deeper(int $x): int
            {
                return $x + 1;
            }

            return array_map(deeper(...), $row);
        }

        if (!\function_exists(__NAMESPACE__ . '\\conditional')) {
            function conditional(): string
            {
                return __FUNCTION__;
            }
        }

        return array_map($visitor->visit(...), array_map(helper(...), $rows));
    }

    public function blocks(int $n): int
    {
        {
            $a = $n;
            {
                $b = $a + 1;
            }
        }
        {
        }

        for ($i = 0, $j = 10; $i < $j; $i++, $j--) {
            while (true) {
                do {
                    if ($i % 2) {
                        continue 2;
                    }
                    break 2;
                } while (false);
            }
        }

        switch ($n) {
            case 1:
            case 2: {
                $b++;
                break;
            }
            default:
                $b--;
        }

        return $a <=> $b;
    }

    public function &reference(): array
    {
        static $cache = [];

        return $cache;
    }

    public function generator(iterable $items): \Generator
    {
        foreach ($items as $key => $item) {
            $sent = yield $key => $item;
            if ($sent) {
                yield from $this->generator($sent);
            }
        }

        return 0;
    }

    public function empty(): void
    {
    }

    public function oneLine(): void {}

    public function commented(): void
    {
        // nothing but a comment { with a brace
        /* and } another */
        # and { one more
    }
}

function top(int ...$numbers): int
{
    $sum = 0;
    foreach ($numbers as $number) {
        $sum += $number;
    }

    return $sum |> abs(...) |> (fn (int $x): int => $x * 2);
}

function withDefaults(
    Closure $callback = static function (string $secret, mixed $id): ?string {
        return $id === null ? null : $secret;
    },
    Marker $marker = new Marker('default'),
    Closure $length = strlen(...),
    #[\SensitiveParameter] string $secret = '',
    int|string|null $id = null,
): ?string {
    try {
        return $callback($secret, $id);
    } catch (Throwable $e) {
        echo $e->getMessage(), PHP_EOL;
    }

    return null;
}

const BUILD = 'trees';

$instance = new Circle(1.5);
$instance->label = 'c';
echo top(1, 2, 3), PHP_EOL;

if ($instance->area() > 1) {
    function late(): void
    {
        echo 'late';
    }
}

try {
    late();
} catch (\Error) {
} finally {
    echo PHP_EOL;
}

return static function (Circle $circle): array {
    return ['circle' => $circle, 'copy' => clone $circle];
};
