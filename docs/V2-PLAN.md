# Plan — LedgerFlow v2 (for general users)

Status: **proposed, not started.** Written 2026-09-30 from the owner's v2 brief.
v2 begins **after P5 is complete** (steps 2–6: XLSX export, diagnostics,
accessibility pass and goldens, `targetSdk` 37, Play release track), and only
when the owner says so. Nothing in this document has been implemented.

v1 was built for one user and one phone. v2 is for general users, adds
opt-in network features, and **keeps the privacy promise**: no LedgerFlow
server, no telemetry, no user data sent anywhere except where the user sends
it themselves (their own Google Drive, encrypted).

---

## 1. Decisions this brief collides with — owner decides before any code

CLAUDE.md §0 and §10 require these to be flagged, not worked around. Each needs
an owner decision and, where marked, a superseding ADR **before** its phase
starts.

| # | Request | Collides with | Recommendation |
|---|---|---|---|
| D1 | Google Drive backup/sync; "online" data | Law 6 (nothing computed touches a network) | **ADR-0029: opt-in network, own-account only.** Drive holds the same `.lfbk` files the SAF folder does — already encrypted to the phrase-derived key (ADR-0027) before they leave the phone. Google sees ciphertext and file names; LedgerFlow runs no server. Uses the Drive REST API through the Storage Access Framework picker if possible (no Google SDK); the official Drive/Identity SDKs only if SAF cannot hold a Drive folder reliably — a dependency the owner approves separately. |
| D2 | Currency converter with real-time rates | Locked decision: *rates are user-entered, never fetched* | **Split.** The ledger rule stays: an entry's `fx_rate_micro` is what the user enters. A **utility** converter may fetch a public rate table (e.g. ECB reference rates), sending nothing but the request, cached, clearly labelled "indicative". Optionally it may *suggest* a rate the user confirms on the entry form. Needs ADR-0030 superseding the §0 line. |
| D3 | Account: profile, email, phone, sign-in/out, delete account, account type | Law 6; privacy; there is no backend | **No LedgerFlow accounts.** "Account" becomes a **local profile** (name, photo) stored in the vault, plus the Google account linked for Drive (D1), shown as "Signed in to Google Drive as …" with sign-out. "Delete account" = erase this install's data (with the purge-style `Warning` confirmation) and unlink Drive. Email/phone are not collected: nothing would use them, and a field that stores PII for no purpose is a liability. |
| D4 | Change default currency after onboarding | Locked decision: one base currency per install; `amount_minor` is always base | Base currency stays fixed once data exists (changing it would silently relabel every amount). Settings shows it read-only, with symbol, number format (lakh/western grouping) and date format as the adjustable parts. A future "start a new book in another currency" is a separate design. |
| D5 | Receipts auto-delete after a period | ADR-0023 declined a timed purge | Owner reverses ADR-0023's decision explicitly (superseding ADR), or v2 ships "select and delete" + a reminder instead of silent auto-deletion. Recommendation: allow auto-delete **only of receipts already backed up** to a verified backup, never of the only copy. |
| D6 | Ghost Inbox entries (BUG-B below) | §5.1 / CLAUDE.md §7: never silently drop a financial SMS | Recognised non-transactions (OTP, balance, statement, promo) are **retained and visible** under a new Inbox filter ("Not transactions"), not deleted and not in the main queue. That keeps the guarantee's substance (nothing lost) while ending the noise. Owner confirms. |
| D7 | Settings as a sixth bottom-bar slot | Material's 3–5 destinations; CLAUDE.md compactness brief | Five slots are already used (Home, Ledger, +, Analytics, More). **Recommend: Settings replaces More in the bar, and More's contents move** — tools (goals, recurring, debts, utilities) into a "Tools" section reached from Home/More-as-a-screen, or keep More and put Settings behind a gear in every tab header. Owner picks. |
| D8 | Import (Excel/CSV) | Law 1: nothing reaches the ledger without a tap | Imported rows land in the Inbox as `IMPORT` candidates (the source already exists), approvable in bulk with one confirmed tap per batch. Owner confirms "bulk approve" satisfies Law 1. |

---

## 2. Bugs (fixed first in v2)

### BUG-A — tab transitions blur and overlap
**Cause, from the code:** `LedgerFlowNavHost` sets no transitions, so
Navigation Compose applies its default **700 ms cross-fade**: for most of a
second both screens are drawn half-transparent over each other — the fuzzy,
overlapping text reported. Heavy screens (Analytics) also compose inside that
animation.

