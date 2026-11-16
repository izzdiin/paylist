# Paylist — Owner Signoff Matrix v1.1

**Status:** PARTIALLY DECIDED (MUSE-R44, 16 November 2026) — 4 of 7 sign-offs decided; H1 CLOSED by owner decision (see §4); D-final, G11-pilot, G11-production remain · **Sources:** `04-final-design-v1.1.md` (R43) + `04-file-plan-v1.1.md` (R43) + GitHub Issue izzdiin/paylist#1 · **Mode:** DOCUMENTATION ONLY — this matrix records decisions and evidence; it grants no implementation, pilot, or production authorisation.

This document answers the recheck's request (comment 6907321403): present the consolidated owner-signoff matrix and evidence before requesting D-final. Nothing in it is approved until the owner says so, per item. Updated in MUSE-R37 with the owner decisions P2/C4/C20/C21 (comment 6907505460). Updated in MUSE-R38 with the ChatGPT audit follow-ups (comment 6907620905): matrix §3/§4/§5 cross-reference corrections and the P3 case-B qualifier in §1; no decision change, no normative change. Updated in MUSE-R44 with the H1 closure (owner decision, 16 November 2026, chat 17:15 WIB) and the R43 publication record (commit 3fbfcdda); the design and file plan are unchanged in R44.

## 1. Decisions already made (owner-locked, for the record)

| Decision | Owner decision | Issue comment |
|---|---|---|
| P1 (pattern set + disclosure text) | ACCEPTED, subject to the specified tests and platform-policy review | 6902737515 |
| P3 (identity/attribution) | REVISED, then P3(a)–(d) CONFIRMED: explicit/manual merchant pattern registration only; uniqueness per merchant across all source accounts; cross-account repeat = duplicate evidence ONLY where verified evidence proves the same bank transaction (case B, design §3.1.4; F46/F51) — otherwise distinct/indeterminate evidence ⇒ AMBIGUOUS/NEEDS_REVIEW, never presumed duplicate | 6902737515, 6903307191 |
| P6 (raw forwarding) | Option B (granular informed opt-in) + guarded best-effort fallback; RAW_UNAVAILABLE is a distinct held state | 6893621818, 6896048588 |
| P7 (reverse migration) | ACCEPTED: no automated reverse migration in MVP; tested manual recovery runbook is a pre-pilot gate | 6902737515 |
| P8 (server-side NEEDS_REVIEW) | ACCEPTED: client-side hold for v1.1; server-side validation is post-v1.1 | 6902737515 |
| P9 (pre-v1.1 privacy reprocessing) | Blanket cleanup REJECTED; narrowed non-destructive per-item strategy APPROVED | 6902737515, 6903307191 |
| P10 (privacy at rest / device loss) | Option C: no transaction backup, conspicuous loss warning | 6893621818 |
| P11 (v2New verification) | Verify the actual v2New endpoint before pilot; no equivalence to warganet assumed | 6893621818 |
| P12 (server reconciliation) | Build the status-query endpoints (P12-revised, as amended by D1–D4) | 6893621818 |
| §7 exception policy | ACCEPTED: bounded per-route P6 Option B (mixed financial+OTP only, pure-auth excluded, in-memory raw handling) | 6902737515 |
| Q1 (reconciliation export) | REVISED: structured transaction reconciliation export REQUIRED in v1.1 MVP (CSV mandatory); diagnostic/recovery export remains prohibited | 6905797710 |
| Export policy trade-offs | LOCKED: identifiers default OFF with no cross-export persistence; recorder records included; stable anonymous row ID required; currency-appropriate decimal amounts | 6905986632 |
| P2 (supersession) | ACCEPTED: superseded by D8 (revised C4, §7.1–7.2 — financial record preserved, secret span redacted); whole-event drop only for pure-auth events | 6907505460 |
| C4 (PrivacyGate rule) | APPROVED: §7.1 four-class rule as the final form; residual Option A′ trade-off accepted (security objective, not a proof) | 6907505460 |
| C20 (PrivacyGate fixtures) | APPROVED: fixture set F1–F19 (§12.3) as the acceptance basis; execution is a Phase 1.3 matter | 6907505460 |
| C21 (capture handoff gate) | APPROVED: §8.3–8.5, §9 as written; F40 remains conditional until it runs on the actual stack | 6907505460 |

## 2. Pending owner sign-offs (the matrix)

Items P2, C4, C20, C21 were decided 2026-11-16 (comment 6907505460) and are recorded in §1. Three sign-offs remain:

| # | Decision | What the owner signs | Evidence status | Blockers |
|---|---|---|---|---|
| 1 | D-final | Spec v1.1 approval (the consolidated design + file plan as the buildable specification) | H1 CLOSED by owner decision 2026-11-16 (see §4); the consolidated design + file plan (R43, published to `main`) await the owner's explicit separate approval | Owner decision only |
| 2 | G11-pilot | Pilot gate: the v1.1 build may run on the pilot device | Blocked on D-final + the pre-pilot evidence (§3) | D-final; F40 on the actual stack; P11 v2New; clean build on laptopgrt; per-route/endpoint contract tables |
| 3 | G11-production | Production gate: the v1.1 build may run on the production device | Blocked on G11-pilot | G11-pilot |

## 3. Evidence status (what is verified, what is not)

