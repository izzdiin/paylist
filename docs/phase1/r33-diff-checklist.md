# R33 diff checklist — ChatGPT independent audit corrections on the R32 export text

Source: ChatGPT independent audit of the R32 documents (comment id 6906246551, 16 Nov 2026 10:13 WIB).
Round: [MUSE-R33] — documentation only; not D-final, not implementation authorisation.
R32 preserved as `.r32.bak` (md5-verified against the R32 files).

Sizes: design 332,537 bytes / 1,067 lines (R32) → **343,514 bytes / 1,076 lines** (R33).
File plan 67,917 bytes / 204 lines (R32) → **70,798 bytes / 208 lines** (R33).

## Design (`04-final-design-v1.1.md`)

- [x] Header → R33 (R29 + R30 + R31 + R32 + R33 AUDIT CORRECTIONS); §0 re-issue history + R33 patch sentence.
- [x] §4.1 `events` table: new `source_account_key` column (nullable, opaque stable key for the verified source account, derived at P3 confirmation; export filtering/grouping only; never exported/logged).
- [x] §4.7 `export_audit`: new `epochs_json` column (nullable authorization snapshot: per-route consent/config epochs + opt-in + frozen row-set fingerprint at write start).
- [x] §7.7A purpose/scope: "LF line endings" → CRLF record separators per RFC 4180 §2.1 (MODERATE F); the R32 wording is called out as wrong.
- [x] §7.7A row grain (BLOCKER A): grain now event×route rows PLUS exactly one event-level zero-hop row; new "Zero-hop events" paragraph — `route_kind` NONE, `route_label` empty, `route_id` sentinel `none`, `row_id` SHA-256 of `event_ref + "|none"`, `server_outcome` NOT_DISPATCHED, `resolution_label` UNRESOLVED_NEEDS_REVIEW/UNRESOLVED_UNKNOWN; zero-hop row only when `hop_outcomes[]` is empty; partial chains keep reached-hop rows only; same privacy/consent eligibility; never implies dispatch/settlement/capture.
- [x] §7.7A row identity (BLOCKER B): `event_ref` is capture-event identity, NOT bank-transaction identity; bank-transaction identity only via verified `bank_reference` + source/merchant scope (§3.1.4 case B); counting rule: group by verified `bank_reference` where present, otherwise count distinct `event_ref`s as captured events (distinct-or-indeterminate, never merged/split on `event_ref` alone). Column 2 rule corrected accordingly.
- [x] §7.7A schema: column 13 `source_account` rule — filtering/grouping use the internal verified account key, not the masked display value (MAJOR D); column 15 `route_kind` gains `NONE` (zero-hop rows only); column 18 `server_outcome` gains `NOT_DISPATCHED` (zero-hop rows).
- [x] §7.7A formula-injection guard (MODERATE G): residual-risk paragraph — the `'` prefix is a mitigation, not a guarantee across every import path; machine consumers MUST use a real CSV parser and MUST NOT evaluate cell content; residual risk documented in `docs/operations.md`; no absolute-guarantee claim.
- [x] §7.7A filters (MAJOR D): source-account filter matches the internal verified account key, never the masked display value; same-last-four collision → separate filter entries by P3 source-pattern label, or one explicitly-ambiguous bucket including both accounts, never silently one; ambiguity visible in the confirmation dialog.
- [x] §7.7A new "Authorization snapshot and revalidation" paragraph (MAJOR E): snapshot (per-route consent/config epochs + opt-in + filter + frozen row set) captured after device auth and immediately before the first byte is written; revalidated before the first row write and at every periodic flush; epoch change or lost opt-in aborts the write (FAILED audit, truthful rows-written count, `notes` naming the epoch change, best-effort partial delete + warning); rows arriving after the snapshot never added mid-export; audit records the snapshot epochs (`export_audit.epochs_json`).
- [x] §7.7 diagnostic-export paragraph (MAJOR C): "The export function enforces this by construction" → hypothetical future requirement ("A future diagnostic export function would enforce this…; no such function exists in v1.1").
- [x] §7.8 D7 (MAJOR C): "F35 asserts statically that no export function exists in the v1.1 tree" → "F35 asserts statically that no diagnostic/recovery export function exists in the v1.1 tree — the only export function is the gated reconciliation export (§7.7A)". (The F35 fixture text itself was already correct in R32; only the D7 restatement was stale.)
- [x] §10: F56 extended (zero-hop rows + CRLF); F61 extended (Excel/LibreOffice Calc/Google Sheets import tests + adversarial cases + documented residual risk); F62 extended (same-last-four collision via internal key); F63 extended (partial chain vs zero-hop; captured-event count wording); F64 extended (snapshot + mid-write epoch-change abort); F67 extended (epoch-change abort); new F68 (bank-transaction grouping: two events verified as one bank transaction vs two distinct bank transactions sharing one order number).
- [x] §11: D→E gate + F56–F68.
- [x] §12.3: header F1–F68; F56/F61/F62/F63/F64/F67 entries updated; F68 entry added; test ownership `ReconciliationExportTest` F56–F68.
- [x] §13: H1 line → patched R26–R33.
- [x] §14: R33 supersessions paragraph added; open items — H1 re-issue confirmation → R33 patched text.
- [x] §12.1: no change — no new owner decision in this round (zero-hop-row inclusion is specified within the already owner-approved Q1 scope; subject to D-final sign-off).

## File plan (`04-file-plan-v1.1.md`)

- [x] Header → R33.
- [x] Stage-gate preamble: D→E + F56–F68.
- [x] §C: `EventEntity` row + `source_account_key`; `ExportAuditEntity` row + `epochs_json`.
- [x] §D: `ReconciliationExport.kt` row revised (zero-hop NONE rows, CRLF, capture-event vs bank-transaction identity, internal account key + collision handling, authorization snapshot/revalidation, residual-risk note); `ReconciliationExportActivity.kt` row revised (ambiguous-bucket filter display).
- [x] §E: R33 ownership blockquote added (F68; F56/F61/F62/F63/F64/F67 extensions); `ReconciliationExportTest` row extended (F56–F68 + F68); `docs/operations.md` row extended (snapshot procedure + residual risk).
- [x] §G: item 4 (export work + gate, F56–F68); approval gates D→E + F56–F68.
- [x] Consolidation note: R33 re-issue entry added.

## Residual questions

None. All seven audit findings (BLOCKER A–B, MAJOR C–E, MODERATE F–G) are fully specified. No new owner decision is requested: the zero-hop-row inclusion sits inside the already owner-approved Q1 scope (manual reconciliation of privacy-cleared unresolved items) and remains subject to D-final sign-off; the owner may still confirm or narrow it at D-final.

## Verification

- [x] No leftover normative "LF line endings" in either document (the one remaining occurrence is inside the §7.7A correction note quoting the R32 wording, and inside the historical R32 supersession record).
- [x] No leftover "no export function exists in the v1.1 tree" outside the historical R32 record (the D7 sentence now reads "no diagnostic/recovery export function").
- [x] No leftover "F56–F67" gate/ownership text outside historical records.
- [x] §12.3 header F1–F68; all F61–F68 entries present in both §10 and §12.3.
- [x] `event_ref` no longer described as bank-transaction identity anywhere outside historical records.
- [x] H1 still a D-final blocker (no claim of ChatGPT sign-off or Phase 1.3 authorisation).
- [x] Unrelated locked items untouched: P3/P6/P7/P9/P10/P11/P12, cutover gates, F55, the four owner-locked export decisions.
