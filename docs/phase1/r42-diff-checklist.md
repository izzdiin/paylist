# R42 diff checklist — ChatGPT R41-review clarifications (comment 6909262016)

Trigger: `[CHATGPT]` independent review of MUSE-R41 (comment id 6909262016, 2026-11-16T06:33:29Z):
H1-C ACCEPTABLE IN PRINCIPLE with two narrow clarifications; H1-A/B/D remain
PROVISIONALLY ADDRESSED pending source verification; R41 publication set superseded
by R42; publication/evidence request (commit SHA + five blob SHAs + exact changed
sections after an owner order to publish documentation only); verdict note that
fixtures are unexecuted and no app-code change, test execution, PR, pilot, or
deployment is authorised by this review.

## Design — `04-final-design-v1.1.md` (371,471 → 374,364 bytes; 1,093 lines; md5 8fb4e06a24d401ff4c9936aeca4acad1)

- Header: R42 patch note appended (ChatGPT R41-review clarifications, comment 6909262016).
- §7.3A(e): "F43 B20 case (b) asserts the residual outcome (first write proceeds,
  POST completes, response stands)" → "(the first write proceeds; the attempt runs
  to its outcome under the ordinary §5.4 result taxonomy — success, failure, or
  UNKNOWN — and a response, if one was received, stands)". "After it, the POST
  completes, the response stands (§7.5(c))" → "After it, the attempt runs to its
  outcome under the ordinary §5.4 result taxonomy (§7.5(c)); a response, if one was
  received, stands".
- F43 B20: case (b) rewritten — first write proceeds; attempt runs to its outcome
  under the ordinary §5.4 result taxonomy (success, failure, or UNKNOWN; socket/
  network failure remains possible; revocation does not cancel the attempt
  retroactively); response, if received, stands; no further raw POST follows. The
  R41 phrase "the revocation epoch noted in the audit" is WITHDRAWN as written and
  replaced with the existing-field wording: the attempt row records the admission
  stamp (`delivery_attempts.consent_epoch`, `fence_generation`); a reviewer
  establishes the post-admission revocation by comparison with
  `route_consents.epoch`; the route's PENDING deliveries transitioning to `BLOCKED`
  (`route_revoked`) is the durable record of the revocation event. Stated plainly:
  the revocation epoch value itself is NOT pinned per attempt — no new field is
  introduced. Case (c) reworded the same way (runs to its §5.4 outcome; response,
  if received, stands). The pinned-value question (explicit §4.3 schema amendment)
  is left open to the reviewer (MUSE-R42 §2).
- F43 B3 parenthetical: "a revoke after the first write is B20 case (c): the POST
  completes, the response stands" → "the attempt runs to its §5.4 outcome; a
  response, if received, stands".
- §11 D→E gate: B20 note "(R41 corrects case (a); R42 outcome-taxonomy +
  audit-field precision)".
- §13/§14: R42 supersessions entry added (both clarifications accepted; open
  pinned-value question; H1 stays OPEN pending recheck of the R42 text).
- Historical record kept: the R41 supersessions paragraph still quotes the old
  B20(b) wording ("POST completes, response stands, revocation epoch noted in the
  audit") as what R41 said; the R42 paragraph withdraws it. Remaining "POST
  completes" occurrences outside §13/§14 are pre-existing F40/fence-lifecycle
  fixture language (checkpoint sequences), not B20 outcome claims.

## File plan — `04-file-plan-v1.1.md` (78,975 → 79,552 bytes; 222 → 224 lines; md5 eecc082c64597452625ec1b5a503c074)

- Stage gates: B20 note "(R41 corrects case (a); R42 outcome-taxonomy +
  audit-field precision)".
- `PrivacyGateTest` row: B20 note "(R41 corrects case (a); R42 outcome-taxonomy +
  audit-field precision, co-owned with `ConsentTest`)".
- `ConsentTest` row: B20 note "(admission fence; R42 outcome-taxonomy +
  audit-field precision)".
- §G gate line: "F43 B20/B21 (R40/R41/R42: revocation admission fence, R41 fixture
  correction, R42 outcome-taxonomy + audit-field precision; ...)".
- Consolidation note: R42 re-issue line added.

## Matrix — `05-owner-signoff-matrix-v1.1.md` (7,930 → 8,461 bytes; 66 lines; md5 b5d9289f0f9151dba952d0d301ed58b1)

- Header: status marker MUSE-R41 → MUSE-R42; sources (R41) → (R42).
- §3: "the R42 patch awaits publication by owner order and independent recheck".
- §4: R41-review clarifications recorded (H1-C acceptable in principle; both
  corrections in MUSE-R42; pinned-value question open; H1-A/B/D provisionally
  addressed pending source verification after publication; recheck pending).

## Unchanged by this round

- No owner decision changed; no schema change; fixtures F1–F68 plus F43 B20/B21
  and F41 J–M; D→E gate unchanged (B20 still required, corrected).
- Backups: R41 design / file plan / matrix as `.r41.bak` (md5s match the
  issue1-watermark.json R41 record).
- No code change, commit, PR, pilot, or deployment. Publication of the R42 set
  (design, file plan, matrix, r41 checklist, r42 checklist) to `main/docs/phase1`
  awaits the owner's separate order, as in R33–R41.
