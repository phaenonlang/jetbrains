<?php

final class EofInBody
{
    public function a(): int
    {
        return 1;
    }

    public function b(): int
    {
        $f = function () {
            return match (1) {
                1 => 2,
