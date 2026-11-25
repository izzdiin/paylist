<?php
// READ-ONLY parity probe for review #7 (Paylist C2): SHOW CREATE TABLE for
// paylist_log + payment from the warganet production DB, plus row count,
// idx_amount presence, and rematch-column presence.
// Credentials come from db_config.php on this box and are never printed.
// Run: php /tmp/wg-parity-probe.php ; delete afterwards.
require '/usr/local/app/app-warganet/html/db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) { fwrite(STDERR, "no mysqli conn\n"); exit(1); }
foreach (['paylist_log', 'payment'] as $t) {
    $r = $db->query("SHOW CREATE TABLE `$t`");
    if (!$r) { echo "=== $t === ERROR: " . $db->error . "\n\n"; continue; }
    $row = $r->fetch_assoc();
    echo "=== $t ===\n" . $row['Create Table'] . "\n\n";
}
$r = $db->query("SELECT COUNT(*) AS c FROM paylist_log");
echo "paylist_log rows: " . ($r ? $r->fetch_assoc()['c'] : 'ERR') . "\n";
$r = $db->query("SHOW INDEX FROM paylist_log WHERE Key_name = 'idx_amount'");
echo "idx_amount on paylist_log: " . ($r && $r->num_rows > 0 ? "PRESENT" : "ABSENT") . "\n";
$r = $db->query("SELECT COUNT(*) AS c FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'paylist_log' AND COLUMN_NAME IN ('rematch_count','last_rematch_at','rematched_by')");
echo "rematch columns present: " . ($r ? $r->fetch_assoc()['c'] : 'ERR') . " of 3\n";
