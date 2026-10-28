<?php

declare(strict_types=1);

namespace App;

function describe(): string
{
    return (new La<caret>bel('plain'))->upper();
}
