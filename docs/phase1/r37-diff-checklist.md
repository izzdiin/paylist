# R37 diff checklist — Owner decisions P2/C4/C20/C21 integrated (comment id 6907505460)

The owner decided the first four pending items of the signoff matrix (P2 supersession ACCEPTED; C4/C20/C21 APPROVED). This round reflects all four in the consolidated decision register (§12.1) and the signoff matrix, and runs the contradiction check the owner comment requested. Documentation only; H1 remains open; no D-final, no implementation authorisation.

**Sizes:** design 358,976 bytes / 1,091 lines (R36: 357,552 / 1,089); file plan 76,214 bytes / 218 lines (R36: 75,743 / 216); matrix 6,813 bytes / 66 lines (R36: 6,872 / 64). No normative, schema, fixture, or gate change.

## Decision-register reflection (design §12.1)
- [x] P2 row: status column now "Superseded by D8; supersession ACCEPTED by owner (comment id 6907505460); sign-off complete".
- [x] C4/C20/C21 rows: annotated "(owner-approved 2026-11-16, comment id 6907505460)" on the description cell.
- [x] "Owner decisions now made" paragraph: gains the 2026-11-16 decisions (comment 6907505460) with the per-item substance (P2 span-redaction rule; C4 residual trade-off accepted; C20 execution is Phase 1.3; C21 F40 conditional) + "Integrated in MUSE-R37; contradiction check run, no open contradiction found".
- [x] "Still pending owner sign-off" sentence: now lists only D-final, G11-pilot, G11-production.

## Contradiction check (as requested by the owner comment)
- [x] P2 vs §7.1–7.2: verified consistent — §7.1(a) already states the approved rule (separable span redacted, rest preserved; whole-event drop only for pure-auth events).
- [x] C4 vs §7.1: consistent by construction (the approved text is §7.1 as written); §7.3A/§7.3B/§7.9 operate inside the C4 gates.
- [x] C20 vs §12.3: verified — F1–F11 and F12–F19 are all listed under PrivacyGateTest; no fixture in F1–F19 is owned by another test class.
- [x] C21 vs §8.3–8.5/§9: verified — every approved component is present as the C21 row defines it; the F40 caveat is already stated in §13 and matrix §3.
- [x] Result: no open contradiction remains. Stated in the R37 comment §2 with the per-item evidence.

## Signoff matrix
- [x] Header/status: PARTIALLY DECIDED (MUSE-R37) — 4 of 7 sign-offs decided; sources bumped to R37.
- [x] §1 gains four rows (P2, C4, C20, C21) with comment 6907505460.
- [x] §2: the four decided items removed (with a pointer to §1); D-final, G11-pilot, G11-production renumbered 1–3; D-final's blockers column drops "items 1–4 above" (now just "H1 open").
- [x] §3/§4/§5 unchanged (evidence status, H1 open, D-final scope).

## Cross-cutting
- [x] Headers → R37 (both docs); §13 H1 line → R26–R37; §14 open-items owner list now D-final/G11-pilot/G11-production only; §14 R37 supersessions paragraph + H1 recheck → R37 text.
- [x] File plan open-items line: P2/C4/C20/C21 moved from pending to the decided parenthetical; H1 → R37. Consolidation note: inputs R1–R37 + comment 6907505460; R37 re-issue entry.
- [x] Backups: R36 preserved as `.r36.bak` (design, file plan, matrix — md5-verified against the R36 staging copies used for the GitHub commit: design `4e5b7481…`, file plan `431eb0e1…`, matrix `1d2976a7…`).
- [x] No residual design question: the only open items are the owner's remaining decisions (D-final, G11-pilot, G11-production) and the verification evidence in the matrix.
