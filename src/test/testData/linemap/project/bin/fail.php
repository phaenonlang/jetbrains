<?php
require __DIR__ . '/../.src/Domain/Failing.php';
echo (new App\Domain\Failing())->run(), "\n";
