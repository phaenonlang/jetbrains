<?php

function f(): void
{
    try {
        foo();
    } catch (\Throwable $e) {
        bar(function () {