**Verified at document level:**
- The consolidated design (R43) and file plan (R43) are complete and mutually consistent as local documents and are published to `main/docs/phase1` on GitHub (commit 3fbfcdda, 2026-11-16T08:05:32Z), verified read back via blob SHAs and byte-identical public raw downloads; the reviewer independently confirmed the five canonical blobs (comment 6913317655), and the R44 reply (comment 6913442853) confirmed the publication from both sides. H1 is CLOSED by owner decision (see §4).
- Independent rechecks R25–R35: every finding raised by the reviewer has been answered in the documents; the R35 recheck verdict is ACCEPTED WITH LIMITATIONS (both R34 findings CLOSED at document level, comment 6907321403).
- All owner-locked decisions in §1 are recorded in the design (§12.1) and the file plan (consolidation note).

**NOT verified — no test has been executed, no code has been written:**
- Fixtures F1–F68 are documented test cases, not executed PASSes. A documented fixture is not evidence until it runs.
- No clean build has been run (Stage A, on laptopgrt, with the documented toolchain).
- F40 (the `NEVER_SENT` rule + bytes invariant) has not run on the actual stack; the rule is conditional until it passes.
- P11: the actual v2New endpoint implementation, response, dedupe, matching/apply, and idempotency semantics are unverified; no v2New contract row exists.
- Version pins (Room/WorkManager/Kotlin/KSP against AGP/Kotlin/SDK) are unvalidated.
- API 28–30 device-transfer behaviour is unverified (F35).
- Heartbeat alert threshold is unchosen (needs device measurements; 20 minutes is not assumed safe).
- Existing HTTP endpoint policy inventory is not done.
- Per-route prior-effect table and per-endpoint contract table are pre-pilot deliverables, not yet written.
- P6 platform/policy verification gate (Google Play policy on forwarding authentication codes; notification-listener consent UX) is not checked.
- Hop-identity stamping is unspecified; until specified and built, same-system multi-hop rows stay `HELD`.

## 4. H1 status

H1 (re-issue confirmation) is CLOSED by owner decision, 2026-11-16 (chat, 17:15 WIB). It was the D-final blocker; it no longer blocks D-final. History: H1 was to close only when the independent reviewer confirmed the complete re-issued text (§7.3A/§7.3B/§7.9, patched R26–R40) at the full-design consolidation/approval gate. The R35 recheck closed the two R34 export findings at document level; it did not close H1. The H1 full-consolidation audit (comment id 6908017568) raised four findings — H1-A (BLOCKER, §7.3B(5) negative reconciliation), H1-B (MAJOR, §8.7 E1/E2 historical binding), H1-C (MAJOR, §7.3A(e) revocation fence), H1-D (MODERATE, §0 stale sign-off list) — all corrected in MUSE-R40; the R40 review (comment id 6908544054) found H1-C's B20(a) internally inconsistent and it was corrected in MUSE-R41 (case (a) struck for the post-read timing; that timing is now case (b), the disclosed residual); H1-A/B/D provisionally addressed pending source verification after publication; recheck pending. The R41 review (comment id 6909262016) accepted H1-C in principle with two narrow clarifications — B20(b) must not mandate POST success (ordinary §5.4 taxonomy applies) and 'revocation epoch noted in the audit' must name an existing field (the `delivery_attempts` admission stamp; the revocation epoch value is not pinned per attempt) — both corrected in MUSE-R42, with the pinned-value question left open to the reviewer; H1-A/B/D still provisionally addressed pending source verification after publication; recheck pending. The R42 review (comment id 6910432771) accepted the outcome taxonomy in principle and the audit-field withdrawal, with one narrow correction — the epoch-mismatch wording over-claimed: a mismatch proves only that consent changed after admission, BLOCKED records corroborate rather than prove per-attempt, and ordering comes from the test latch, not the mutable epoch — plus the answer to the pinned-value question (the admission stamp + test-controlled ordering is sufficient for the B20 fixture; no new §4.3 field in the v1.1 MVP; no durable forensic proof of exact historical revocation timing is claimed, and any such requirement needs an explicit owner decision). Both corrected in MUSE-R43. Closure basis: the R43 documents were published to `main/docs/phase1` (commit 3fbfcdda, 2026-11-16T08:05:32Z) and verified read back (blob SHAs + byte-identical public raw downloads); the reviewer's targeted read-only recheck (comment 6913317655) verified the H1-A/B/C/D corrections present in the canonical files — §7.3A(e), §7.3B(5), §8.7 E1/E2, F43 B20(b), the file-plan assignments, and the matrix — and confirmed the five canonical blob SHAs; the R44 reply (comment 6913442853) confirmed the publication from both sides. Recorded plainly: the recheck was targeted verification of the narrow corrections, not execution of fixtures and not a new exhaustive full-document signoff, and the reviewer recorded no explicit closure verdict of its own; the R44 open question (whether the targeted recheck suffices or a full re-read is still required) is superseded by the owner's decision to close H1. H1-A/B/C/D are closed at document level; the fixtures (F41 J–M, F43 B20/B21) still execute only in Phase 1.3 (Stage D).

## 5. What D-final unlocks (and does not)

- D-final approves the v1.1 specification as the buildable design. It does not authorise implementation: Phase 1.3 (on laptopgrt) needs a separate explicit owner authorisation.
- D-final does not authorise a pilot or production run: G11-pilot and G11-production are separate gates with their own evidence (§2 items 2–3).
- D-final does not execute any test: the §3 evidence items are worked in Phase 1.3 and the pilot, not by the sign-off itself.
