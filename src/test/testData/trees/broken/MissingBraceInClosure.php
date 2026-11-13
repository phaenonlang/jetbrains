<?php

function f(): int
{
    $g = function () {
        if (rand()) {
            foo();
    };

    return 1;
}

function h(): int
{
    return 2;
}
