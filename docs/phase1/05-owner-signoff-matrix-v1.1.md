# Paylist — Owner Signoff Matrix v1.1

**Status:** PRESENTED FOR OWNER DECISION (MUSE-R36, 16 November 2026) · **Sources:** `04-final-design-v1.1.md` (R36) + `04-file-plan-v1.1.md` (R36) + GitHub Issue izzdiin/paylist#1 · **Mode:** DOCUMENTATION ONLY — this matrix records decisions and evidence; it grants no implementation, pilot, or production authorisation.

This document answers the recheck's request (comment 6907321403): present the consolidated owner-signoff matrix and evidence before requesting D-final. Nothing in it is approved until the owner says so, per item.

## 1. Decisions already made (owner-locked, for the record)

| Decision | Owner decision | Issue comment |
|---|---|---|
| P1 (pattern set + disclosure text) | ACCEPTED, subject to the specified tests and platform-policy review | 6902737515 |
| P3 (identity/attribution) | REVISED, then P3(a)–(d) CONFIRMED: explicit/manual merchant pattern registration only; uniqueness per merchant across all source accounts; cross-account repeat = duplicate evidence | 6902737515, 6903307191 |
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

## 2. Pending owner sign-offs (the matrix)

| # | Decision | What the owner signs | Evidence status | Blockers |
|---|---|---|---|---|
| 1 | P2 supersession | Mixed OTP+financial event: superseded by D8 (revised C4, §7.1–7.2 — financial record preserved, secret span redacted) | Design text complete (§7.1–7.2) | Sign-off pending as part of D-final |
| 2 | C4 approval | PrivacyGate four-class rule (§7.1, final form R8/R9/R10 as amended by R23/R24) | Design text complete | Approval against the consolidated text pending |
| 3 | C20 approval | PrivacyGate fixture set (F1–F19, §12.3) | Fixtures documented; none executed yet | Approval against the consolidated text pending; execution is a Phase 1.3 matter |
| 4 | C21 approval | Capture handoff gate (§8.3–8.5, §9 — staging, atomic admission, pre-socket machine, mode/intent table, orphaned-attempt quiescence, per-hop drain, status-query contract, attribution protocol, pre-v1.1 evidence model, negative-query safety, re-gating) | Design text complete | Approval against the consolidated text pending |
| 5 | D-final | Spec v1.1 approval (the consolidated design + file plan as the buildable specification) | Requires explicit separate approval after the evidence in §3; H1 (independent full-design consolidation/approval gate) is the remaining blocker | H1 open; items 1–4 above |
| 6 | G11-pilot | Pilot gate: the v1.1 build may run on the pilot device | Blocked on D-final + the pre-pilot evidence (§3) | D-final; F40 on the actual stack; P11 v2New; clean build on laptopgrt; per-route/endpoint contract tables |
| 7 | G11-production | Production gate: the v1.1 build may run on the production device | Blocked on G11-pilot | G11-pilot |

## 3. Evidence status (what is verified, what is not)

**Verified at document level:**
- The consolidated design (R36) and file plan (R36) are complete, mutually consistent, and committed to `main/docs/phase1` on GitHub.
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

H1 (re-issue confirmation) remains OPEN and is a D-final blocker. It closes only when the independent reviewer confirms the complete re-issued text (§7.3A/§7.3B/§7.9, patched R26–R36) at the full-design consolidation/approval gate. The R35 recheck closed the two R34 export findings at document level; it did not close H1.

## 5. What D-final unlocks (and does not)

- D-final approves the v1.1 specification as the buildable design. It does not authorise implementation: Phase 1.3 (on laptopgrt) needs a separate explicit owner authorisation.
- D-final does not authorise a pilot or production run: G11-pilot and G11-production are separate gates with their own evidence (§2, items 6–7).
- D-final does not execute any test: the §3 evidence items are worked in Phase 1.3 and the pilot, not by the sign-off itself.
