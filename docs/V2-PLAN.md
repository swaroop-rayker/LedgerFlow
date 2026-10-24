# Plan — LedgerFlow v2 (for general users)

Status: **V2-1 and V2-2 merged (2026-10-22).** Written 2026-09-30 from
the owner's v2 brief; V2-1 started 2026-10-14 at the owner's word.

| Item | State |
|---|---|
| BUG-A transitions | BUG57, squashed onto `main` from PR #2 (`5421712`) |
| BUG-B ghost entries | BUG58 (schema v12, D6 confirmed), squashed onto `main` (`9bc1af0`) |
| BUG-C Home v1 | On `main` (BUG54, `2109191`, `fd5e635`) |
| BUG59 Inbox links land on Home | Squashed onto `main` (`37e225a`) |
| Perf alongside | PRs #1, #3, #4 squashed onto `main` (`48c4a04`, `5d7b262`, `52ddd82`); PERF-120HZ §3 tried and reverted |
| V2-2 Settings | Merged, PR #5 (`d40f5cd`, 2026-10-22): D7 (a), §3 below; SPEC §9.3. Device suites on `d40f5cd` (SM-S721B, 2026-10-23): `:core:database` 105/105 (migration chain v1→v12, backup round-trip, older-schema restores) and `:core:data` 402/402 (restore, nightly and manual backup, purge). CI skips its instrumented job behind the known receipt-corpus red. Its RTL check found BUG61, fixed separately in PR #6 |
| BUG61 RTL text | Merged, PR #6 (`dd25dd9`, 2026-10-24) |
| V2-3 decisions | Owner, 2026-10-24: seven PRs in order Appearance → Currency & region → Notifications (budget-alerts row + overspending) → Allowlist editor → App lock + screenshot block → Hide amounts + Data permissions → About. Lock on the platform `BiometricPrompt` (no `androidx.biometric`); preferences outside the vault under ADR-0030 |
| V2-3 · Appearance | PR #8, `claude/v2-3-appearance` (§3.2; SPEC §9.3, §9.5; ADR-0030). Not yet merged |
| V2-3 · Currency & region | PR #9, stacked on #8 (§3.3; SPEC §9.3, §16 Q26 closed; BUG62). Not yet merged |
| V2-3 · Notifications | PR #10, stacked on #9 (§3.4: budget-alerts row, overspending alerts; SPEC §9.3, §5.7). Not yet merged |
| V2-3 · Allowlist editor | PR #11, stacked on #10 (§3.4; SPEC §9.3, §5.2, §16 Q4). Not yet merged |
| V2-3 · App lock + screenshot block | Built on `claude/v2-3-app-lock`, stacked on the allowlist editor (§3.5; SPEC §7.6, §9.3; platform `BiometricPrompt`, `USE_BIOMETRIC` pinned). Not yet merged |