**Fix:** explicit transitions — bottom-tab switches as a short fade-through
(~150–200 ms: old fades out, then new fades in, never both at full size) or
instant, with `launchSingleTop`/`restoreState` so a tab keeps its scroll;
forward navigation (entry form, review, settings pages) as a short shared-axis
slide. Honour the system "remove animations" setting and v2's own Animations
toggle. **Test:** a tab-switch macrobenchmark (late frames, `docs/PERF-120HZ-PLAN.md`
§1.5) plus a Roborazzi check that no frame mid-transition shows two screens'
text at once. Regression test `BugNN_TabSwitchDoesNotOverlapScreens`.

### BUG-B — "ghost" Inbox entries (no payee, no amount)
**Cause, from the code:** any message from an allowlisted SMS sender or
notification package that matches no parser rule becomes a `PENDING` row with
`confidence = 0` (§5.1's never-drop rule). Promotional SMS headers (`-P`) are
already excluded, but **OTPs, balance alerts, statement/bill reminders, and
payment apps' own promotional notifications** come from allowlisted senders,
match no rule, and arrive as empty candidates. There is no non-transaction
classifier anywhere in the pipeline.

**Diagnosis still needed (owner):** which messages the owner's ghosts are.
The bodies are private; the owner either shows them, or authorises reading the
ghost rows' raw text for classification and adding **redacted** copies to the
golden corpus (CLAUDE.md §11: a real message that fails becomes a fixture).

**Fix (after D6):** a non-transaction classifier in the shared rule engine
(source-agnostic — both SMS and notifications): OTP/verification codes,
balance/available-limit notices without a debit/credit verb, statements and
due-date reminders, offers/cashback promos, failed/declined/reversed notices
(the last one linked, not dropped). Classified rows go to the "Not
transactions" filter, never notify, and can be moved back with one tap. Also
checked: whether dedupe or the parser mis-handled a real transaction into an
empty row (false negative), using the fixtures.

**Related copy fix:** Home and onboarding say "Most UPI payments never send an
SMS" — the owner's banks (HDFC for all amounts, SBI above a threshold) show the
opposite, and payment apps are reported to have stopped notifying. The banner
and the default setup emphasis change to **SMS first, notifications as a
supplement**, with the allowlist seeded accordingly (smsFull); playSafe, which
has no SMS, explains its limit plainly.

### BUG-C — Home says "Nothing here yet" with entries in the vault
Seen on the benchmark install (2,005 entries, several today) and on the
owner's own Home. Not investigated; v2 finds out whether Home's summary reads a
window, a rollup or a condition that is wrong. Named test once understood.

---

## 3. Navigation and Settings

A **Settings** destination (placement per D7) absorbs today's scattered rows —
Back up now, Export, Notification capture, Receipts — and adds the sections
below. Every settings page follows the compactness brief: rows, not cards of
pills; one shape per screen.

### 3.1 Account (per D3)
Local profile (name, photo — stored in the vault, backed up with it), linked
Google Drive account and sign-out, "Erase this install" (purge-style warning,
names what is lost, tells the user to back up first — never pretends to).

### 3.2 Appearance
Theme: System / Light / Dark (the app already follows the system; this adds
the override — **palette unchanged**). Animations: Full / Reduced / Off.
Start screen: Home / Ledger / Analytics.

### 3.3 Currency & region (per D4)
Base currency (read-only once data exists), currency symbol style, number
grouping (Indian lakh vs western), date format, country/region (drives
defaults for SMS allowlist seeds and number format).

### 3.4 Notifications
Budget alerts (exists — moves here), bill reminders, recurring-transaction
reminders, overspending alerts (spend rate projected past a budget before month
end), capture notifications (exists). Each a switch plus timing where it has
one. All local (WorkManager), no push service.

### 3.5 Security & privacy
**App lock** (§7.6, specified since v1, not yet built): `BiometricPrompt` with
device-credential fallback, gating the **UI only — never the DEK** (CLAUDE.md
§7). Lock on launch / after N minutes in background. Privacy: hide amounts on
Home (tap to reveal), block screenshots in-app (`FLAG_SECURE`) as an option,
the phrase-screen protections already in place. Data permissions: a page
listing each permission the app holds, why, and a deep link to revoke it.

### 3.6 Data & sync
Backups (today's manual + nightly + Recovery Kit, moved here), Restore (exists
at first run — also reachable here with the §7.3 type-DELETE confirmation
still owed), **Google Drive** (per D1: destination for the same encrypted
files, sync status, last upload), Export (CSV exists; XLSX from P5) and
**Import** (per D8: CSV and XLSX into the Inbox).

### 3.7 Receipts (per D5)
Browse receipts by period (Today / Week / Month / all), multi-select delete,
delete all (warning with count and size), storage location and size, and
auto-delete as decided in D5.

### 3.8 About
Version and build, What's new (from Conventional Commit release notes), Help
and FAQ (bundled, offline), Report a bug (opens the user's email/GitHub with
device info **they review before sending** — no telemetry), Privacy policy,
Terms, open-source Licenses (generated from the dependency graph at build
time — dependency choice to approve).

---

## 4. More — new tools

All money is `Long` minor units; rates (interest, FX) are stored as integer
basis points or micro-units, never `Float`/`Double` (Law 3). Every new table
ships a `Migration` + `MigrationTest` + schema JSON + `BackupPayload` fields
(CLAUDE.md Room rules). None of these writes to `ledger_entry` except through
`ApproveTransactionUseCase` (Law 1).

### 4.1 Savings goals
Goal name, target amount, target date, progress (manual contributions, or
linked to a category/credit source), projected completion at the current rate.
Goals are **not** a third ledger: contributions are notes against the plan,
and nothing nets income against spending (Law 2).

### 4.2 Recurring transactions
Explicit recurring items (rent, subscriptions, EMIs, taxes, bills): amount,
cadence (monthly / quarterly / yearly / custom), next renewal date, reminder.
Seeded from the existing **recurring detection** (Analytics runway) as
suggestions the user confirms. A due item creates an **Inbox candidate**, never
a ledger entry (Law 1).

### 4.3 Debts & loans
Loan: principal, interest rate (bps), tenure, start date, EMI (computed with a
stated rounding rule, unit-tested against bank amortisation examples),
schedule, outstanding balance, interest paid to date. EMI payments matched to
debit entries as they are approved. Money lent to others tracked the same way
(owed to me / I owe).

### 4.4 Utilities
Calculator (with "use as amount" into the entry form), currency converter (per
D2), split expense (equal / by share / by amount; creates one debit for the
user's share and a record of who owes what — **not** a netted figure), bill
reminder (shares §3.4's reminders).

---

## 5. Merchant categories

A flat taxonomy for merchants (e.g. "Food delivery", "Groceries", "Fuel"),
**no subcategories**, managed with the same UI and interactions as Categories
(`TaxonomyCard`, rename, colour, delete with reassignment), and shown wherever
a merchant is picked — the entry form, review, filters, Analytics merchant
views. Schema: `merchant_category` table + nullable `merchant.merchant_category_id`
(v12 migration, `CREATE/INSERT SELECT/DROP/RENAME`), backup payload fields with
migration-equal defaults. **Question for the owner:** is this separate from the
existing "default category for a merchant" (which files its entries under a
*spending* category)? The plan assumes yes — merchant categories describe the
merchant, spending categories describe the money — and changes nothing else.

---

## 6. Recommended additions (owner picks)

1. **Transfers** (SPEC §16 Q22): mark a candidate or entry as a transfer
   between own accounts/wallets — excluded from spending without being
   discarded. Removes the manual "discard the top-up" step.
2. **Refund linking**: a credit linked to the debit it reverses, shown together
   without netting the books.
3. **Accounts/instruments with balances** (bank, card, cash, wallet) — per-book
   views, never a combined "net worth" that mixes ledgers unless the owner
   defines one explicitly.
4. **Search** across the ledger (merchant, note, amount range).
5. **Tags** alongside categories.
6. **Monthly statement PDF** (local, shareable by the user).
7. **Home-screen widget** (today's spend, budget left) — Glance is a new
   dependency; propose separately.
8. **Hindi (and other) UI localisation** — the OCR already reads Devanagari.
9. **Onboarding for general users**: the 24 words stay mandatory (locked), but
   the flow leads with "save the Recovery Kit PDF" (QR + words) and explains
   Drive backup (D1) as the off-phone copy.
10. **Local crash log export** instead of any crash reporting service.

---

## 7. Phases (each ends green on `preMergeCheck`, both flavours, device-verified)

| Phase | Scope | Needs first |
|---|---|---|
| V2-0 | Owner decisions D1–D8; ADR-0029 (network), ADR-0030 (FX), ADR for D5 | owner |
| V2-1 | BUG-A transitions, BUG-B ghost entries + copy, BUG-C Home | D6; ghost-message evidence |
| V2-2 | Settings destination and restructure (moves only, no new features) | D7 |
| V2-3 | Appearance, Currency & region, Notifications, App lock, About | D4 |
| V2-4 | Data & sync: Drive, Import (CSV/XLSX), Restore entry | D1, D8; P5 XLSX |
| V2-5 | Receipts management | D5 |
| V2-6 | Merchant categories (schema v12) | §5 question |
| V2-7 | More: recurring, goals, debts, utilities (schema v13+) | D2 for converter |
| V2-8 | Recommended additions the owner picks; Play listing for v2 | owner |

`docs/PERF-120HZ-PLAN.md` runs alongside from V2-1 (its transition work is
BUG-A's measurement).

## 8. What v2 does not change

The two-factor key scheme and phrase derivation (§7.2), the two isolated
ledgers (Law 2), approval before anything reaches the ledger (Law 1), money as
`Long` (Law 3), non-destructive migrations (Law 4), on-device OCR and parsing,
and the palette.
