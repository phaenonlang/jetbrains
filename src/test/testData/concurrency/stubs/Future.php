<?php

namespace Amp;

/**
 * A stand-in for amphp/amp's Future: the test project has no vendor directory.
 *
 * @template-covariant T
 */
final class Future
{
    /**
     * @return T
     */
    public function await(): mixed
    {
    }
}
