<?php
/**
 * Endpoint admin re-match operator (perbaikan C2, desain 08 §4b).
 *
 * POST saja, sesi admin + token CSRF (pola csrf.php yang sama dengan
 * v2New). Satu baris log per panggilan (log_id). BUKAN endpoint perangkat:
 * secret perangkat (X-QRIS-Secret) TIDAK PERNAH diterima di sini — domain
 * autentikasi terpisah dari notify.php.
 *
 * Respons JSON: {ok, log_id, before, after, applied, note, ...}
 * (bentuk lengkap dari paylist_rematch_log()).
 */
session_start();
header('Content-Type: application/json; charset=utf-8');

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    http_response_code(405);
    echo json_encode(['ok' => false, 'error' => 'method_not_allowed']);
    exit;
}

// Guard sesi — pola yang sama dengan payment/index.php dan
// update_paylist.php (JSON 401, bukan redirect, karena ini endpoint API).
if (!isset($_SESSION['username'])) {
    http_response_code(401);
    echo json_encode(['ok' => false, 'error' => 'unauthorized']);
    exit;
}

require_once __DIR__ . '/../csrf.php';
if (!csrf_token_valid()) {
    http_response_code(403);
    echo json_encode(['ok' => false, 'error' => 'csrf_invalid']);
    exit;
}

$logId = (int)($_POST['log_id'] ?? 0);
if ($logId <= 0) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'error' => 'bad_log_id']);
    exit;
}

require_once __DIR__ . '/../db_config.php';
$db = $conn ?? ($mysqli ?? null);
if (!($db instanceof mysqli)) {
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'db_unavailable']);
    exit;
}

require_once __DIR__ . '/paylist_match.php';
try {
    $res = paylist_rematch_log($db, $logId, (string)$_SESSION['username']);
} catch (Throwable $e) {
    error_log('C2 rematch gagal (log ' . $logId . '): ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'rematch_failed']);
    exit;
}

echo json_encode($res);
