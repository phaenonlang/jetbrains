<?php

namespace App;

final class PlainRepository implements AggregateRepository
{
    public function get(AggregateId $id): ?Aggregate
    {
        return null;
    }

    public function load(AggregateId $id): Aggregate
    {
        throw new \LogicException();
    }

    public function save(Aggregate $aggregate): void
    {
    }
}

final class WrongPlainRepository implements AggregateRepository
{
    public function get(AggregateId $id): ?string
    {
        return null;
    }

    public function load(AggregateId $id): Aggregate
    {
        throw new \LogicException();
    }

    public function save(string $aggregate): void
    {
    }
}
