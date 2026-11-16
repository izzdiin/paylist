# R43 diff checklist — ChatGPT R42-review narrow correction (comment 6910432771)

Trigger: `[CHATGPT]` independent review of MUSE-R42 (comment id 6910432771, 2026-11-16T07:40:40Z):
outcome taxonomy ACCEPTED IN PRINCIPLE (verify §7.3A(e), B3, F23, F43 and
file-plan cross-references in canonical text); audit-field withdrawal accepted
with ONE narrow correction — the epoch-mismatch wording over-claimed
("proves exactly that"); BLOCKED records corroborate rather than prove
per-attempt; ordering comes from the test latch, not the mutable epoch; plus
the ANSWER to the pinned-value question (admission stamp + test-controlled
ordering sufficient for the B20 fixture; pinned per-attempt revocation_epoch
NOT required as a new v1.1 MVP schema field; do not represent the admission
stamp as durable forensic proof of exact historical revocation timing; any
such product requirement escalates explicitly for owner decision).
H1-A/B/D remain PROVISIONALLY ADDRESSED pending source verification; R42
publication set superseded by R43; publication/evidence request (commit SHA +
five blob SHAs + exact changed sections after an owner order to publish
documentation only); verdict note that no app-code change, test execution,
PR, pilot, or deployment is authorised by this review.

## Design — `04-final-design-v1.1.md` (374,364 → 377,689 bytes; 1,093 lines; md5 529593502037b6dee03b549c01424353)

- Header: R43 patch note appended (ChatGPT R42-review narrow correction,
  comment 6910432771).
- F43 B20 case (b): audit sentence rewritten. The R42 wording ("from which a
  reviewer establishes that a revocation intervened after admission by
  comparison with `route_consents.epoch` ... the route's PENDING deliveries
  transitioning to `BLOCKED` ... is the durable record of the revocation
  event itself") is CORRECTED: a mismatch between the admission stamp and the
  current `route_consents.epoch` proves ONLY that consent state changed after
  admission — not that the specific revocation intervened, nor its timing
  relative to the final admission read / first write (subsequent
  revoke/re-grant cycles also change the epoch; the current epoch is
  mutable). BLOCKED (`route_revoked`) transitions MAY corroborate that a
  revocation occurred but are not an immutable event tied to this attempt and
  do not by themselves establish ordering. Ordering in the fixture is
  established by the controlled fake-transport latch / test instrumentation
  (the latch is the deterministic ordering source), not inferred from the
  mutable current epoch. Stated plainly: no existing durable record provides
  independently verifiable forensic proof of the exact historical revocation
  timing for a given attempt; if such proof becomes a product requirement, it
  requires an explicit owner decision and a separately approved consent-change
  audit record or schema change — it is not assumed here. The revocation
  epoch value itself is still not pinned per attempt — no new field is
  introduced. The MUSE-R42 §2 phrase "the admission stamp plus the epoch
  mismatch proves exactly that" is WITHDRAWN as written.
- §13/§14: R43 supersessions entry added (taxonomy accepted in principle,
  cross-references verified against the local R42 text; audit-proof correction
  accepted in full; pinned-value question answered — no new §4.3 field in the
  v1.1 MVP; H1 stays OPEN pending recheck of the R43 text).
- Historical record kept: the R42 supersessions paragraph still quotes the old
  B20(b) wording as what R42 said; the R43 paragraph corrects it. The single
  remaining "proves exactly" occurrence in the design is the R43 B20(b) note
  naming the withdrawn R42 phrase, not a claim.

## File plan — `04-file-plan-v1.1.md` (79,552 → 80,246 bytes; 224 lines; md5 a1ee35f5fb6555f1398aa44dd0e0ba9a)

- Stage gates: B20 note gains "R43 audit-proof wording correction".
- `PrivacyGateTest` row: B20 note gains "R43 audit-proof wording correction".
- `ConsentTest` row: B20 note gains "R43 audit-proof wording correction".
- §G gate line: "F43 B20/B21 (R40/R41/R42/R43: ... R43 audit-proof wording
  correction; ...)".
- Consolidation note: R43 patch line added.

## Matrix — `05-owner-signoff-matrix-v1.1.md` (8,461 → 9,264 bytes; 66 lines; md5 64e97dad48d70eeb8849bb840cdcfda4)

- Header: status marker MUSE-R42 → MUSE-R43; sources (R42) → (R43).
- §3: "the R43 patch awaits publication by owner order and independent recheck".
- §4: R42-review recorded (taxonomy accepted in principle; audit-proof narrow
  correction; pinned-value answer — no new §4.3 field in the v1.1 MVP, no
  durable forensic proof claimed, explicit owner decision required for any
  such requirement; both corrected in MUSE-R43; H1-A/B/D provisionally
  addressed pending source verification after publication; recheck pending).

## Unchanged by this round

- No owner decision changed; no schema change; fixtures F1–F68 plus F43 B20/B21
  and F41 J–M; D→E gate unchanged (B20 still required, corrected).
- Backups: R42 design / file plan / matrix as `.r42.bak` (md5s match the
  issue1-watermark.json R42 record).
- No code change, commit, PR, pilot, or deployment. Publication of the R43 set
  (design, file plan, matrix, r42 checklist, r43 checklist) to `main/docs/phase1`
  awaits the owner's separate order, as in R33–R42.
