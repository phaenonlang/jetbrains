<?php

function f(string $x): string
{
    $a = "unterminated $x ;
    return $a;
}

function g(): int
{
    return 1;
}
