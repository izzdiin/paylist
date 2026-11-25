#!/usr/bin/env bash
# make-diffs.sh — build the C2 diff bundle (review #8 update).
# Unified diffs, original (production, sha-verified 23 Nov, re-verified
# 24 Nov) vs patched (review #8 state: C2 + birth-fix 24 Nov + stale-
# binding fix 24 Nov), for both billing systems, plus incremental
# diffs (review7 -> review8) and a sha256 manifest of every file.
set -u
C2="$HOME/workspace/c2-impl"
OUT="$C2/review7/diffs"
mkdir -p "$OUT"

d() { # label file-old file-new outfile
    diff -u --label="a/$1" --label="b/$1" "$2" "$3" > "$OUT/$4" || true
    echo "$4: $(wc -l < "$OUT/$4") lines"
}

# v2New (production files, sha-verified against stbjkt 23 Nov, re-verified 24 Nov)
d "bayarQR/notify.php"              "$C2/v2new/notify.php"         "$C2/patched/v2/notify.php"         v2-notify.diff
d "member/payment.php"              "$C2/v2new/member-payment.php" "$C2/patched/v2/member-payment.php" v2-member-payment.diff
d "payment/index.php"               "$C2/v2new/payment-index.php"  "$C2/patched/v2/payment-index.php"  v2-payment-index.diff
d "bayar.php"                       "$C2/bayar-hook/bayar.php.orig" "$C2/bayar-hook/bayar.php"         v2-bayar.diff
d "csrf.php"                        "$C2/v2new/csrf.php"            "$C2/new/v2/csrf.php"               v2-csrf.diff
d "bayarQR/qris_apply.php"          "$C2/v2new/qris_apply.php"     "$C2/patched/v2/qris_apply.php"    v2-qris-apply.diff
d "member/payment.php (deV2)"       "$C2/dev2/member-payment.php"   "$C2/patched/dev2/member-payment.php" dev2-member-payment.diff
# warganet (production files, sha-verified against laptopgrt 23 Nov, re-verified 24 Nov)
d "paylist/notify.php"              "$C2/warganet/notify.php"      "$C2/patched/wg/notify.php"        wg-notify.diff
d "payment/checkout.php"            "$C2/warganet/checkout.php"    "$C2/patched/wg/checkout.php"      wg-checkout.diff
d "payment/index.php"               "$C2/warganet/index.php"       "$C2/patched/wg/index.php"         wg-payment-index.diff
d "paylist/paylist_apply.php"       "$C2/warganet/paylist_apply.php" "$C2/patched/wg/paylist_apply.php" wg-paylist-apply.diff

# Incremental diffs (reviewer convenience): what changed since review #7.
mkdir -p "$OUT/incremental"
di() { # label file-old file-new outfile
    diff -u --label="a/$1" --label="b/$1" "$2" "$3" > "$OUT/incremental/$4" || true
    echo "incremental/$4: $(wc -l < "$OUT/incremental/$4") lines"
}
# birth-fix (C2 -> birth-fix, 24 Nov 07:54): used-set + amount-collision check
di "member/payment.php"   "$C2/birth-fix/orig/v2/member-payment.php" "$C2/patched/v2/member-payment.php" v2-member-payment.c2-to-birthfix.diff
di "bayar.php"            "$C2/birth-fix/orig/v2/bayar.php"          "$C2/bayar-hook/bayar.php"         v2-bayar.c2-to-birthfix.diff
di "payment/checkout.php" "$C2/birth-fix/orig/wg/checkout.php"       "$C2/patched/wg/checkout.php"      wg-checkout.c2-to-birthfix.diff
# stale-binding fix (review #7 -> review #8, 24 Nov 10:16): revalidate binding under lock
di "bayarQR/qris_apply.php"     "$C2/review8-stale/orig/v2-qris_apply.review7.php" "$C2/patched/v2/qris_apply.php"     v2-qris-apply.review7-to-review8.diff
di "paylist/paylist_apply.php"  "$C2/review8-stale/orig/wg-paylist_apply.review7.php" "$C2/patched/wg/paylist_apply.php" wg-paylist-apply.review7-to-review8.diff

# New files: full content copies (no diff possible — they did not exist).
mkdir -p "$OUT/new-files"
cp "$C2/new/v2/qris_match.php" "$C2/new/v2/qris_rematch.php" "$OUT/new-files/"
cp "$C2/new/wg/paylist_match.php" "$C2/new/wg/paylist_rematch.php" "$C2/new/wg/csrf.php" "$OUT/new-files/"
cp "$C2/new/migrate-c2-v2.sql" "$C2/new/migrate-c2-wg.sql" "$C2/review7/"

# Manifest: sha256 of every original, patched, and new file.
{
    echo "# sha256 manifest — C2 review #8 bundle (generated $(date -u +%Y-%m-%dT%H:%M:%SZ))"
    echo "# originals verified byte-identical to production 23 Nov 2026, re-verified 24 Nov 2026 (zero drift)"
    echo "# patched = review #8 state: C2 (review #7) + birth-fix 24 Nov 07:54 + stale-binding fix 24 Nov 10:16"
    echo
    echo "## v2New originals (production)"
    (cd "$C2" && sha256sum v2new/notify.php v2new/member-payment.php v2new/payment-index.php v2new/csrf.php v2new/qris_apply.php bayar-hook/bayar.php.orig)
    echo
    echo "## v2New patched (review #8 state)"
    (cd "$C2" && sha256sum patched/v2/notify.php patched/v2/member-payment.php patched/v2/payment-index.php patched/v2/qris_apply.php bayar-hook/bayar.php)
    echo
    echo "## v2New new files"
    (cd "$C2" && sha256sum new/v2/qris_match.php new/v2/qris_rematch.php new/v2/csrf.php new/migrate-c2-v2.sql)
    echo
    echo "## warganet originals (production)"
    (cd "$C2" && sha256sum warganet/notify.php warganet/checkout.php warganet/index.php warganet/paylist_apply.php)
    echo
    echo "## warganet patched (review #8 state)"
    (cd "$C2" && sha256sum patched/wg/notify.php patched/wg/checkout.php patched/wg/index.php patched/wg/paylist_apply.php)
    echo
    echo "## warganet new files"
    (cd "$C2" && sha256sum new/wg/paylist_match.php new/wg/paylist_rematch.php new/wg/csrf.php new/migrate-c2-wg.sql)
    echo
    echo "## deV2 deployed (verified live 24 Nov)"
    echo "member/payment.php (deV2, birth-fix 24 Nov 07:52): fbc1fee093055ac3c9e4b0125898f219d5074fa16d231600305de3711b0e67c7"
    echo "bayar.php (deV2, birth-fix 24 Nov 07:52): 4ff18fc64ba215ce86bafdeeac68eb0e4ba0340280e5aaa4761cefabb1f17f9a"
    echo "bayarQR/qris_apply.php (deV2, review8 stale-binding fix 24 Nov 10:14): 99a99e487918f9e800d8f2969a8ce9ded71f8da6f0b57d975cb2a6390625d9bd"
    echo
    echo "## bundle diffs (this directory)"
    (cd "$C2/review7/diffs" && sha256sum *.diff new-files/* incremental/*.diff)
    if [ -d "$C2/review7/logs" ] && [ -d "$C2/review7/runners" ]; then
        echo
        echo "## test logs & runners (this directory)"
        (cd "$C2/review7" && sha256sum logs/*.log runners/*)
    fi
} > "$C2/review7/SHA256SUMS.md"
echo "manifest written"
ls -la "$OUT" "$OUT/new-files" "$OUT/incremental"