**Before V2-2 starts, two numbers this plan reserved are taken.** ADR-0029 is
the per-launch SQLCipher key (BUG56, PR #1), so the network ADR (D1) and the
FX ADR (D2) take the next free numbers when they are written, not 0029/0030.
ADR-0030 is now V2-3's device preferences (2026-10-24), so D1 and D2 start at 0031.
Schema v12 is BUG58's, so merchant categories (§5.5) become v13 and V2-7's
tables v14 and later.

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
| D6 | Ghost Inbox entries (BUG-B below) | §5.1 / CLAUDE.md §7: never silently drop a financial SMS | Recognised non-transactions (OTP, balance, statement, promo) are **retained and visible** under a new Inbox filter ("Not transactions"), not deleted and not in the main queue. That keeps the guarantee's substance (nothing lost) while ending the noise. **Confirmed by the owner, 2026-10-20** — built as BUG58. |
| D7 | Settings as a sixth bottom-bar slot | Material's 3–5 destinations; CLAUDE.md compactness brief | Five slots are already used (Home, Ledger, +, Analytics, More). **Recommend: Settings replaces More in the bar, and More's contents move** — tools (goals, recurring, debts, utilities) into a "Tools" section reached from Home/More-as-a-screen, or keep More and put Settings behind a gear in every tab header. Owner picks. **Decided 2026-10-21: Settings replaces More** (gear, fourth tab). Every row More had moved to Settings; Diagnostics, the one tool, is its Tools section rather than a screen of one row (V2-2). |
| D8 | Import (Excel/CSV) | Law 1: nothing reaches the ledger without a tap | Imported rows land in the Inbox as `IMPORT` candidates (the source already exists), approvable in bulk with one confirmed tap per batch. Owner confirms "bulk approve" satisfies Law 1. |

---

## 2. Bugs (fixed first in v2)

### BUG-A — tab transitions blur and overlap
**Status: fixed 2026-10-19 as SPEC §8 BUG57** — fade-through tabs, shared-axis
slide elsewhere, snapped (never `None`) when animations are off; the v2
Animations toggle plugs into `rememberNavMotion(animationsEnabled)` at V2-3.
Left as it was: the bottom bar still disappears at the start of a forward
slide rather than travelling with the outgoing screen.

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

**Built (2026-10-20) as BUG58, after D6 was confirmed** — see SPEC §5.1 and
§8. Six labels (a collect request is the sixth: the corpus had one and none of
the five fitted). Not done, and recorded as SPEC §16 Q25: ghosts that predate
schema v12 are not re-labelled, and a failed notice is labelled, not linked.
The owner's real ghost messages are still to be added as redacted fixtures.

**Related copy fix:** Home and onboarding say "Most UPI payments never send an
SMS" — the owner's banks (HDFC for all amounts, SBI above a threshold) show the
opposite, and payment apps are reported to have stopped notifying. The banner
and the default setup emphasis change to **SMS first, notifications as a
supplement**, with the allowlist seeded accordingly (smsFull); playSafe, which
has no SMS, explains its limit plainly.

### BUG-C — Home says "Nothing here yet" with entries in the vault
**Status: built 2026-10-16 as SPEC §8 BUG54 (Home v1, on `main`).** The card
list below is what shipped, except that budgets use `LfBudgetBar` (the owner
chose the bar over the ring).

**Found alongside, BUG59 (2026-10-20):** every `ledgerflow://inbox` link, which
means every Inbox notification tap, opened the Inbox for a frame and then left
the user on Home. Home's "To review" card was the only way in that worked.
Fixed with a regression test and checked on the device; see SPEC §8.

**Cause, from the code:** it is not a data bug — Home was never built.
`DashboardScreen` draws its "Nothing here yet" empty state **unconditionally**
after the two banners, whatever the vault holds. Its KDoc says the real content
(recent entries, quick stats, budget rings) would arrive "with the rollup worker
at P3"; `DashboardUiState` still says "when P3 lands, this class grows fields",
and holds only the capture-health and backup-reminder state. P3 built the rollup
worker and Analytics, and Home was never revisited. Seen on the owner's Home and
on the benchmark install (2,005 entries, several today).

**Recommended fix — Home v1, built entirely from what already exists** (no
schema change, no new query shapes):

| Order | Card | Source (existing) | Rule it must keep |
|---|---|---|---|
| — | Backup reminder, capture-health banner | unchanged | unchanged |
| 1 | **To review: N** — pending Inbox items, tap → Inbox | the shell's pending count | hidden at zero |
| 2 | **This month** — *Spent* and *Received* as **two separate figures**, each with Δ vs last month | `GetAnalyticsSnapshotUseCase` for DEBIT and for CREDIT, month window, compare-previous on | Law 2: no net, no balance, no combined total — ever |
| 3 | **Spent today** (debit) | the same snapshot, day window | debit only |
| 4 | **Budgets** — the two or three closest to their limit, as `LfBudgetRing`s, tap → Budgets | `snapshot.budgets` (`BudgetProgress`) | debit only (§5.7); hidden when no budgets |
| 5 | **Coming up** — "₹X of recurring charges due before the 30th" (A10 runway), tap → Analytics | `snapshot.runway` | hidden when nothing is due |
| 6 | **Captured automatically** — "72% of this month's spending arrived by itself" (by value; count on the second line), tap → Analytics capture coverage | `CaptureCoverage` (DATAVIZ-PLAN C1, shipped at P3) | debit only; hidden when the month has no spending |
| 7 | **Recent spending** — the last five debit entries, same row as the Ledger, "See all" → Ledger (Expenses) | the Ledger's DEBIT paged query, limit 5 | one book per list; no mixed list |
| 8 | **Recent income** — the last five credit entries, its own card, "See all" → Ledger (Income) | the Ledger's CREDIT paged query, limit 5 | a separate list, never interleaved with spending; hidden when the book is empty |
| — | "Nothing here yet" | — | **only** when both books hold no live entries — a real condition, tested |

Design and performance rules for it:
- Compactness brief: one card shape for the whole screen, figures first, no
  decorative charts — the ring is the only graphic, and it is small.
- Home is the start screen, so it pays against §11's cold-start budget: the
  first frame shows the banners and fixed-height placeholders; the figures load
  after first frame from `daily_rollup` (cheap by design), sized so nothing
  jumps when they arrive. `reportFullyDrawn()` fires when they do
  (`docs/PERF-120HZ-PLAN.md` §6), so startup is measured to a real Home.
- Font scale 2.0 and RTL: the two monthly figures stack rather than clip (BUG9).
- The two figures are labelled in words ("Spent", "Received") and announced as
  such, never distinguished by colour alone (§9.6).

Tests: `BugNN_HomeSummarisesWhenEntriesExist` (a vault with entries never shows
the empty state; an empty vault always does); `DashboardViewModel` unit tests
per card, including each card's hidden case; a guard that no Home figure is
computed from both ledgers (alongside `LedgerIsolationTest`); goldens at 1×
and 2×, reviewed; the startup benchmark re-run against the new Home.

Decided by the owner (2026-09-30): capture coverage gets a Home slot (card 6,
closing DATAVIZ-PLAN §7.3's open question), and Recent offers the Income book as
a second, separate list (card 8).

---

## 3. Navigation and Settings

A **Settings** destination (placement per D7) absorbs today's scattered rows —
Back up now, Export, Notification capture, Receipts — and adds the sections
below. Every settings page follows the compactness brief: rows, not cards of
pills; one shape per screen.

**V2-2, merged 2026-10-22 (moves only; PR #5).** Settings is the fourth tab and holds
all of More's rows under Capture, Organise, Data and Tools (SPEC §9.3 has the
order). The sections below are absent until their phase builds them; each
then joins as a heading of its own, or grows an existing one (§3.4 takes over
Capture, §3.6 takes over Data).

### 3.1 Account (per D3)
Local profile (name, photo — stored in the vault, backed up with it), linked
Google Drive account and sign-out, "Erase this install" (purge-style warning,
names what is lost, tells the user to back up first — never pretends to).

### 3.2 Appearance
Theme: System / Light / Dark (the app already follows the system; this adds
the override — **palette unchanged**). Animations: Full / Reduced / Off.
Start screen: Home / Ledger / Analytics.

**Built (V2-3, 2026-10-24).** Three rows on the Settings tab under an
Appearance heading, between Data and Tools; SPEC §9.3 and §9.5 have the
detail. Full is "as the phone allows" (no separate System option), Off scales
every Compose animation to zero, and the start screen applies from the next
launch. Stored outside the vault under ADR-0030.

### 3.3 Currency & region (per D4)
Base currency (read-only once data exists), currency symbol style, number
grouping (Indian lakh vs western), date format, country/region (drives
defaults for SMS allowlist seeds and number format).

**Built (V2-3, 2026-10-24): base currency (read-only), number grouping and
date order.** Defaults reproduce the old behaviour exactly; SPEC §9.3 has the
detail. Amounts now display and parse in English in the phone's region (§16
Q26), which found BUG62. Not built: symbol style, and region-driven
allowlist seeds (owner: later).

### 3.4 Notifications
Budget alerts (exists — moves here), bill reminders, recurring-transaction
reminders, overspending alerts (spend rate projected past a budget before month
end), capture notifications (exists). Each a switch plus timing where it has
one. All local (WorkManager), no push service.

**Built (V2-3, 2026-10-24): budget-alerts row and overspending alerts.** The
"budget alerts (exists — moves here)" above was mistaken: there was no in-app
switch to move, only Android's channel, which the row now reports and opens.
Overspending warns once per period from day 7 (day 2 weekly) on the Budget
alerts channel; SPEC §9.3 has the rule. Capture's heading is now
Notifications. Bill and recurring reminders wait for V2-7.

**Notification allowlist editor (deferred here by the owner, 2026-10-02).** v1
ships a built-in list of payment and banking apps and no way to change it, and
the store listings say exactly that. SPEC §3.1 has long said a user could add a
messaging app "once P5 ships the Settings editor"; P5 did not ship it. The
editor belongs in this section, and §3.1's consequences (a bank SMS captured
twice; reading every SMS notification, personal ones included) are its design
brief, not afterthoughts.

