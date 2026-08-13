## What & why

<!-- One paragraph. Link the SPEC.md section this implements. -->

Spec ref: `SPEC.md §___`

---

## Definition of Done

- [ ] `.\gradlew preMergeCheck` passes locally
- [ ] Unit tests added/updated
- [ ] Previews render at `fontScale 2.0` and in RTL without overlap (BUG5)
- [ ] No new StrictMode violations in debug (BUG7)
- [ ] Verified on the **physical device**, including a force-stop → relaunch cycle
- [ ] `SPEC.md` updated if behaviour diverged from spec

## Blast radius

- [ ] **Schema touched** — new schema JSON + `Migration` + `MigrationTest` in this PR (BUG8)
- [ ] **`:core:crypto` touched** — backup→wipe→restore round-trip re-verified (BUG4)
- [ ] **Ingest touched** — cross-source dedupe test still green; both flavours build
- [ ] **Entry form touched** — draft still survives process death (BUG6)
- [ ] Screenshot diffs **reviewed**, not blindly re-recorded

## Seven Laws check (CLAUDE.md §2)

- [ ] Nothing auto-commits to `ledger_entry` outside `ApproveTransactionUseCase`
- [ ] No query mixes DEBIT and CREDIT
- [ ] No `Float`/`Double` money
- [ ] No destructive migration
- [ ] Persistent data in `filesDir`, not `cacheDir`
- [ ] No `INTERNET` permission in release
- [ ] Bug fixes have a named regression test

<!--
If you ticked a Blast Radius box, expect a slower review. That's the point.
If a change would violate a Law, don't work around it — say so in the PR and
raise an ADR instead.
-->
