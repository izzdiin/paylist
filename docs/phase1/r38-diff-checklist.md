# R38 diff checklist — ChatGPT audit follow-ups on R37 matrix (comment id 6907620905)

ChatGPT's targeted integration review of MUSE-R37 accepted the owner-decision integration (P2/C4/C20/C21 reflected in design §12.1 and matrix §1, F40 retained conditional) and raised three matrix text-consistency defects. All three are accepted in full and patched here as a narrow documentation correction. Documentation only; H1 remains open; no D-final, no implementation authorisation.

**Sizes:** matrix 7,207 bytes / 66 lines (R37: 6,813 / 66). Design 358,976 bytes / 1,091 lines and file plan 76,214 bytes / 218 lines UNCHANGED (still R37). No normative, schema, fixture, or gate change.

## Accepted corrections (all three verified against the canonical text before patching)
- [x] 1a. Matrix §3: "The consolidated design (R36) and file plan (R36)" → "(R37) and file plan (R37)". Verified: matrix header/sources were already R37; only this sentence was stale.
- [x] 1b. Matrix §4: H1 re-issue text "patched R26–R36" → "patched R26–R37". Verified: design §13 already states "(R25, patched R26–R37)" and the r37 checklist records the same; the matrix lagged by one round.
- [x] 2. Matrix §5: "with their own evidence (§2, items 6–7)" → "(§2 items 2–3)". Verified: §2 was renumbered in R37 to items 1–3 (pilot=2, production=3); the r37 checklist itself recorded "§3/§4/§5 unchanged", which is how this cross-reference went stale in the same round that renumbered §2.
- [x] 3. Matrix §1 P3 row: "cross-account repeat = duplicate evidence" → "cross-account repeat = duplicate evidence ONLY where verified evidence proves the same bank transaction (case B, design §3.1.4; F46/F51) — otherwise distinct/indeterminate evidence ⇒ AMBIGUOUS/NEEDS_REVIEW, never presumed duplicate". Verified against design §3.1.4 ("but only where the decision table below (case B) proves it is the same bank transaction; otherwise it is distinct or indeterminate evidence under case (C)"), F46 (§10.2/§12.3: unverified/distinct identity ⇒ `ambiguous`/`NEEDS_REVIEW`; a unique-index conflict alone never proves duplicate-bank-transaction identity) and F51 (§12.3: "no false `duplicate_evidence`"). The old shorthand was the only one of the three defects with implementer-facing risk; the other two were pure cross-reference staleness.

## Matrix header
- [x] Status line: PARTIALLY DECIDED (MUSE-R38) — 4 of 7 sign-offs decided; D-final, G11-pilot, G11-production remain. Sources stay design (R37) + file plan (R37): neither document changed in this round.
- [x] Intro paragraph gains the R38 update sentence (comment 6907620905; no decision change, no normative change).

## Cross-cutting
- [x] Backups: R37 matrix preserved as `.r37.bak` (md5-verified against the R37 staging copy used for the GitHub commit).
- [x] No other stale "R36" or "items 6–7" reference remains in the matrix (grep-verified); no stale "R26–R36" or "items 6–7" reference exists in the design or file plan.
- [x] No residual design question: the only open items are the owner's remaining decisions (D-final, G11-pilot, G11-production), the verification evidence in matrix §3, and H1 (targeted review only — the ChatGPT comment states explicitly it is not an exhaustive independent full-design consolidation audit).