**Built (V2-3, 2026-10-24)** as Settings > Notifications > Payment apps: switch
built-ins off and on, add from installed apps, remove added ones, and a
warning naming §3.1's consequences before a messaging, SMS or email app is
added. SPEC §9.3 has the detail.

### 3.5 Security & privacy
**App lock** (§7.6, specified since v1, not yet built): `BiometricPrompt` with
device-credential fallback, gating the **UI only — never the DEK** (CLAUDE.md
§7). Lock on launch / after N minutes in background. Privacy: hide amounts on
Home (tap to reveal), block screenshots in-app (`FLAG_SECURE`) as an option,
the phrase-screen protections already in place. Data permissions: a page
listing each permission the app holds, why, and a deep link to revoke it.

**Built (V2-3, 2026-10-24): app lock and screenshot block.** Platform prompt
only (no `androidx.biometric`), on cold start and after Lock after
(Immediately / 1 / 5 / 15 minutes); a screen overlay that touches no key.
SPEC §7.6 has the detail. Hide amounts and the data-permissions page are the
next PR.

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

**Public contact email (owner to decide, probably in v2; 2026-10-02).** Both
store listings and `docs/play/privacy-policy.md` need one, and all three carry
a placeholder until then. Whatever is chosen is shown publicly.

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

**Decided by the owner (2026-10-01), closing this section's open question.**
Merchant categories are a **separate taxonomy from spending categories**:
a merchant category says what kind of place a merchant *is* ("Groceries",
"Pharmacy", "Fuel"); a spending category says what the *money* was for, per
entry. Neither replaces the other, and the existing "default category for a
merchant" (which pre-fills an entry's *spending* category) is unchanged.

