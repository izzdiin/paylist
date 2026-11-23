package com.netter.paylist.parser

/**
 * Transaction direction from text (design §7.1 confidence matrix,
 * §7.2).
 *
 * The same two-list OUT-first scan `catat.php` runs, in memory
 * over transient raw text, using the COMMON-SUBSET vocabulary of
 * both PHP copies (verified 15 Nov 2026, R10): words present in
 * only one copy (`pengeluaran`, `spent`, `pemasukan`, `received`)
 * are NOT in the lists — a constructed text using them would
 * classify differently on the two servers.
 *
 * Server substring semantics (`mb_strpos`): a list word matches
 * anywhere in the lowercased text, including inside a longer word
 * (`REF BAYAR 9` contains `bayar` and flips direction to OUT).
 * OUT is checked first, then IN; no list word ⇒ UNKNOWN, never
 * guessed.
 *
 * The keyword lists live in two PHP files and can drift — fixture
 * F19 pins the lists as verified 15 Nov 2026; any PHP list change
 * requires re-running the fixture and, if the common vocabulary
 * changes, a client update.
 */
enum class Direction {
    IN,
    OUT,
    UNKNOWN,
}

object DirectionHeuristic {

    /** OUT words in the common vocabulary of both `catat.php` copies. */
    val OUT_WORDS: List<String> = listOf(
        "terdebit", "debit", "keluar", "pembayaran", "bayar",
        "tarik tunai", "belanja", "pembelian", "transfer ke", "kirim ke",
    )

    /** IN words in the common vocabulary of both `catat.php` copies. */
    val IN_WORDS: List<String> = listOf(
        "masuk", "diterima", "kredit", "setoran",
        "top up", "topup", "refund", "pengembalian",
    )

    /**
     * Classifies [text] with the server heuristic: OUT first, then
     * IN, else UNKNOWN. Substring match on lowercased text, exactly
     * as the PHP `mb_strpos` scan.
     */
    fun classify(text: String): Direction {
        val hay = text.lowercase()
        if (OUT_WORDS.any { hay.contains(it) }) return Direction.OUT
        if (IN_WORDS.any { hay.contains(it) }) return Direction.IN
        return Direction.UNKNOWN
    }

    /** True when [text] contains any direction-list word (either list). */
    fun containsDirectionWord(text: String): Boolean {
        val hay = text.lowercase()
        return OUT_WORDS.any { hay.contains(it) } || IN_WORDS.any { hay.contains(it) }
    }
}
