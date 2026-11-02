<?php
require __DIR__ . '/../.src/Domain/Greeter.php';
$g = new App\Domain\Greeter();
$s = $g->twice('ada');
$g->log($s);
echo $s, "\n";
