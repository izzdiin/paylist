<?php
// Helper CSRF bersama (pola yang sama dengan csrf.php v2New, perbaikan M-6).
// Dipakai pertama kali pada paylist_rematch.php (perbaikan C2) dan tab
// Paylist payment/index.php. Butuh sesi aktif: panggil session_start()
// sebelum memakai fungsi-fungsi ini. Satu token per sesi (stabil,
// tidak diganti per permintaan) agar banyak tab tetap bekerja.

function csrf_token(): string
{
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf_token'];
}

function csrf_field(): string
{
    return '<input type="hidden" name="csrf_token" value="'
        . htmlspecialchars(csrf_token(), ENT_QUOTES, 'UTF-8') . '">';
}

function csrf_token_valid(): bool
{
    $sent = $_POST['csrf_token'] ?? '';
    return !empty($_SESSION['csrf_token'])
        && is_string($sent)
        && hash_equals($_SESSION['csrf_token'], $sent);
}
