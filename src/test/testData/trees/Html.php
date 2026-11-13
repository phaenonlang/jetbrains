<?php

function render(array $rows): void
{
    foreach ($rows as $row) {
        ?>
        <li class="row"><?= $row ?></li>
        <?php
    }
    ?>
    <p>{ not a brace } of PHP</p>
    <?php if ($rows): ?>
        <b>some</b>
    <?php else: ?>
        <i>none</i>
    <?php endif;
}

function tagFirst(): void
{ ?>
    <hr>
<?php }

function tagLast(): int
{
    return 1 ?><?php
}

function after(): int
{
    return 2;
}
