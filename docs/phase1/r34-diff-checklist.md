# R34 diff checklist — ChatGPT independent recheck precision (comment id 6906879704)

ChatGPT read the canonical R33 files directly from `main/docs/phase1` on GitHub (blob SHAs verified) and confirmed the seven R32 findings as integrated. Six follow-up precision items follow, all accepted and corrected in R34. Documentation only; H1 remains open; no D-final, no implementation authorisation.

**Sizes:** design 351,208 bytes / 1,083 lines (R33: 343,514 / 1,076); file plan 73,401 bytes / 211 lines (R33: 70,798 / 208). Schema unchanged: 20 columns, fixture range F1–F68, no new fixture.

## 1. Group key / miscount (MAJOR)
- [x] Design §7.7A "Stable row identity": the R33 counting claim (grouping by verified `bank_reference` + source/merchant scope) is STRUCK; `event_ref` grouping counts captured events only.
- [x] Design §7.7A new paragraph "Bank-transaction counting (normative, R34)": automated distinct-bank-transaction totals from the CSV are expressly disallowed; `bank_reference` is a manual reconciliation aid only; no grouping key may be fabricated from text; distinct-transaction counting happens only in verified external reconciliation.
- [x] Design §10 + §12.3 F68 rewritten as a disallowance with the four cases: equal references from different issuing banks (never merged), same payment across two source accounts (never auto-merged), missing/ambiguous scope (no grouping claim), separate transfers sharing one order number (separate groups).
- [x] Design §13: new limitation bullet (counting disallowed in v1.1; the CSV is a reached-hop, captured-event report).
- [x] File plan §D `ReconciliationExport.kt` row: "no bank-transaction grouping key — automated distinct-bank-transaction totals disallowed (R34)"; §E test row F68 = "bank-transaction grouping disallowance".
- [x] §3.1.4 case B untouched (advisory internal matcher reasoning, not an exportable grouping key).

## 2. Account key privacy/identity (MAJOR)
- [x] Design §4.1 `source_account_key` row: lowercase hex HMAC-SHA256 of the canonical verified identity (issuer + account identifier, trimmed, case-folded, separators removed), keyed by a Keystore-protected export key; never a raw hash; NULL on insufficient verified identity (fail-closed); never from the package alone or a source-pattern label.
- [x] Design §7.7A row identity / Filters: no text change needed beyond §4.1 (the key's derivation is specified once, in §4.1); rotation rule stated in the R34 comment and F62 (new Keystore key re-derives all keys from stored verified identities; old keys discarded, never reused).
- [x] Design §10 + §12.3 F62 extended: full-source identity collision (different keys), key stability across exports/restarts, rotation re-derivation, fail-closed.
- [x] File plan §C `EventEntity` row + §D export row: Keystore-HMAC derivation noted (R34).

## 3. Multi-route zero-hop consent (MAJOR)
- [x] Design §7.7A zero-hop paragraph: the row carries capture-level fields only; exportable iff ≥1 target route has current consent (snapshot per-route epochs); all-revoked / no consenting target ⇒ excluded entirely; data attributable only to a revoked route never exported; audit records per-route epochs.
- [x] Design §7.7A privacy eligibility (b): per-route rows need their own route's consent; the zero-hop row needs ≥1 consenting target.
- [x] Design §10 + §12.3 F56/F58/F64 extended with the multi-route case.
- [x] File plan §D export row + §E test row: multi-route zero-hop consent noted.

## 4. Partial chain visibility (MODERATE)
- [x] Design §7.7A new paragraph "Partial chains and downstream visibility (normative, R34)": the CSV is a reached-hop report; unreached hops are NOT represented (summary row rejected — breaks the grain; summary column rejected — breaks the 20-column schema); exact actionable UI warning wording; the delivery screen is authoritative for chain status.
- [x] Design §10 + §12.3 F63 case: reached hops exported, unreached hops absent, exact warning shown.
- [x] File plan §D export row (reached-hop report) + Activity row (partial-chain warning UI) + §E test row.

## 5. Revalidation timing (MODERATE)
- [x] Design §7.7A authorization snapshot paragraph: revalidation before the first row write AND before every row write (authorization check per row; flush stays periodic); abort at the next row on epoch change; residual exposure disclosed (rows written before the abort remain; cleanup best-effort; the exporter never claims an already-written file is retractable).
- [x] Design §10 + §12.3 F64/F67 adverse timing scenario (revocation between rows ⇒ abort at the next row).
- [x] File plan §D export row + §E test row: per-row revalidation + adverse timing.

## 6. F61 gate (MODERATE)
- [x] Design §10 + §12.3 F61 split: (a) deterministic automated injection unit tests (the mandatory D→E gate, runnable with no spreadsheet application); (b) platform Excel/Calc/Sheets import checks as tracked manual compatibility evidence in the Stage E pilot (PASS/FAIL/NOT RUN per platform/version; NOT RUN never a PASS; required release-checklist evidence for G11).
- [x] Design §11 stage gates: D→E notes F61's automated tests gate D→E and its platform checks are manual Stage E evidence; E→release gains the F61 manual evidence.
- [x] File plan §G item 5 (manual spreadsheet evidence + gate) + approval gates (D→E note + E→release F61 evidence) + §E test row + R34 blockquote.

## Cross-cutting
- [x] Headers → R34 (both docs); §12.1 recheck sentence (comment 6906879704); §13 H1 line → R26–R34; §14 R34 supersessions paragraph + H1 recheck → R34 text.
- [x] File plan consolidation note: inputs R1–R34 + comments 6906246551/6906879704; H1 → R34 (also fixed the stale R32 reference the R33 round left); R34 re-issue entry.
- [x] Backups: R33 preserved as `.r33.bak` (md5-verified against the user/files R33 copies).
- [x] Stale-text sweep: "where the test environment permits", "e.g. SHA-256 of the verified account identity", "groups rows by verified `bank_reference`" = 0 occurrences; "at every flush" survives only inside the historical §14 R33 supersessions record.
- [x] No residual policy question: both either/or choices are decided with stated reasons (group-key disallowance; partial-chain documentation).
