# R36 diff checklist — R35 recheck accepted; watchpoints recorded; signoff matrix presented (comment id 6907321403)

ChatGPT's independent recheck of the R35 documents (comment 6907321403) closed both R34 findings at document level (verdict ACCEPTED WITH LIMITATIONS) and raised three implementation watchpoints, explicitly not new documentation blockers. No documentation correction was requested. This round is small by design: it records the watchpoints in the canonical documents and presents the consolidated owner-signoff matrix the recheck asks for before D-final. Documentation only; H1 remains open; no D-final, no implementation authorisation.

**Sizes:** design 357,552 bytes / 1,089 lines (R35: 355,325 / 1,085); file plan 75,743 bytes / 216 lines (R35: 74,999 / 214); new matrix 6,872 bytes / 64 lines. No schema, fixture, or gate change.

## Watchpoints (recorded, not new blockers)
- [x] Watchpoint 1 (key-loss invalidation handling) — one normative tightening: design §4.1 `source_account_key` row now states the nulling completes before any new-key grouping becomes active (no mixed old/new HMAC keys ever live at once) and, where the platform permits, the key generation + nulling run in one transaction. File plan §D export row records the same ordering.
- [x] Watchpoint 2 (epoch check on all routes/row writes) — already normative, no text change: the per-row check (§7.7A) applies to every row of every route by construction; F64/F67 already assert exact counts and the residual-file warning. Recorded in the §14 watchpoints paragraph so the Phase 1.3 implementer does not miss it.
- [x] Watchpoint 3 (documented case ≠ executed PASS) — recorded as a process rule in §14: no fixture has been executed yet; D-final requires the evidence the signoff matrix lists.
- [x] Design §14 new paragraph "Implementation watchpoints (R35 recheck, comment 6907321403)" records all three, with the owner's no-full-account-persistence and export privacy decisions preserved.

## Owner-signoff matrix (new document)
- [x] New standalone document `05-owner-signoff-matrix-v1.1.md` (6,872 bytes / 64 lines): §1 decisions already made (12 owner-locked decisions with comment IDs); §2 pending sign-offs (P2 supersession, C4, C20, C21, D-final, G11-pilot, G11-production — each with what the owner signs, evidence status, blockers); §3 evidence status (verified at document level vs not verified — no fixture executed, no clean build, F40/P11/version pins/API 28–30/heartbeat/endpoint inventory/contract tables/P6 gate/hop-identity stamping all open); §4 H1 status (open, D-final blocker); §5 what D-final unlocks and does not (spec approval only; Phase 1.3 needs separate authorisation; G11 gates are separate).
- [x] Definitions verified against the design before writing: P2 (§12.1 table, superseded by D8), C4 (§7.1), C20 (§12.1 table: PrivacyGate fixture set F1–F19), C21 (§12.1 table: capture handoff gate, §8.3–8.5/§9).

## Cross-cutting
- [x] Headers → R36 (both docs); §12.1 recheck sentence (comment 6907321403); §13 H1 line → R26–R36; §14 R36 supersessions paragraph + H1 recheck → R36 text.
- [x] File plan consolidation note: inputs R1–R36 + comment 6907321403; H1 → R36; R36 re-issue entry (notes the new matrix document).
- [x] Backups: R35 preserved as `.r35.bak` (md5-verified against the R35 staging copies used for the GitHub commit: design `70da7151…`, file plan `2af5ece6…`).
- [x] No residual design question: the only open items are the owner's decisions and the verification evidence in the matrix.
