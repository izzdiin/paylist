# R41 diff checklist — ChatGPT R40-review correction (comment 6908544054)

Trigger: `[CHATGPT]` review of MUSE-R40 (comment id 6908544054, 2026-11-16T05:51:01Z):
H1-A/B/D provisionally addressed pending source verification; H1-C REMAINS OPEN
(B20(a) internally inconsistent); publication/evidence request (commit SHA + four
blob SHAs + r40 checklist after an owner order to publish documentation only);
verdict note that F1–F68 and new cases are design fixtures, not executed PASS results.

## Design — `04-final-design-v1.1.md` (368,354 → 371,471 bytes; 1,093 lines; md5 e732d8ddaa80446a4b4eb1ec6884826f)

- Header: R41 patch note appended (ChatGPT R40-review correction, comment 6908544054).
- §7.3A(e): admission fence marked "(normative, R40; fixture corrected R41)";
  added "No fixture asserts zero bytes for a revocation landing in that window;
  F43 B20 case (b) asserts the residual outcome (first write proceeds, POST
  completes, response stands)."
- §7.3B disclosure: tightened to "a revocation that commits after the final
  admission read but before the first write cannot prevent the first write —
  that window is bounded to the read-to-first-write instruction sequence, with
  no I/O between them, and no fixture asserts zero bytes for a revocation
  landing in it (F43 B20 case (b))."
- F43 B3: scoped to revokes observed at or before the final admission read;
  post-read timing cross-referenced to B20 case (b), post-first-write to B20 case (c).
- F43 B20: rewritten. Case (a): revoke commits at or before the final admission
  read (incl. between the pre-send gate and the latch) ⇒ zero bytes, `BLOCKED`.
  Case (b) (new, R41 — the disclosed residual): revoke commits after the final
  admission read but before the first write ⇒ first write proceeds, POST completes,
  response stands, no further raw POST; revocation epoch noted in the audit; fixture
  asserts bytes MAY be written. The R40 case-(a) zero-byte expectation for that
  timing is STRUCK. Case (c) (old case (b)): revoke after the first write began ⇒
  POST completes, response stands. Mapping: old (a) → struck/replaced by new (b);
  old (b) → new (c); old (c) → folded into new (a).
- F23 register line: corrected to "revoke observed at or before the final admission
  read ⇒ no byte transmitted (B20 case (a)); revoke landing after the final admission
  read but before the first write is the disclosed residual (B20 case (b)) — the
  check/write pair is not atomic".
- §11 D→E gate: B20 note "(R41 corrects case (a))".
- §13/§14: "patched R26–R40" → "patched R26–R41"; R41 supersessions entry added
  (H1-C correction, alternative considered and not adopted with reasons, H1 stays OPEN
  pending independent recheck of the R41 text).

## File plan — `04-file-plan-v1.1.md` (78,242 → 78,975 bytes; 220 → 222 lines; md5 4c14317c5375a327814bc4441b9e873a)

- Stage gates: B20 note "(R41 corrects case (a))".
- `PrivacyGateTest` row: B3 scope (observed at or before the final admission read;
  post-read timing is B20 case (b)); B20 note "(R41 corrects case (a))".
- `ConsentTest` row: F23 fence race reworded (observed-at-or-before ⇒ zero bytes;
  after the final read is the disclosed residual, B20 case (b)).
- §G gate line: "F43 B20/B21 (R40/R41: revocation admission fence, R41 fixture correction; ...)".
- Consolidation note: R41 re-issue line added.

## Matrix — `05-owner-signoff-matrix-v1.1.md` (7,637 → 7,930 bytes; 66 lines; md5 3b85071e06e6211fdd97887f8eb87a8e)

- Header: status marker MUSE-R38 → MUSE-R41; sources (R40) → (R41).
- §3: "the R41 patch awaits publication by owner order and independent recheck".
- §4: H1-C B20(a) correction recorded as R41; H1-A/B/D provisionally addressed
  pending source verification after publication; recheck pending.

## Unchanged by this round

- No owner decision changed; no schema change; fixtures F1–F68 plus F43 B20/B21
  and F41 J–M; D→E gate unchanged (B20 still required, corrected).
- Backups: R40 design / file plan / matrix as `.r40.bak` (md5s match the
  issue1-watermark.json R40 record).
- No code change, commit, PR, pilot, or deployment. Publication of the R41 set
  (design, file plan, matrix, r40 checklist, r41 checklist) to `main/docs/phase1`
  awaits the owner's separate order, as in R33–R40.