### 5.1 The taxonomy

A flat list — **no subcategories** — managed with the same UI and interactions
as Categories (`TaxonomyCard`, rename, colour, hide, delete). Deleting a
merchant category asks where its merchants go: another merchant category, or
**Uncategorised**. A merged merchant keeps the survivor's category (the merge
already moves aliases, BUG28); a hidden merchant keeps its category.

### 5.2 Categorising is manual, and happens in one place

**Categorisation is done by the user, in the Merchants section — nowhere else.**
Nothing infers a merchant's category: not the parser, not OCR, not the entry
form.

- **Capture and review do not categorise.** SMS, notification and receipt
  extraction fill the *merchant* and stop there (as today); a new merchant —
  captured or typed — is created **Uncategorised**. The review screen and the
  entry form never ask for a merchant category and never show a prompt to
  choose one; adding a step there would slow the approval the app exists to
  make fast.
- **The Merchants section is where it happens.** Each merchant row shows its
  category and offers "Set category"; an **Uncategorised (n)** filter at the top
  of the section lists exactly the merchants still to do, so the work can be
  cleared in one sitting; multi-select assigns one category to several at once
  (same selection pattern as the Inbox and the bin).
- **Seed list:** a short default set the user can rename, hide or extend —
  Groceries, Food delivery, Restaurants & cafés, Fuel, Pharmacy & health,
  Utilities & bills, Shopping, Travel & transport, Entertainment, Education,
  Services, Government & tax. No merchant is pre-assigned to any of them.

### 5.3 Where merchant categories are used

- **Manual entry (and review) — browsing, not categorising.** The merchant
  picker keeps search as its first control, and adds **browse by merchant
  category**: the merchant list grouped under category headers (Uncategorised
  last), so a user who cannot remember a name can find it by kind. Picking a
  merchant is all that happens; the picker never edits a merchant's category.
- **Analytics** — §5.4.
- **Filters** — "Merchant category" joins the Analytics filter sheet (A9), and
  the Ledger list filters, as one more dimension.

### 5.4 Merchant-category spending analytics (new; `docs/DATAVIZ-PLAN.md` A11)

A **"By merchant category"** section on Analytics — where the money went, by
kind of place, beside "By category" (what it was for).

- **Graphic and list:** donut plus a ranked list with share and Δ against the
  previous period, exactly A2's shape; treemap toggle as A3. The list is the
  content; the donut orients (CLAUDE.md §5 charts rules).
- **Drill-down:** merchant category → its merchants (ranked, A4's
  `LfHorizontalBarChart`) → a merchant's entries (Paging over base tables).
- **Uncategorised is a bucket, not a hole:** shown last, with how many
  merchants it holds and a "Categorise in Merchants" action that opens the
  Merchants section on its Uncategorised filter. Without it the section's total
  would silently disagree with the page total.
