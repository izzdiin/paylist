# R35 diff checklist — ChatGPT independent audit precision (comment id 6907216982)

ChatGPT reviewed the canonical R34 files directly from `main/docs/phase1` on GitHub (blob SHAs verified) and confirmed all six R33 follow-up corrections as integrated. Two remaining precision issues follow, both accepted and corrected in R35. Documentation only; H1 remains open; no D-final, no implementation authorisation.

**Sizes:** design 355,325 bytes / 1,085 lines (R34: 351,208 / 1,083); file plan 74,999 bytes / 214 lines (R34: 73,401 / 211). Schema unchanged except one definitional column (`match_results.source_account_masked`); 20-column CSV schema, fixture range F1–F68, no new fixture.

## A. HMAC rotation recovery/privacy (MAJOR)
- [x] Verification first: §3.1 defines the stored field as `source_account_masked` ("labelled span only, masked to last 4", sole occurrence in the design); no §4 table stores a full verified account identity; the masked value's storage location was undefined. The R34 re-derivation claim is therefore impossible as written — confirmed, not merely unproven.
- [x] Design §4.1 `source_account_key` row: the full verified identity exists only transiently at P3 confirmation (in memory) and is NEVER persisted — only the masked value and the HMAC key are stored. **Key loss (fail-closed):** a new export key is generated at first use and all existing keys are set to NULL in one pass (a no-op on first install); affected rows become unattributed (masked display unaffected); new captures derive fresh keys from freshly verified identities; old keys are never recovered; no re-derivation is attempted.
- [x] Design §4.6 `match_results`: `source_account_masked` TEXT nullable added — the ONLY stored form of the source account (masked to last 4).
- [x] Design §3.1 `source_account_masked` row: storage cross-reference to §4.6 + never-persisted statement.
- [x] Design §7.7A Filters: post-loss sentence (historical rows stay unattributed; no key re-derived without a fresh verified identity).
- [x] Design §10 + §12.3 F62 extended: key-loss case, backup/restore case (P10 option C excludes DB + Keystore; a DB-only backup yields NULL keys), unrecoverable-identity case (stays unattributed indefinitely, never fabricated). The R34 rotation sentence is replaced, not kept alongside.
- [x] File plan §C `EventEntity` row (key-loss fail-closed) + `MatchResultEntity` row (gains `source_account_masked`) + §D export row + §E test row + R35 blockquote.
- [x] No owner policy decision requested: the fail-closed option needs no new persistence. The consequence is stated plainly (historical rows lose account grouping until new notifications arrive; masked display values remain).

## B. Revocation vs SAF write race (MODERATE)
- [x] Design §7.7A authorization snapshot paragraph: precise guarantee stated normatively — no row is STARTED after a revocation is observed; the check/write pair is not atomic, so a row already in the write window may complete and previously written bytes may remain despite best-effort cleanup; no instant revocation, no retraction claimed.
- [x] Design §10 + §12.3 F64/F67: adverse interleaving case (revocation injected between the epoch check and the row write via controllable epoch injection; in-flight row may complete, NO further row written; FAILED audit with truthful counts; cleanup attempted; warning shown).
- [x] File plan §D export row (precise guarantee) + §E test row + R35 blockquote.

## Cross-cutting
- [x] Headers → R35 (both docs); §12.1 audit sentence (comment 6907216982); §13 H1 line → R26–R35; §14 R35 supersessions paragraph + H1 recheck → R35 text.
- [x] File plan consolidation note: inputs R1–R35 + comment 6907216982; H1 → R35; R35 re-issue entry.
- [x] Backups: R34 preserved as `.r34.bak` (md5-verified against the R34 staging copies used for the GitHub commit: design `0adc8e97…`, file plan `86f5adec…`).
- [x] Stale-text sweep: live "re-deriv" uses are only the new rule (new captures re-derive from fresh identities) and negations (never re-derived / no re-derivation attempted); the R34 rotation claim survives only inside the historical §14 R34 supersessions record and the historical file-plan R34 blockquote.
- [x] No residual policy question: the audit's either/or is decided (fail-closed), with the consequence stated in the R35 comment §3.