- **One book at a time** (Law 2): it follows the Analytics book tab; no figure
  combines debit and credit. Merchants mostly appear on the debit side; a
  credit book with no categorised merchants simply shows the Uncategorised
  bucket.
- **Optional lens on A1:** "Spend over time" stacked by merchant category as an
  alternative to stacking by spending category — a toggle, not a new chart.
- **Export:** the XLSX gains a "Spending by merchant category" pivot in
  ADR-0004's shape (one book, category × month, totals within the book), and
  the raw `merchant_category` table rides along automatically (it is in the
  backup payload, so `ExportCoversEveryTableTest` covers it).

**Data — no new rollup dimension.** `daily_rollup` already sums per
`merchant_id`; the section groups those rows through
`merchant.merchant_category_id` at query time (`JOIN merchant`, `LEFT JOIN
merchant_category`, a literal `'DEBIT'`/`'CREDIT'` bound per CLAUDE.md's rule and
`LedgerIsolationTest`). This is deliberate:

- **Retroactive by design.** A merchant category describes the merchant as it
  is now, so re-categorising a merchant moves *all* its history — the user is
  correcting a label, not recording a change in their spending. (Spending
  categories are the opposite: per entry, fixed when approved.) A rollup column
  would freeze the old label and need a recompute on every change; the join
  cannot be stale.
- **`daily_rollup` is not widened** (CLAUDE.md, charts rules) — merchant
  category is a property of the merchant, not of the money.
- **Performance:** one extra join on a small table; re-measure the 5Y query
  (< 300 ms, SPEC §11) on LF Bench, whose seed gains merchant categories.

### 5.5 Schema and tests

Schema **v13** (v12 went to BUG58): `merchant_category` (id, name, color_argb, sort_order,
created_at, updated_at, deleted_at — the same shape and soft-delete rule as
`category`, flat) and nullable `merchant.merchant_category_id`. Migration by
`CREATE new / INSERT SELECT / DROP / RENAME`, with `MigrationTest`, the
committed `13.json`, and `BackupPayload` fields defaulting to what the migration
writes (`NULL` for every existing merchant — all start Uncategorised).
`OlderSchemaBackupRestoresTest` gains a v12 payload.

Tests: categorisation only ever changes from the Merchants section (a guard
that ingest, review and entry never write `merchant_category_id`); delete with
reassignment and to Uncategorised; merge keeps the survivor's category;
re-categorising moves a merchant's history in the section (the retroactive
rule, pinned); the Uncategorised bucket makes the section sum to the page
total; Law 2 guard on the new query; picker browse groups and Uncategorised
last; goldens at 1x and 2x for the section, the picker's browse mode and the
Merchants section's Uncategorised filter; semantics per donut segment.

**Not planned:** budgets per merchant category, and any automatic suggestion of
a merchant's category. Either would be its own owner decision.

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
| V2-0 | Owner decisions D1–D8; ADRs for the network (D1), FX (D2) and D5, at the next free numbers | owner; D6 settled 2026-10-20 |
| V2-1 | BUG-A transitions, BUG-B ghost entries + copy, BUG-C Home v1 | D6; ghost-message evidence; Home card list |
| V2-2 | Settings destination and restructure (moves only, no new features) | D7 |
| V2-3 | Appearance, Currency & region, Notifications, App lock, About | D4 |
| V2-4 | Data & sync: Drive, Import (CSV/XLSX), Restore entry | D1, D8; P5 XLSX |
| V2-5 | Receipts management | D5 |
| V2-6 | Merchant categories (schema v13): taxonomy, manual categorisation in Merchants, browse-by-category picker, merchant-category analytics (§5) | decided 2026-10-01; owner to confirm §5.2's seed list |
| V2-7 | More: recurring, goals, debts, utilities (schema v14+) | D2 for converter |
| V2-8 | Recommended additions the owner picks; Play listing for v2 | owner |

`docs/PERF-120HZ-PLAN.md` runs alongside from V2-1 (its transition work is
BUG-A's measurement).

## 8. What v2 does not change

The two-factor key scheme and phrase derivation (§7.2), the two isolated
ledgers (Law 2), approval before anything reaches the ledger (Law 1), money as
`Long` (Law 3), non-destructive migrations (Law 4), on-device OCR and parsing,
and the palette.
