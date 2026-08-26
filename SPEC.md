# LedgerFlow — Engineering Specification

**Version:** 0.5.0
**Status:** Stack **LOCKED** — Native Kotlin + Jetpack Compose. Phase 0 complete; P1 in progress.
**Platform:** Android-only, native.
**Owner:** Swar
**Dev environment:** Windows 11 + Android Studio, on-device testing (Android 16 / API 36, forward to Android 17 / API 37).

---

## 1. Product Definition

### 1.1 One-liner
A production-grade, offline-first, encrypted personal ledger for Android that ingests expenses from **live SMS**, **OCR'd receipts**, and **manual entry** — and never writes a single rupee to the ledger without explicit human approval.

### 1.2 Core Principles (non-negotiable)

| # | Principle | Enforcement |
|---|---|---|
| **P1** | **Human-in-the-loop.** No automated source ever commits to the ledger. Everything lands in an Inbox as `PENDING`. | Schema-level: `ledger_entry` rows can only be created via `ApproveTransactionUseCase`. Enforced by a lint rule + instrumentation test. |
| **P2** | **Ledger isolation.** DEBIT and CREDIT are two disjoint ledgers. They never net, sum, or offset each other. | Every DAO query carries a mandatory `ledger` param. A test asserts zero queries return mixed-ledger rows. |
| **P3** | **Offline-first, zero-cloud.** No network permission in the base build. All OCR/parsing is on-device. | `AndroidManifest` declares no `INTERNET` permission in `release`. |
| **P4** | **Data is sacred.** Losing user data is a P0 incident, not a bug. Undecryptable data == lost data. | Multi-wrapped encryption key + mandatory 24-word recovery phrase (§7). |
| **P5** | **Money is integers.** All amounts are `Long` minor units (paise/cents). Floating-point money is a build failure. | Custom lint rule bans `Float`/`Double` in `:core:model`. |
| **P6** | **Every migration is reversible-by-backup.** No destructive migration ships. Ever. | `fallbackToDestructiveMigration()` is banned in release source sets. |

### 1.3 Out of scope (v1)
- iOS / web / desktop.
- Cloud sync, multi-device, accounts, auth servers.
- Bank API / account aggregator integration.
- Investments, net worth, loans, EMI amortization.
- Shared/family ledgers, splitting.
- Automatic FX conversion / live rate lookup. Foreign-currency spend is captured manually (§5.8).
- Changing the base currency after onboarding (would require re-stating all history).

---

## 2. Stack Decision — **LOCKED: Native Kotlin + Jetpack Compose**

### 2.1 The candidates

**Option A — Native Kotlin + Jetpack Compose (Material 3)**
**Option B — Flutter (Dart) + a Kotlin platform-channel layer**

### 2.2 Honest comparison against *this* product's requirements

| Requirement | Native Compose | Flutter |
|---|---|---|
| Live SMS interception (`SMS_RECEIVED_ACTION` BroadcastReceiver, process-death-resilient) | First-class. Receiver runs in a Kotlin process with zero bridge. | Requires a native Kotlin plugin anyway. Background isolate spin-up adds latency and is a known source of dropped events when the app is killed. **You write the native code regardless.** |
| SQLCipher + Room encryption | `net.zetetic:sqlcipher-android` + Room is the reference path; huge ecosystem. | `sqflite_sqlcipher` — smaller maintainer base, slower to track SQLCipher/AndroidX releases. Room's compile-time schema validation and `MigrationTestHelper` have no Flutter equal. |
| On-device OCR (ML Kit Text Recognition v2) | Google's own SDK, day-zero. | Wrapper package with version lag; new ML Kit features arrive late. |
| Android 16 → 17 forward compat (edge-to-edge enforcement, predictive back, new permission models) | Available the day the SDK drops. | Historically 1–2 quarters behind on new API-level behaviour changes. This is your **biggest** long-term risk given you're explicitly targeting API 37. |
| Deep-linked notification actions → in-app review screen | Native intents/`PendingIntent`, trivial. | Works, but routing through the engine adds cold-start cost on notification tap. |
| 60fps locked | Compose + Baseline Profiles + R8 full mode hits it. Rendering is the platform's own. | Also achievable; Impeller is solid now. Slight edge to Flutter on raw animation consistency, slight edge to Native on cold start and memory. **Effectively a wash.** |
| UI/UX quality ceiling (Toshl-like polish) | Material 3 Expressive, shared-element transitions, motion APIs. Excellent. | Excellent, arguably faster to iterate visually (hot reload beats Compose Preview + install cycles). **Genuine Flutter win.** |
| APK size | ~8–14 MB | ~18–25 MB (engine baseline) |
| Cross-platform optionality later | None without a rewrite. | Real — but you scoped this **Android-only-native**, so this benefit is currently worth zero. |
| Talent/AI-agent support for recursive feature work | Enormous Kotlin/Compose corpus; Claude Code generates high-quality Compose + Room + Hilt. | Good, but less deep on Android-platform-specific edge cases (which is 40% of this app). |

### 2.3 Recommendation — **Option A, Native Kotlin + Compose. High confidence.**

The deciding argument isn't UI. It's that **~40% of LedgerFlow is platform plumbing**: SMS broadcast receivers, notification channels with actions, `WorkManager`, Keystore-backed crypto, SAF file export, CameraX, `PdfRenderer`, Auto Backup semantics, and forward compat with two unreleased API levels. In Flutter you write all of that in Kotlin anyway — you just pay an extra serialization boundary, a second dependency tree, and a permanent lag behind new Android behaviour changes.

Flutter's single real advantage here (faster UI iteration via hot reload) does not outweigh being a second-class citizen on the only platform you're shipping to.

**Choose Flutter only if** you expect to ship iOS within 12 months. You've said you won't.

### 2.4 Sign-off

```
DECISION: Native Kotlin + Jetpack Compose (Option A)
DATE:     2026-08-13
STATUS:   LOCKED
```

Revisiting this decision requires a new ADR superseding ADR-0001 and a full rewrite of §4, §6, and §11.

### 2.5 Decision Log

| # | Question | Resolution | Section |
|---|---|---|---|
| D-01 | Flutter vs Native Compose | **Native Kotlin + Compose** | §2 |
| D-02 | Single-currency vs multi-currency | **Single base currency, currency-aware schema, manual FX capture. No conversion engine.** | §5.8 |
| D-03 | Recovery: passphrase vs 24-word vs both | **24-word phrase mandatory (primary + backup key).** The "passphrase optional, device-local" half is superseded by **D-05** — it is dropped, not merely optional. | §7.2–7.4 |
| D-04 | Play-safe flavour: P1 or P5 | **Neither. Notification ingest is promoted to a co-equal first-class source, shipped in P2 alongside SMS, present in *both* flavours.** | §3.1, §5.2 |
| D-05 | KEK-C (optional passphrase wrap): implement at P1 or drop | **Dropped. KEK-A + KEK-B permanently; no Argon2id dependency. Extension point stays reserved.** ADR-0011. | §7.2 |
| D-06 | `draft_entry`: singleton draft vs one row per in-flight entry | **One row per in-flight entry, keyed by a client-generated UUIDv7.** A singleton silently destroys work, which is BUG6 wearing a different hat. **Its uniqueness half — one new-entry draft per ledger — is superseded by ADR-0013:** in use, starting a second entry resumed the first rather than creating a second, so a second in-flight entry could not exist. A ledger now holds as many as the user starts, shown as a horizontal peek shelf. | §6.1, §6.1.2 |
| D-07 | Recovery Kit: plaintext, password-protected PDF, or plaintext + confirmation | **Plaintext, gated behind an explicit "this file is your master key" confirmation.** A PDF password reintroduces a weak user-chosen secret into the recovery path. | §7.2 |
| D-08 | `app_meta.canary`: keep or drop | **Keep.** It cannot detect a wrong key (SQLCipher's HMAC does that first) but it is the only check that catches a DEK/database *mismatch* after restore or a partially-applied rotation. | §7.3 |

---

## 3. Target Configuration

| Setting | Value | Rationale |
|---|---|---|
| `minSdk` | **26** (Android 8.0) | Notification channels, `java.time` desugaring, Keystore AES-GCM maturity. Covers ~97% of devices. |
| `compileSdk` | **37** (Android 17) | Forced, not chosen: AndroidX (Compose BOM 2026.08.00, lifecycle 2.11.0) is built against 37 and AGP refuses to compile against an older platform. |
| `targetSdk` | **36** (Android 16), bump to 37 at P5 | Compiling against newer APIs is independent of opting in to new runtime behaviour. The `targetSdk` bump carries edge-to-edge enforcement and new foreground-service rules, so it gets its own testing pass rather than riding along with a dependency upgrade. |
| Language | Kotlin (latest stable), JVM target 17, core library desugaring **on** | |
| Build | Gradle Kotlin DSL + Version Catalog (`libs.versions.toml`) + Convention Plugins | Reproducible, no version drift. |
| ABI | `arm64-v8a` primary, `armeabi-v7a` fallback, App Bundle splits | |

**Every dependency version lives in `gradle/libs.versions.toml`. Zero hardcoded versions in module build files. No dynamic versions (`+`, `latest.release`) — build reproducibility is a hard requirement.**

### 3.1 Distribution strategy & ingest sources — **D-04**

`RECEIVE_SMS` / `READ_SMS` are **Play-restricted permissions**. Google Play grants them only to apps whose *core* function is being the default SMS handler, or via an approved Permissions Declaration exception. Finance-tracking justifications are frequently rejected.

**The resolution is not "build a degraded fallback later." Notification ingest is promoted to a co-equal primary source.**

Rationale: in the current Indian payments landscape, a `NotificationListenerService` captures **strictly more** than SMS does. UPI apps (GPay, PhonePe, Paytm), card apps, and bank apps all post transaction notifications; many banks have moved to notification-only for small-value UPI. SMS misses those entirely. Notification ingest is not a compliance workaround — it is the higher-recall source. SMS is the source that survives when notifications are muted or the payer app is uninstalled. **Run both, dedupe across them.**

| | Source A: SMS | Source B: Notifications |
|---|---|---|
| Permission | `RECEIVE_SMS` (Play-restricted) | `BIND_NOTIFICATION_LISTENER_SERVICE` (user-granted via Settings, **not** Play-restricted) |
| Coverage | Bank SMS alerts | UPI apps, bank apps, card apps, wallet apps |
| Flavour | `smsFull` only | **both** flavours |
| Delivery | sideload / internal testing | Play-eligible |

**Product flavours** (`productFlavors { smsFull; playSafe }`):
- `smsFull` — Source A + Source B. Sideload / internal-testing track. Full feature set.
- `playSafe` — Source B + OCR + manual only. No restricted permissions. Play-eligible.

**Architectural requirement (P1, not P5):** both sources implement `TransactionIngestSource`, emitting a common `RawIngestEvent(sourceType, sender, body, receivedAt, packageName?)`. Everything downstream — allowlist, rule engine, dedupe, `pending_transaction`, notification, review UI — is source-agnostic and lives in shared source sets. Only the two capture adapters are flavour-scoped.

**What shipped at P1, and what P2 fills in.** The abstraction and both flavours' skeletons landed in `:feature:ingest`; ingestion itself did not.

- `TransactionIngestSource` and `RawIngestEvent` live in `:core:domain` (`ingest` package). Nothing about either is Android-shaped, so ADR-0014's `paging-common` carve-out is untouched and no new module was needed.
- The port carries `sourceType` and a suspending `status(): IngestSourceStatus`, whose four values are `UNSUPPORTED_IN_BUILD` / `UNAVAILABLE_ON_DEVICE` / `PERMISSION_REQUIRED` / `READY`. `GetIngestSourceStatusUseCase` reads every bound source through it, which is the source-agnostic consumer the split exists to make possible — it lives in `:core:domain` rather than beside the adapters because features may not depend on features, and a Settings row or a §5.2 health banner has to be able to reach it.
- **`playSafe` binds an SMS source too**, permanently reporting `UNSUPPORTED_IN_BUILD`. Same fully-qualified class name as `smsFull`'s adapter, resolved by source set, so the shared Hilt module binds "the SMS source" once and no call site branches. An absent binding would compile equally well and would leave the Play build unable to explain why SMS ingest is missing.
- Both capture components are declared and bind on device: the `smsFull` receiver (`RECEIVE_SMS`, priority 999, never abortive) and `NotificationIngestService` in both flavours. Both are inert. The receiver normalizes and reassembles multipart SMS into a `RawIngestEvent` and hands it to `IngestEventSink`, whose P1 implementation **discards** it — `sms_raw` and `notification_raw` are P2 and the schema is untouched at v5. `NotificationIngestService` does not override `onNotificationPosted` at all: the package allowlist is P2, and there is no way to read a notification body before the allowlist exists without breaking the §5.2 privacy rule. P2 replaces one class (the sink) and adds the one override.
- `RECEIVE_SMS` appears in `src/smsFull/AndroidManifest.xml` and nowhere else, enforced by `restrictedPermissionCheck` (Gradle) and a mirrored CI step rather than by memory. The same check bans `INTERNET` in every source set of every module (Law 6).

**Cross-source dedupe** is mandatory: a UPI payment commonly fires *both* a bank SMS and a GPay notification. Dedupe key = `(amountMinor, direction, roundToMinute(occurredAt), accountLast4 ?: merchantNormalized)` within a **±3 minute** window. On collision, keep the higher-confidence extraction and record the second in `sms_raw`/`notification_raw` as `DUPLICATE_SUPPRESSED` (visible in the Inbox's "Suppressed" filter — never invisible).

---

## 4. Architecture

### 4.1 Pattern
Clean-ish layering + **MVVM with Unidirectional Data Flow**. Single Activity, Compose-only, type-safe Navigation Compose. Hilt for DI. Coroutines + Flow everywhere; no RxJava, no LiveData.

```
UI (Composable, stateless) 
  ↕ StateFlow<UiState> / (UiEvent) -> Unit
ViewModel (holds UiState, no Android imports beyond SavedStateHandle)
  ↕ 
UseCase (pure business logic, single public operator fun invoke)
  ↕
Repository (interface in :core:domain, impl in :core:data)
  ↕
DataSource (Room DAO | SmsReceiver | MlKitOcr | DataStore)
```

### 4.2 Module graph

```
:app                          — Application, MainActivity, NavHost, DI wiring, flavours

:core:model                   — pure Kotlin. Entities, value classes (Money, LedgerType). NO Android deps.
:core:common                  — Result types, dispatchers, date utils, formatters
:core:crypto                  — KeyManager, DEK/KEK wrapping, recovery phrase, backup cipher
:core:database                — Room entities, DAOs, migrations, schema JSONs, SQLCipher init
:core:datastore               — encrypted preferences (settings, onboarding flags)
:core:domain                  — repository interfaces + use cases
:core:data                    — repository impls, mappers
:core:designsystem            — theme, color, type, motion, atoms (LfCard, LfChip, LfButton, ...)
:core:ui                      — shared composites (AmountText, CategoryPicker, DateRangeBar, EmptyState)
:core:testing                 — fakes, fixtures, MigrationTestHelper harness, Compose test rules

:feature:onboarding           — first-run, recovery phrase setup, Recovery screen, permission priming
:feature:dashboard            — home, recent, quick stats, budget rings
:feature:inbox                — pending SMS/OCR review queue, approve/discard
:feature:entry                — add/edit entry (manual), line-item editor
:feature:ledger               — debit & credit lists, filters, search, paging
:feature:analytics            — charts, aggregations, comparisons
:feature:budget               — budget CRUD, progress, alerts
:feature:categories           — category/subcategory/group CRUD, merchant management
:feature:ocr                  — camera capture, file/image/PDF import, receipt parse review
:feature:export               — CSV/XLSX export, encrypted backup & restore
:feature:settings             — theme, security, parser rules, about, diagnostics

:benchmark                    — Macrobenchmark: startup, scroll jank, baseline profile generation
```

**Dependency rule:** `:feature:*` may depend on `:core:*` only. Features never depend on features. `:core:model` depends on nothing. Violations fail the build via a Gradle dependency-rule check.

---

## 5. Feature Specification

### 5.1 SMS Ingest Pipeline

```
SMS arrives
  → SmsBroadcastReceiver (RECEIVE_SMS, priority-ordered, non-abortive)
  → enqueue raw SMS to Room table `sms_raw` IMMEDIATELY (before any parsing)
  → OneTimeWorkRequest(ParseSmsWorker)   [decouples from the 10s receiver limit]
  → SenderAllowlist check → drop if not a known financial sender
  → ParserRuleEngine (versioned regex ruleset)
  → build PendingTransaction(confidence, extractedFields[])
  → dedupe: SHA-256(sender + normalizedBody + minuteBucket) → skip if seen
  → insert into `pending_transaction` with status = PENDING
  → post Notification (channel: "inbox_high", actions: [Review] [Discard])
```

**Extraction targets:** `amount`, `currency`, `direction` (DEBIT/CREDIT), `merchantRaw`, `accountLast4`, `instrumentHint` (UPI/card/netbanking), `referenceNo`, `transactionAt`, `availableBalance`.

**Parser rule engine:**
- Rules live in `assets/parser_rules/v{N}.json`, loaded into `parser_rule` table on first run and on version bump.
- Shape: `{ id, priority, senderPattern, bodyPattern (regex with named groups), fieldMap, direction, confidenceBase, enabled }`
- User-visible rule editor in Settings → "SMS Parsing" (advanced), including a **test bench**: paste an SMS, see what the engine extracts.
- Unmatched SMS from allowlisted senders → still creates a `PENDING` entry with `confidence = 0` and `needsManualFill = true`, plus is logged for rule improvement. **Never silently dropped.**
- **A merchant that does not exist yet is created, never a refusal.** An extracted `merchantRaw` resolves through `MerchantRepository.createOrGet`, **not** `findByName`: the first time a shop appears in an SMS there is by definition no row for it, and treating that as a failure would make the pipeline reject exactly the transactions it exists to capture. `createOrGet` returns the existing merchant when the name normalises onto one, un-hides a hidden row that holds the key (BUG11), and creates otherwise — so **no ingest path may fail for a missing merchant**. The same rule and the same call the manual form uses (§5.4); one door, so the two cannot disagree about what "the same shop" means. Fuzzy matching (§5.5's Jaro-Winkler ≥ 0.88) sits *in front* of this as a suggestion at review time, and never as a gate on committing the entry.

**Notification behaviour:**
- Channel `inbox_high` — importance HIGH, no sound by default (configurable).
- Tap → deep link `ledgerflow://inbox/{pendingId}` → Review screen with fields prefilled and focus on Category picker.
- Action "Discard" → sets status `DISCARDED`, keeps the row (auditable, restorable for 30 days).
- Grouped notification when >3 pending.

**Inbox screen:** list of all pending items, filter by `PENDING` / `DISCARDED` / `SUPPRESSED` (cross-source duplicates) / `FAILED`, bulk approve with a previously used category, swipe-to-discard with undo snackbar.

### 5.2 Notification Ingest Pipeline (**both flavours**)

```
Bank/UPI app posts a notification
  → NotificationIngestService : NotificationListenerService
  → package allowlist check (com.google.android.apps.nbu.paisa.user, com.phonepe.app,
     net.one97.paytm, bank apps, …) → drop if not listed
  → flatten title + text + bigText + subText into a single normalized body
  → write to `notification_raw` IMMEDIATELY (before parsing)
  → OneTimeWorkRequest(ParseIngestWorker)   ← the SAME worker as SMS
  → ParserRuleEngine (same versioned ruleset, different sender-matching field)
  → cross-source dedupe (§3.1) against recent sms_raw + notification_raw
  → insert into `pending_transaction` (status = PENDING)
  → post Inbox notification
```

**Shared with SMS by design:** the rule engine, dedupe, confidence scoring, `pending_transaction` schema, Inbox UI, approval flow, **and §5.1's create-the-merchant rule** are identical. The only source-specific code is the capture adapter and the sender-matching field (`sender` vs `packageName`).

**Permission UX:** `NotificationListenerService` cannot be granted in-app. Onboarding deep-links to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` with an explainer screen, then polls `NotificationManagerCompat.getEnabledListenerPackages()` on resume to confirm. Service disconnection (OEM battery killers are aggressive here) is detected via `onListenerDisconnected()` → `requestRebind()`, plus a Dashboard health banner if the service has been dead > 6 h.

**Privacy hard rule:** LedgerFlow reads notifications **only** from the package allowlist. Notification content from non-allowlisted packages is never read, logged, or persisted — the filter runs before any body access. This is stated verbatim in the permission explainer and in Settings.

### 5.3 OCR Ingest Pipeline

**Input sources:** CameraX capture, gallery/photo picker (`PickVisualMedia`), any file via SAF (`OpenDocument`) — images and PDF. PDFs are rasterized page-by-page via `PdfRenderer` at 300 DPI equivalent.

**Pipeline:**
```
Bitmap → preprocess (deskew, grayscale, adaptive threshold, downscale to ≤1600px long edge)
       → ML Kit TextRecognition v2 (on-device, Latin script; Devanagari model optional add-on)
       → geometric line reconstruction: cluster Text.Element by y-centroid bands
       → column inference: rightmost numeric column = amount; leftmost text run = item name
       → classify each line: HEADER | ITEM | TAX | DISCOUNT | SUBTOTAL | TOTAL | FOOTER | NOISE
       → merchant detection from top-3 header lines → fuzzy match against `merchant` table
       → totals detection via keyword set {TOTAL, GRAND TOTAL, NET AMOUNT, AMOUNT PAYABLE, बिल राशि}
       → reconciliation: |Σ(items) + Σ(tax) − Σ(discount) − total| ≤ max(₹1, 0.5% of total)
       → produce PendingTransaction + List<PendingLineItem>
```

**Review screen (the important part):**
- Editable table: item name, qty, unit price, line total, category, subcategory.
- Reconciliation banner: green ✅ if balanced, amber ⚠️ with the delta if not — **user can still save an unbalanced bill**; the delta is stored as an `UNALLOCATED` synthetic line item so totals never silently drift.
- Bulk actions: select-many → assign category; "apply last category for this merchant".
- **Category memory:** `(merchantId, normalizedItemName) → categoryId` learned mapping table auto-suggests on future bills.
- One `ledger_entry` (parent, = bill total, merchant-level) + N `line_item` children. Analytics can roll up at either grain.
- Original image stored encrypted in app-internal storage, linked as an `attachment`.

**Link-to-SMS flow:** from a PENDING SMS entry, "Add receipt" opens the OCR flow and merges line items into that same pending transaction (amount from SMS is authoritative; OCR total mismatch raises a warning).

### 5.4 Manual Entry

Fast-path sheet: amount (system numeric keyboard, focused on open) → category → subcategory → payment method → date → merchant (autocomplete) → note → optional attachment. Target: **≤4 taps after the amount** for a repeat expense — chip, then Save, with the assignment coming from the chip. (The original wording said "≤4 taps" flat, which was never achievable including the digits under any input method.) Recent/frequent combos surfaced as chips at the top.

**Amount entry uses the system keyboard — ADR-0012.** An in-app keypad shipped first and was reversed: appending digits right-to-left makes typing `125` mean ₹1.25, which is right for a payments app and wrong for a ledger, where the user is usually transcribing an exact figure. Law 3 is preserved by `MoneyFormat.parse`, which converts the typed text to minor units with integer arithmetic only — no `Double` on the path — and which discards anything that is not a digit or a separator, so an OEM keyboard that ignores `KeyboardType.Decimal` is harmless.

Supports both ledgers (segmented control DEBIT | CREDIT at the top). Supports multi-line-item entry manually (same editor as OCR review).

**Unbalanced line items are saved, not refused.** §5.3's rule for a receipt that does not reconcile applies to manual entry too: the difference between the entry total and the sum of its lines is written as an `UNALLOCATED` line item by `ApproveTransactionUseCase`. The user is allowed to save it, the entry total stays authoritative, and the parts always add up to the whole. Applying one rule to both paths is what stops manual and OCR entries disagreeing about what an unbalanced bill means.

**A merchant can be added from the form itself.** The merchant picker's field is a search box and a name box at once: it filters the existing list as you type (the "merchant (autocomplete)" this section already promised), and when the typed name matches no live merchant it offers `Add "…"`. That goes through `MerchantRepository.createOrGet`, so a name that normalises onto an existing merchant selects it rather than refusing, and a name held by a *hidden* merchant un-hides that row with its aliases and default category (BUG11) instead of creating a second one the unique index would reject. The offer is decided on the **normalised key**, not the raw text, so "zepto" and "Zepto Pvt Ltd" both recognise an existing "Zepto" and the offer correctly disappears — otherwise the app would promise a new shop and quietly hand back an old one.

**An itemised entry files at line grain — ADR-0018.** A ₹1,000 bill at a shop that sells across categories is not ₹1,000 of one category. The form offers `Single item | Itemised`; in itemised mode each line carries its own name, unit price, quantity and category/subcategory, the line total is `unit price × quantity` in integer arithmetic (`Money.times(Quantity)`, rounded half away from zero), and **the entry itself stores no category** — `ledger_entry.category_id` and `subcategory_id` are null, and `line_item` is the entry's only filing. A line's category is validated exactly as the entry's is: it must exist, be live, and belong to the entry's ledger (Law 2), and a subcategory must be a child of that line's category. Those checks run inside the approval's transaction with the rest (§6.1.1). A partially itemised bill is just the unbalanced case above — the remainder becomes an uncategorised `UNALLOCATED` line.

**Manual entry does not route through the Inbox.** Law 1 says only `ApproveTransactionUseCase` may insert into `ledger_entry`, so manual entry calls it — but directly, with `source = MANUAL` and `source_ref_id = NULL`, rather than first writing a `pending_transaction` row and immediately approving it. The law exists so that *automated* sources cannot commit without a human; the Save tap on a form the human just filled in **is** that human act. Round-tripping it through a review queue the user would leave in the same gesture is ceremony that adds a table write, a second state to reason about, and a row in the Inbox that was never pending on anything. `pending_transaction` therefore stays out of schema v2 and lands with the ingest pipeline in P2, which is the first thing that actually needs it. In-flight form state lives in `draft_entry` (§6.1.2), which is a different concern: recovering unsaved work, not gating a commit.

### 5.5 Ledgers, Categories, Merchants

- **Debit ledger** and **Credit ledger** are fully separate: separate lists, separate category trees, separate analytics screens, separate budgets (budgets are debit-only). No screen ever displays a net figure combining them. **One exception, and only one: the bin (ADR-0015)**, which lists both books in a single chronological list because it manages storage rather than reading a ledger. The carve-out is narrow and stated as a property rather than as a place — *a list with no figure derived from more than one row*. Its reads are still one statement per book, and every row is signed and coloured from its own `ledger`.
- **The Ledger tab reads the two books, paged.** `LedgerRepository.observeEntries(ledger)` returns `Flow<PagingData<LedgerListItem>>` over per-book `PagingSource` queries against `debit_entries` / `credit_entries` — one statement each, so neither can be pointed at the other book by passing a different argument (ADR-0002). `PagingData` on a domain port is ADR-0014. The screen's `Expenses | Income` control switches *partition*, never filters: there is no "All" segment, no running balance, and no figure on the screen that spans both books (Law 2). The list is unbounded by construction, which is what §11's "0 frames > 16.6 ms during Ledger scroll" needs.
- **A ledger row resolves its own names.** The paged query `LEFT JOIN`s `category` and `merchant`, so a page costs one read rather than a read plus two lookups per row. `LEFT`, not inner: an entry filed under nothing is a real row — §5.1 writes one with `confidence = 0` for an unparseable bank SMS — and an inner join would hide exactly those from the list while they still counted in every total. Neither join filters `deleted_at`, because a hidden merchant keeps labelling the entries it was already on, which is the whole point of soft-deleting one.
- **The Ledger list shows the last 30 days** (`LedgerRepository.LIST_WINDOW_DAYS`), as a `local_date >= :since` bound on the paged queries. **This is a bound on the view and not a retention policy: nothing is deleted at that boundary.** Older entries are still stored, still exported, still restored from a `.lfbk`, and still counted by analytics — §5.6 runs windows out to 5Y and could not do so otherwise. The list is bounded because the screen a user scrolls daily is worth keeping short and recent; date filters and search (P1/P3) are how the rest is reached. A caller passes `Int.MIN_VALUE` for the whole book.
- **Rows are grouped under sticky recency bands:** `Today`, `Yesterday`, `This week`, `This month`. **Rolling, not calendar,** and that is load-bearing rather than pedantic — a calendar month does not tile a rolling 30 days (on 19 August the window reaches back to 20 July), so calendar bands would leave rows belonging to no band at all. Defined as rolling, the four tile the window exactly, which is why there is no "Earlier" band; if one were ever needed it would mean the bands and the query bound had drifted apart. The grouping is read off the query's own `local_date DESC, occurred_at DESC` ordering by comparing each row with the one above it — never by regrouping a materialised list, which would mean holding the whole ledger.
- **A row is two lines: merchant · category across the top, then the timestamp on the left and the amount on the right.** Merchant and category share a line, separated by `·`, because they are two independent facts about the same entry — stacking them implied a hierarchy that does not exist. A uniform two-line height is what makes the list scannable. The naming line wraps rather than truncating (it is the row's identity, and clipping it is BUG9 one level up); the timestamp never truncates either; only a free-text note does, because it is the one field with no length a layout can plan for.
- **Ledger rows use `cornerLarge`**, not `cornerSmall`. At 24dp on a row of this height the shape reads close to a pill, which is the intent; the hairline border still carries the edge, so this is radius alone and not a change of treatment.
- **The second line is measured, not assumed.** Two arrangements were tried and both failed on device, and each looks like the fix for the other: with the timestamp beside the note, a wide amount squeezed it until it ellipsised to "18 Aug, 5:32 …"; with the timestamp stacked *under* the amount, the right-hand column became as wide as the wider of the two — which for anything under about ₹100 is the timestamp — so every row paid that width and, once the delete control took its 48dp, every row collapsed into three lines. The amount is now measured against the real row width and the timestamp gets what is left; only if that falls under a floor do the two stack, which happens per row and per amount rather than on a global font-scale threshold. **That floor is the leading block's own `maxIntrinsicWidth`, not a constant** — a fixed 96dp chosen for naming width let a *narrow* amount at font scale 2.0 leave 144dp beside a timestamp needing 200, so the row stayed side by side and clipped "19 Aug, 10:15 am" to "19 Aug, 10:1…". A long note raises the floor and stacks a row earlier than it used to, which is the right way round: stacking costs a line, clipping costs the information.
- **A row under `Today` or `Yesterday` shows the time only.** The band header already carries the date, and repeating it is both noise and the width that lets the amount sit beside the timestamp instead of below it. The other two bands span up to three weeks, so there the date is the point and it is printed.
- **The time shown is `occurred_at`, not `created_at`** — the row means the transaction, and the entry form's date picker sets exactly this field. For a manual entry saved on the spot they are the same instant; when they differ it is because the user deliberately back-dated a receipt, and showing them when they typed it would be showing them the wrong fact. The 12/24-hour choice follows the *device setting* rather than the locale, because on Android that is an independent switch `DateTimeFormatter` cannot see.
- **An entry can be deleted from the list, behind a confirmation.** A trailing icon on each row, not an inline text action: the taxonomy cards carry three actions over a list of a dozen items, this carries one over a list without end, and a text button would add a line to every row on the screen whose whole job is being scanned. The confirmation names the entry (`₹69.00 · Unfiled will no longer appear in your ledger or totals`) — a dialog that only asks "are you sure?" is one people learn to tap through.
- **Deletion is a soft delete, through `DeleteEntryUseCase`.** `ledger_entry.deleted_at` is set and the row stays; the views already filter on it, so the entry leaves every read path at once. A real `DELETE` would cascade `line_item` away, and those items are the only record of how a bill broke down — they also have to survive for the `.lfbk` round-trip's row-level equality check (§13.1, BUG4). The statement binds **both** `id` and `ledger`: an id is a UUIDv7 with no book inside it, so without the pair a screen showing one book could reach into the other, and with it a mismatch simply affects no rows and returns `EntryNotFound` (Law 2). `AND deleted_at IS NULL` makes a second confirmation on a stale row report "already gone" rather than overwrite the original timestamp.
- **More → "Deleted entries" opens the bin.** A destination, so it takes a noun: the row used to perform the erase itself and was named for the verb, and naming a destination after the most destructive thing inside it is how people learn not to open it. Not "Recently deleted" either — nothing here expires on a timer, and the word would promise a sweep the app does not perform. **The row is always present and always enabled, even at zero**, with the empty case explaining what the bin is for; it was hidden when empty, and that was reported as the feature being missing, because a control that exists only sometimes is indistinguishable from one that was never built.
- **The bin lists every deleted entry from both books, newest first** (ADR-0015), each with its amount signed and coloured from its own ledger, its merchant · category · subcategory, and its date and time. The subcategory appears here and nowhere else in a list, because the bin is where a user tells two otherwise identical entries apart before deciding which to keep. Rows are selected by the `(ledger, id)` pair, never by id alone — an id carries no book inside it, and the list spans both.
- **Selected rows can be restored or erased; everything can be erased at once.** Restore clears `deleted_at`, which returns the entry to its book, its totals and its exports in one statement — that recoverability is the whole reason a delete is soft. **Restore asks no question and erase always does:** putting something back is undone by binning it again, while erasing is the only thing in the app that cannot be walked back. Both destructive confirmations use `LfDialogEmphasis.Warning`, the treatment otherwise reserved for the Recovery Kit, and both tell the user to export first because **the app cannot back them up for them** — `.lfbk` is phrase-derived (ADR-0011) and the app never holds the 24 words.
- **Four audited doors now open `ledger_entry`**, each guarded by `LedgerSingleWriterTest`: `ApproveTransactionUseCase` inserts, `DeleteEntryUseCase` bins, `RestoreEntryUseCase` un-bins, and `PurgeDeletedEntriesUseCase` destroys — whether sweeping a whole book or the rows a user picked. Every statement binds `:ledger`, so a write issued against the wrong book affects nothing and is reported as `EntryNotFound` rather than silently succeeding elsewhere (Law 2). The per-row purge also binds `deleted_at IS NOT NULL`, so a *live* entry can never be reached through it.
- **A purge compacts afterwards, once.** `VACUUM` is not cosmetic: `DELETE` frees pages without zeroing them or shrinking the file, so without it "permanently erase" would be true of the app's queries and false of the bytes on disk. It runs after a `wal_checkpoint(TRUNCATE)` and outside any transaction — SQLite refuses to VACUUM inside one, and an uncheckpointed WAL would leave the deletes out of the file being rewritten. Selective purges compact once at the end rather than per row, since each `VACUUM` rewrites the entire database.
- **`DeleteEntryUseCase` is the sibling of `ApproveTransactionUseCase`, and is guarded the same way.** Law 1 names inserts, so a delete is not literally covered by it — but the law's purpose is that every write to `ledger_entry` has one audited entrance, and removing an entry is the write that changes totals somebody has already read. `LedgerSingleWriterTest` fails the build on any other caller of `softDeleteEntry`. The method is named `softDeleteEntry` rather than `softDelete` so that guard cannot be confused by the taxonomy repositories, which all carry a `softDelete` of their own.
- **The Ledger lists this book's unsaved entries above the recency bands** (ADR-0013). An unsaved entry is not *in* the ledger — it has no date to file under and no amount that counts — so it sits on top of the record rather than inside it, in the same card shape with an accent border and a "Not saved yet" line. Tapping a row opens **that** draft in the entry form; the row hands an id upward and `:app`'s graph decides where it goes, because features never reach each other directly. Its amount is unsigned: the form's `Expense | Income` control is still editable, so a direction glyph would claim something the entry has not decided. Discarding asks first, and in different words from deleting a saved entry — one removes something committed, the other throws away what the user was still typing (§6.1.2, BUG6).
- **`draft_entry` carries a denormalised summary (schema v4, extended in v5):** `amount_minor`, `category_id`, `merchant_id`, `occurred_at`, written by the entry form on every debounced save. The Ledger cannot read `payload_json` — `EntryDraftPayload` is `internal` to `:feature:entry` and `DraftRepository` treats the JSON as opaque, because `:core:domain` knowing a screen's field names would invert the module graph. So the writer, which is the only layer that may know the shape, lifts the three fields out and every reader gets typed columns. **The payload stays authoritative;** if the two ever disagree the payload is right and the summary is a stale render. The columns carry **no foreign keys**: a draft is invalid by definition, and an FK would let a category soft-deleted mid-typing refuse the next 300 ms write — losing keystrokes to referential integrity is BUG6 arriving through BUG6's own countermeasure. `MIGRATION_3_4` rebuilds the table rather than chaining `ALTER`s, and existing drafts keep their payload with a zeroed summary that the next edit fills in.
- **A pending row shows the date and time its entry is dated for**, always with the date: it sits under "Unsaved", which says nothing about *when*, so unlike a committed row there is no header above it carrying the date on its behalf. The value is the draft's own `occurred_at` where it has one, and its last edit where it does not — a draft written before schema v5 has no `occurred_at`, and rendering 1 January 1970 would be worse than approximately right. Back-filling was impossible: the real date lives in the payload and SQLite cannot read it. The row shows the stamp alone, not "… · Not saved yet": on device that pairing ellipsised to "19 Aug, 10:14 am · Not saved…", clipping the field the line was added for, and "unsaved" is already said by the section header, the accent border and the accent-coloured text.
- **The unsaved section is rendered only once the drafts query has answered** (`LedgerUiState.isLoaded`), which is why the state distinguishes "no drafts" from "not read yet". Composing the list first and prepending the section a frame later put it *above* the lazy list's scroll anchor and out of sight — the Ledger appeared to have no unsaved entries at all until the user scrolled up, which nobody does on a list that appears to start at the top. The list is also keyed on the book, so switching tabs builds a new list rather than carrying the previous one's scroll position into it.
- **The entry form's unsaved cards show what each draft is filed as** — merchant · category, on its own line above the note. It reads them straight from `payload_json`, which it owns, so it needs no denormalised columns and shows whatever was last typed. **Two names, not three:** the subcategory was included and came out, because on a card fixed at `peekCardWidth` three names overran the line and the subcategory, being last, was ellipsised away — taking the category with it when it was long. A field that is only sometimes visible is worse than one that is honestly absent, and the whole draft is one tap away. The line is separate from the note because folding both onto one truncating line meant a long note ellipsised away the merchant and category, which is the part that identifies which draft it is.
- **Amounts are coloured by book and prefixed `-` or `+`.** `amount_minor` is stored positive and direction is carried by `ledger`; the prefix is a **glyph chosen from the partition at draw time** (`MoneyFormat.directional`), never a stored or parsed value — a negative amount on any path is the shape Law 2 forbids, because it is the one that could be summed against the other book without looking wrong. The sign exists alongside the colour rather than instead of it: colour alone fails §9.6 for a colour-blind reader, the same reason the category swatch carries an initial. `MoneyFormat.spokenDirectional` renders it as "spent"/"received" for TalkBack, which skips `-` and `+` as reliably as it skips `₹`.
- **The empty book has two messages, and they are not interchangeable.** A user who has never saved an expense sees one; a user whose entries all predate the 30-day window sees another that names the window and says the older ones are still there. Both render zero rows, and showing the first to the second user is telling them their data is gone. The signal is `LedgerRepository.observeHasEntries(ledger)`, an unbounded `SELECT EXISTS` per book.
- **Categories:** two levels (category → subcategory), user-creatable, editable, soft-deletable (re-assign flow required before delete). **Hiding a category takes its subcategories with it**, stamped with one shared `deleted_at`, and restoring it brings the branch back the shape it left in. It previously *reparented* them — to the re-assign target, or with no target to no parent at all, which promoted them to top-level categories the user never created (BUG12). Cosmetic while a delete was one-way; load-bearing now that there is a restore, because a branch that goes out in pieces comes back in pieces. Icon + color per category. System seed set ships pre-populated, all editable **and deletable** — `is_system` records where a row came from, it does not confer protection. (Reading it as a permission is what removed Delete from every seeded card, since every seeded row has `is_system = 1`; a taxonomy the user cannot prune is not theirs.)
- **Category groups:** many-to-many grouping over categories for analytics rollups (e.g. group "Essentials" = Groceries + Utilities + Rent). **The tables ship in schema v2 at P1; the group CRUD *UI* is deferred to P3.** Their only consumer is analytics rollups (§5.6), so building the management screen at P1 would ship a surface with no observable effect for two phases. Carrying the tables early is nearly free and saves a second migration; carrying the UI early is not.
- **Merchants:** canonical merchant + alias table. SMS `merchantRaw` and OCR headers normalize (uppercase, strip punctuation/legal suffixes/store codes) then fuzzy-match (Jaro-Winkler ≥ 0.88) against aliases. Unmatched → a new merchant is **created** via `createOrGet` rather than the entry being refused (§5.1); the fuzzy match is a merge *suggestion* at review time, not a gate. The user can merge merchants later, which is the cheap correction — a wrong auto-merge is the expensive one, which is why the threshold only ever suggests.
- **The taxonomy list is the screen's scrolling surface**, so the chrome around it stays lean. The pinned Add bar pads `xs` top and bottom: `LfScaffold` already insets it for the navigation bar (§8/BUG5), and a second full inset below the button spends list height on space the system bar was already reserving. `xs` is the floor rather than a taste call — measured on device, the button's bottom edge plus that padding plus the 48dp navigation-bar inset land exactly on the top of the navigation bar, so **the Add button cannot be pushed lower without rendering under the system bar**. Further list height has to come from the header instead, which is why the three header bands (title, section control, ledger control) are separated by `sm`/`xs` rather than `md`: on a screen whose header is three stacked bands, each step of the gap scale is charged to the list several times over.
- The card shape below is one instance of the visual rules in **CLAUDE.md §5, "Visual design philosophy"**, which is where those constraints live in full.
- **All three sections share one card shape** (`TaxonomyCard`): a hairline-bordered row on `surfaceRaised`, name first, actions beneath in an `LfActionRow` of `LfButtonStyle.Inline` buttons. Categories were compacted first and merchants and payment methods kept `LfCard` with `Outlined` pills, which left one screen reading as two designs and spent roughly twice the vertical space per row on lists whose only job is letting the user scan what they have. `Inline` is also what fits Rename / Merge / Hide on a single line — as pills the third wrapped to a row of its own. The border carries the shape rather than elevation: at this size elevation reads as a shadow smear, and the border is what keeps the category tree's nesting rail legible against the card edge. At font scale 2.0 the actions still wrap, but as whole controls, never broken labels (§8/BUG9).
- **Hiding is always confirmed.** Categories, subcategories, merchants and payment methods each sit under a row of small actions in a scrolling list, so a delete that fired on the first tap was one mis-tap away at all times. Every one now asks first, naming the row and stating what its own deletion actually does — the three differ (a merchant keeps labelling past entries; a payment method is scrubbed from them; a category may still need its entries re-assigned), and a confirmation that rounds them to one sentence is one the user learns to tap through. For a category this is the first of two questions, ahead of the re-assign flow: this one guards the mis-tap, that one asks where the entries go.
- **Payment methods:** user-defined instances of types `{DEBIT_CARD, CREDIT_CARD, UPI, CASH, NETBANKING, WALLET, OTHER}` with label, issuer, last4. SMS `accountLast4` auto-selects the matching instrument.
- **One verb for one operation: Hide, Restore, Erase (ADR-0016).** The three sections called the same write three different things — categories "Delete", merchants "Hide", payment methods "Remove" — and all three set `deleted_at` and leave the row labelling past entries. Only "Hide" was ever accurate, and it is now also the reassuring one, which frees "Erase" to mean the thing that really does destroy. "Erase" is the word the bin already uses, so the app has **one** vocabulary for permanence rather than one per screen.
- **Every hidden row is listed and restorable.** Each tab carries a collapsed `Hidden (n)` section at the foot of its own list, in the same `TaxonomyCard` shape as the live rows: the name, the day it was hidden, and `Restore` / `Erase`. **A section rather than a destination**, unlike the bin — that earned a screen because a deleted entry is looked for without knowing which book it was in, while a hidden merchant is looked for by someone already standing on the Merchants tab. It is collapsed by default and re-collapses on every tab and ledger switch: hidden rows are the exception on a screen whose job is showing what you have. **Restore asks nothing and Erase always asks**, the same rule the bin follows and for the same reason — putting something back is undone by hiding it again.
- **A hidden branch is one row, not four.** A subcategory hidden *with* its parent is folded into the parent's row, which says "with 2 subcategories"; a subcategory hidden on its own gets its own row, naming the parent it sits under. Offering to restore the pieces of one deletion separately would let a user reassemble a tree they never took apart. Restoring a subcategory whose parent is still hidden brings the **parent back too** — `observeTree` builds children off live parents, so a live subcategory under a hidden one would exist, still be pointed at by entries, and appear on no screen in the app.
- **Erasing is the second irreversible operation in LedgerFlow, and the schema will not protect it.** `ledger_entry.merchant_id` is `ON DELETE SET NULL`, so destroying a merchant *succeeds* and silently strips the shop's name off every entry that ever used it; `category_id` and `subcategory_id` have **no foreign key at all**, so destroying a category leaves entries holding an id that resolves to nothing. SQLite reports success in both cases. The rule that prevents it therefore lives in code: a purge counts the entries referencing the row and refuses with `ReassignRequired(n)` unless it is given somewhere to move them — the same shape §5.5's re-assign flow has always had for a soft delete, applied to the destroy. A payment method needs no such count, because hiding one already scrubbed `payment_method_id` off every entry; the absent parameter is the statement that this was checked.
- **The purge count includes binned entries. The soft-delete count deliberately does not.** `countForCategory` / `countForMerchant` bind `deleted_at IS NULL` — the right question before hiding something. `countAllForCategory` / `countAllForMerchant` omit it, because a destroy strips the reference off a binned entry exactly as thoroughly, and that entry can still be restored from the bin. Four statements that look like two; collapsing them silently narrows the check, and nothing would fail until a user restored an entry months later and found its merchant gone.
- **Every hard delete binds `deleted_at != 0`**, the taxonomy's version of `purgeDeletedEntry`'s `deleted_at IS NOT NULL`: only a hidden row can be destroyed, so no screen can reach a live one even by passing an id it should never have had. The three destroys have **one audited door each** — `PurgeHiddenCategoryUseCase`, `PurgeHiddenMerchantUseCase`, `PurgeHiddenPaymentMethodUseCase` — and `TaxonomySingleWriterTest` fails the build on any other caller, on the same source-scanning principle as `LedgerSingleWriterTest`. **Restore is deliberately not guarded**: un-binning a ledger entry changes totals somebody has already read, while un-hiding a merchant changes what a picker offers and no figure anywhere, and a guard there would be the ceremony that teaches people the guards are ceremony.
- **A purge compacts, through `StorageMaintenance`.** Erasing a merchant erases a name the user typed, so it carries the same obligation the bin does to make the bytes go rather than only the queries. `compactStorage()` moved off `LedgerRepository` to its own port when this became its second caller — which is the condition that interface's own documentation set. The mechanics are unchanged and still delicate: WAL checkpoint, then `VACUUM` outside any transaction, once at the end rather than per row.
- **A hidden merchant's name can be used again (BUG11).** `index_merchant_normalized_key` is `UNIQUE (normalized_key)` and does **not** include `deleted_at`, while `byNormalizedKey` binds `deleted_at = 0` — so `createOrGet` could not see the hidden row blocking it, fell through to an insert, and raised `SQLiteConstraintException` out of a repository whose entire contract is typed refusals. Two taps reproduced it: hide "Amazon", add "Amazon". The fix is not a wider index — two live rows on one key would break the merchant matching the key exists for — but `createOrGet` restoring the row it already has, which is also what the person typing the name meant. **`rename` was the same bug through a second door** (hide "Big Bazaar", rename "DMart" to it) and needs the opposite answer: the row being renamed already exists, so it refuses with `NameHeldByHiddenRow` — naming the hidden row, rather than reporting a duplicate the user cannot find in any list. With both closed, `normalized_key` is unique across live and hidden rows, which is what the index always claimed.
- **Restoring can be refused, in words.** A merchant whose key has been taken since it was hidden comes back as `DuplicateName`, and the honest next step is a merge rather than a second row the index would reject. A category whose live sibling now owns its name is refused the same way, because §6.1.1's index counts `deleted_at` — the two coexist happily while one is hidden and collide the instant it returns. A payment method is refused nothing but **always returns as a non-default**: hiding does not clear `is_default`, and restoring without clearing it is how an install ends up with two rows claiming to be the default and the entry form picking by row order.

### 5.6 Analytics

**Time windows:** Day, Week, Month, 3M, 6M, 1Y, 5Y, Custom range. Every window supports a **previous-period comparison** toggle.

**Aggregation dimensions:** category, subcategory, category group, merchant, payment method, source (SMS/OCR/MANUAL), line-item vs entry grain.

**Filters (composable, all simultaneously active):** ledger (fixed per screen), date range, category multi-select, subcategory multi-select, payment method multi-select, merchant multi-select, amount range, source, has-attachment, text search across note/merchant/item name.

**Views:**
| View | Chart |
|---|---|
| Spend over time | Stacked bar (by category) / line (total) with period toggle |
| Category breakdown | Donut + ranked list with % and Δ vs previous period |
| Subcategory drill-down | Nested list, tap-to-expand, treemap option |
| Merchant leaderboard | Horizontal bar, Top-N + "Other" |
| Payment method split | Donut |
| Calendar heatmap | Day-cell intensity grid (month view) |
| Budget progress | Ring/linear progress per category with burn-rate projection |
| Recurring detection | List of suspected recurring merchants (interval clustering, ≥3 occurrences, σ/μ < 0.25) |

**Performance requirement:** 5Y queries must render in **<300 ms**. Achieved via a `daily_rollup` materialized table (`date, ledger, categoryId, subcategoryId, merchantId, paymentMethodId, sumMinor, txnCount`) rebuilt incrementally by `RollupWorker` on every ledger write and reconciled nightly. Charts read rollups; drill-downs read base tables via Paging 3.

**Rollups are fed at line grain where an entry has lines — ADR-0018.** An itemised entry has no entry-level category, so an entry-grain rollup would drop it entirely or attribute all of it to one category it does not have. A ₹1,000 bill split ₹600/₹400 contributes to two `daily_rollup` rows. **Open for P3:** `txn_count` then becomes ambiguous — one entry contributing to three category rows is one transaction, not three — and the rule for it is deliberately not decided here, since it concerns a table P3 has yet to build. Budgets (§5.7) read the same grain, or a ₹400 kettle inside a grocery bill lands in the grocery budget.

### 5.7 Budgets
Per-category (optionally per-subcategory) budgets. Periods: weekly, monthly, quarterly, yearly. Optional rollover of unspent. Alerts at configurable thresholds (default 80% / 100%) via notification. Debit ledger only. Budget vs actual shown on Dashboard and in Analytics.

### 5.8 Currency & Money — **D-02**

**Decision: one base currency per install. Currency-aware schema. No conversion engine.**

The schema was always currency-tagged, so the storage cost of this is zero. What we are explicitly *not* building is an FX engine — live rate lookup requires `INTERNET`, which violates Principle P3, and historical-rate tables for accurate back-dated conversion are a genuine sub-project that would eat a phase.

| Rule | Detail |
|---|---|
| Base currency | Chosen once at onboarding (default **INR**). Stored in `app_meta.baseCurrency`. |
| Changing base currency | **Not supported in v1.** Would require re-stating every historical entry. Gated behind a "not available" note in Settings, not a broken toggle. |
| `amount_minor` | **Always base currency.** Every analytics query, rollup, and budget sums this and only this. No currency filter is ever needed because there is only one. |
| Foreign spend | Captured with `original_amount_minor` + `original_currency` + `fx_rate_micro` (rate × 10⁶, user-entered or derived from the two amounts). The user enters what their bank actually charged them in base currency — which is the *correct* figure anyway, since it already includes the bank's markup and fees. |
| Display | Foreign entries show `₹4,120` with a subtle `$49.50 @ 83.23` secondary line. Analytics show base only. |
| SMS/OCR extraction | If a parsed currency ≠ base currency, the pending entry is flagged `needsFxInput` and the review screen requires a base-currency amount before approval. Never auto-converted, never guessed. |

**Minor-unit exponent** comes from ISO-4217 per currency (INR/USD = 2, JPY = 0, BHD = 3). Hardcoded lookup table in `:core:model` — no `java.util.Currency` runtime dependency for arithmetic, since its exponent data has been wrong on some OEM ROMs.

### 5.9 Export & Backup

| Artifact | Format | Encryption | Trigger |
|---|---|---|---|
| Data export | CSV (one file per table, zipped) — **shipped, ADR-0017** | none (user's choice, warned) | Manual, SAF destination |
| Data export | XLSX (multi-sheet: entries, line items, categories, merchants, budgets, summary pivots) | none | Manual, SAF destination |
| **Full backup** | `.lfbk` (custom container) | **AES-256-GCM, key = HKDF-SHA256(24-word phrase seed). Never the passphrase (§7.2).** | Manual + `PeriodicWorkRequest` nightly to a user-granted SAF tree URI |

`.lfbk` container format (**all integers big-endian**):

```
┌─ HEADER — authenticated, never encrypted ───────────────────────────────┐
│  magic          char[4]              "LFBK"                             │
│  formatVersion  u16                  container format; currently 1      │
│  schemaVersion  u32                  Room schema version of the payload │
│  kdfId          u8                   1 = HKDF-SHA256 / BIP-39 (§7.2)    │
│  kdfParamsLen   u16                  byte length of kdfParams           │
│  kdfParams      u8[kdfParamsLen]     kdfId-specific; empty for kdfId=1  │
│  salt           u8[16]               HKDF salt, fresh per backup        │
│  nonce          u8[12]               AES-256-GCM IV, NEVER reused       │
│  keyCheck       u8[4]                see below                          │
│  plaintextLen   u64                  payload length before encryption   │
└─────────────────────────────────────────────────────────────────────────┘
   ciphertext     u8[...]
   tag            u8[16]               GCM tag; AAD = the entire HEADER
```

Three properties this format must have, each of which the earlier draft lacked:

1. **`kdfParamsLen` is mandatory.** Without an explicit length, a reader cannot find where `salt` begins unless it already knows every KDF's parameter encoding — which defeats the purpose of having a versioned `kdfId` at all. A container that can only be parsed by the version that wrote it is not a forward-compatible container.

2. **The header is the GCM AAD.** The tag must authenticate `formatVersion`, `schemaVersion`, `kdfId`, `kdfParams`, `salt`, `nonce`, `keyCheck`, and `plaintextLen`. If the header is unauthenticated, an attacker can flip `schemaVersion` and the tag still verifies, steering the restore path with attacker-chosen metadata. For a file whose entire threat model is "this may end up in Drive or a WhatsApp chat" (§7.2), an unauthenticated header is not acceptable.

3. **`keyCheck` distinguishes "wrong phrase" from "corrupt file".** It is the first 4 bytes of `HKDF-SHA256(seed, salt, info = "lfbk-keycheck-v1")`. Without it, both failures surface identically as a GCM tag mismatch, and the Recovery screen — the one screen that must never feel hopeless — cannot tell the user whether to re-check their words or find another copy of the file. Four bytes of a derived value leak nothing exploitable against 256-bit phrase entropy.

**Reader hardening (required):** reject a file whose `magic` is not `LFBK`; reject an unknown `formatVersion` with a clear "created by a newer version of LedgerFlow" message rather than a parse error; and **never allocate `plaintextLen` bytes before the GCM tag verifies** — an attacker-supplied length is a trivial OOM otherwise.

**Payload format:** the ciphertext wraps a **logical row export** (JSON), not a copy of the database file. The file would be simpler, but it is SQLCipher-encrypted with the DEK, so restoring it would require carrying the DEK too — which is exactly the failure mode §7.5 rejects Android Auto Backup for. A logical export also lets a backup written at an older `schemaVersion` be migrated forward, which a raw file cannot do without replaying the whole migration chain. JSON is not compact, but the payload is encrypted anyway and the format is inspectable during a recovery investigation; revisit if a real ledger ever makes the size matter.

**Restore is a single transaction.** A restore that hits a constraint partway leaves the user looking at some of their data with no indication the rest is missing — worse than a restore that refuses outright.

Backup is **key-independent of the Android Keystore** — this is what makes cross-device and post-factory-reset restore possible (§7.4).

**XLSX library note:** Apache POI is unusable on Android (dex bloat + xmlbeans). Use `org.dhatim:fastexcel` (writer-only, lightweight) or hand-roll SpreadsheetML into a zip. Decide in ADR-004.

#### CSV export, as shipped (ADR-0017)

- **One CSV per table, zipped, streamed straight into a SAF document.** No temp file, which is the deliberate opposite of the `.lfbk` writer's temp → fsync → verify → rename. That ceremony exists because a truncated backup sitting where a good one used to be turns a backup system into a data-loss mechanism (BUG4); an export has no such hazard — it is a copy leaving the app, the database is untouched either way, and a half-written one costs a retry. Staging it would mean writing the user's complete unencrypted financial history into `filesDir` as a side effect of exporting it.
- **The file list comes from `BackupPayload`, not from the DAOs.** `DatabaseBackupManager.export()` is `public` for exactly this: it is the codebase's single answer to "which tables are there". A second enumeration in the export path would mean a table added at schema v6 reaches the backup and silently misses every CSV a user takes, with nothing failing anywhere. `ExportCoversEveryTableTest` counts the payload's `List` fields by reflection and fails if the export does not produce a file for each.
- **Money is written twice: the `_minor` integer verbatim, and a decimal string beside it.** One re-imports without a rounding story, the other is what a human reads. **The decimal is assembled by integer division and zero-padding — no `Float`/`Double` touches a money value** (Law 3), and `CsvMoneyTest` asserts it against values a `Double` renders wrong (807 → `8.069999999999999`) rather than against round numbers that would pass either way. `fx_rate_micro` and `quantity_milli` share the routine at six and three decimal places; the scale comes from the column, not from a constant.
- **Timestamps are written twice too** — epoch millis and an ISO-8601 **UTC** string. UTC rather than device-local because the integer beside it is already authoritative, so the string exists to be read, and a local time with no offset is how an export becomes ambiguous a year later in another timezone.
- **`ledger_entry` exports as two files**, `ledger_entry_debit.csv` and `ledger_entry_credit.csv`. Not because Law 2 requires it — a CSV derives no figures, so it could not violate it — but because §5.5 promises the user "separate lists", and an export is the most literal list the app hands over. The rows already arrive per book, so the split is free.
- **Soft-deleted rows are included**, each file carrying `deleted_at`. The bin's erase dialog instructs the user to export first if they might want something back; that instruction is only true if the export contains what they are about to erase. It also keeps the CSV and the `.lfbk` describing the same database rather than two lists that rot apart.
- **RFC 4180**: CRLF, quotes only where required, embedded quotes doubled, UTF-8 with no BOM. **Surrounding whitespace forces quoting** even though the RFC does not require it — unquoted, every spreadsheet trims it on import, so a merchant stored as `" Zepto"` would come back a different string. **Null and empty stay distinguishable**: null writes as nothing, empty writes as `""`, because the schema means different things by them (a cleared note is not an absent one).
- **Three steps: tap, confirm, pick.** The confirmation is not ceremony duplicating the system picker. What the user needs to know is not *where* the file goes but *what it is* — a complete, unencrypted copy of their financial history — and the picker cannot say that. It uses `LfDialogEmphasis.Warning`, the treatment otherwise reserved for the Recovery Kit and the bin's erase, and the page states the same fact standing, for whoever returns to the screen a month later. Cancelling the picker is **silent**: reporting "export failed" for a deliberate cancellation is how a screen teaches people to ignore its messages.
- **The result names counts, not just success.** "80 rows across 11 files" is a receipt; "Done" gives the user no way to tell a real export from one that wrote empty files. A storage failure never shows the exception text — a revoked SAF grant and a full disk are the same sentence to the person holding the phone.

---

## 6. Data Model

All amounts: `Long` minor units. All timestamps: UTC epoch millis + a separate `local_date` (`INTEGER` days-since-epoch) column for fast date-bucketed queries without timezone math in SQL.

### 6.0 Cross-cutting conventions

**Primary keys are UUIDv7, hand-rolled.** There is no UUIDv7 in the Java or Android standard library — `java.util.UUID.randomUUID()` is v4, and nothing on `minSdk 26` provides v7. Rather than take a dependency for it, `:core:common` ships a ~20-line generator: 48-bit big-endian Unix-millis timestamp, 4-bit version `0111`, 2-bit variant `10`, 74 bits from `SecureRandom`, rendered as the canonical 36-char hyphenated string. Time-sortable prefixes keep index locality on `INSERT`, which is the whole reason for choosing v7 over v4. It is unit-tested for monotonicity within the same millisecond and for correct version/variant bits.

**Constraints SQLite supports but Room cannot express** are created by raw SQL in the migration (and in `RoomDatabase.Callback.onCreate` for v1), not via `@Index`/`@Entity` annotations. Room's schema validator compares the *entity model* against the *live database*, so any such object must be declared in a way that keeps the exported schema JSON honest — otherwise every launch fails an `IllegalStateException` about a schema mismatch. Each case is called out at the table below. This applies to partial indices, `CHECK` constraints, and triggers.

### 6.1 Tables

```sql
-- ── Ledger core ────────────────────────────────────────────────────────────
ledger_entry(
  id TEXT PK,                        -- UUIDv7 (time-sortable)
  ledger TEXT NOT NULL CHECK (ledger IN ('DEBIT','CREDIT')),
                                     -- partition key (ADR-0002), NEVER null
  amount_minor INTEGER NOT NULL,     -- always positive, ALWAYS base currency (§5.8)
  currency TEXT NOT NULL,            -- ISO-4217, == app_meta.baseCurrency in v1
  original_amount_minor INTEGER NULL, -- foreign spend only
  original_currency TEXT NULL,        -- ISO-4217
  fx_rate_micro INTEGER NULL,         -- rate x 1e6, user-entered. NEVER auto-fetched.
  occurred_at INTEGER NOT NULL,      -- epoch millis UTC
  local_date INTEGER NOT NULL,       -- days since epoch, device tz at capture
  merchant_id TEXT NULL REFERENCES merchant(id) ON DELETE SET NULL,
  category_id TEXT NULL REFERENCES category(id) ON DELETE SET NULL,
  subcategory_id TEXT NULL REFERENCES category(id) ON DELETE SET NULL,
      -- INVARIANT: subcategory_id IS NULL OR
      --   (SELECT parent_id FROM category WHERE id = subcategory_id) = category_id
      -- Denormalized deliberately (analytics group by category_id without a
      -- self-join). Not expressible as a SQLite CHECK — a CHECK may not run a
      -- subquery — so it is enforced in ApproveTransactionUseCase and by
      -- LedgerEntryConsistencyTest. See §6.1.1.
  payment_method_id TEXT NULL REFERENCES payment_method(id) ON DELETE SET NULL,
  note TEXT NULL,
  source TEXT NOT NULL,              -- 'SMS' | 'OCR' | 'MANUAL' | 'IMPORT'
  source_ref_id TEXT NULL,           -- FK-ish to pending_transaction.id (audit trail)
  is_recurring INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER NULL            -- soft delete
)
INDEX(ledger, local_date DESC)
INDEX(ledger, category_id, local_date)
INDEX(ledger, merchant_id, local_date)
INDEX(source_ref_id)
-- Every index LEADS with `ledger`, so the partition is real in the B-tree:
-- a debit query never traverses credit rows. This is what makes the single
-- -table choice perform identically to two separate tables (ADR-0002).

-- ── Ledger isolation views (ADR-0002) ──────────────────────────────────────
-- DAOs read from these, never from ledger_entry directly. The predicate is
-- part of the object, so a read path cannot omit it. Writes still target the
-- base table via ApproveTransactionUseCase alone (Law 1).
VIEW debit_entries  AS SELECT * FROM ledger_entry
                       WHERE ledger = 'DEBIT'  AND deleted_at IS NULL
VIEW credit_entries AS SELECT * FROM ledger_entry
                       WHERE ledger = 'CREDIT' AND deleted_at IS NULL

line_item(
  id TEXT PK,
  entry_id TEXT NOT NULL REFERENCES ledger_entry(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  name TEXT NOT NULL,
  normalized_name TEXT NOT NULL,
  quantity_milli INTEGER NOT NULL DEFAULT 1000,   -- 1.000 = 1000, supports 0.5 kg
  unit_price_minor INTEGER NULL,
  total_minor INTEGER NOT NULL,      -- SIGNED. An entry's line items sum to its
                                     -- amount_minor, so DISCOUNT rows are
                                     -- negative and UNALLOCATED may be either.
                                     -- §5.3 states reconciliation with the
                                     -- discount subtracted in the formula; the
                                     -- sign lives on the row instead, which is
                                     -- the same arithmetic and means every
                                     -- consumer that sums line items is correct
                                     -- without knowing what a `kind` means.
  kind TEXT NOT NULL,                -- 'ITEM' | 'TAX' | 'DISCOUNT' | 'UNALLOCATED'
  category_id TEXT NULL,
  subcategory_id TEXT NULL
)
INDEX(entry_id), INDEX(normalized_name)

-- ── In-flight entry state (BUG6) ───────────────────────────────────────────
-- The entry form persists here on every field change, debounced 300 ms. This
-- is NOT the ledger: nothing here has been saved by the user, and nothing here
-- is visible to analytics, rollups or any ledger query. See §6.1.2.
draft_entry(
  id TEXT PK,                        -- UUIDv7, generated when the form opens
  ledger TEXT NOT NULL CHECK (ledger IN ('DEBIT','CREDIT')),
  editing_entry_id TEXT NULL REFERENCES ledger_entry(id) ON DELETE CASCADE,
                                     -- NULL  = a new, never-saved entry
                                     -- set   = an in-flight edit of that entry
  editing_entry_key TEXT NOT NULL,   -- COALESCE(editing_entry_id, '')  ← §6.1.1
  payload_json TEXT NOT NULL,        -- the whole form state, including line items
  payload_version INTEGER NOT NULL,  -- form-state format version
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
)
-- v2 carried UNIQUE(ledger, editing_entry_key), allowing one new-entry draft
-- per book. Dropped in v3 -- ADR-0013 supersedes that half of D-06. One
-- edit-draft per entry is now a repository rule, because a partial index
-- (WHERE editing_entry_id IS NOT NULL) is not expressible through Room.
INDEX(ledger, updated_at DESC)
INDEX(ledger, editing_entry_key)
INDEX(updated_at DESC)

-- ── Taxonomy ───────────────────────────────────────────────────────────────
category(
  id TEXT PK, parent_id TEXT NULL REFERENCES category(id),
  parent_key TEXT NOT NULL,          -- COALESCE(parent_id, '')  ← see note
  ledger_scope TEXT NOT NULL,        -- 'DEBIT' | 'CREDIT'  ← trees are disjoint
  name TEXT NOT NULL COLLATE NOCASE,
  icon TEXT NOT NULL, color_argb INTEGER NOT NULL,
  sort_order INTEGER NOT NULL, is_system INTEGER NOT NULL DEFAULT 0,
  deleted_at INTEGER NOT NULL DEFAULT 0   -- 0 = live; else soft-delete millis
)
UNIQUE(parent_key, name, ledger_scope, deleted_at)

category_group(id TEXT PK, name TEXT NOT NULL, color_argb INTEGER, ledger_scope TEXT NOT NULL)
category_group_member(group_id TEXT, category_id TEXT, PRIMARY KEY(group_id, category_id))

merchant(id TEXT PK, canonical_name TEXT NOT NULL, normalized_key TEXT NOT NULL UNIQUE,
         default_category_id TEXT NULL, logo_ref TEXT NULL, deleted_at INTEGER NULL)
merchant_alias(id TEXT PK, merchant_id TEXT NOT NULL, alias TEXT NOT NULL,
               normalized_alias TEXT NOT NULL, UNIQUE(normalized_alias))

item_category_memory(merchant_id TEXT, normalized_item TEXT, category_id TEXT,
                     subcategory_id TEXT, hit_count INTEGER NOT NULL DEFAULT 1,
                     PRIMARY KEY(merchant_id, normalized_item))

payment_method(id TEXT PK, type TEXT NOT NULL, label TEXT NOT NULL,
               issuer TEXT NULL, last4 TEXT NULL, color_argb INTEGER,
               is_default INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NULL)
INDEX(last4)

-- ── Ingest / approval ──────────────────────────────────────────────────────
sms_raw(id TEXT PK, sender TEXT NOT NULL, body TEXT NOT NULL,
        body_hash TEXT NOT NULL UNIQUE, received_at INTEGER NOT NULL,
        sim_slot INTEGER NULL, parse_status TEXT NOT NULL, matched_rule_id TEXT NULL,
        retention_expires_at INTEGER NOT NULL)   -- purge raw bodies after N days (default 90)

notification_raw(id TEXT PK, package_name TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
        body_hash TEXT NOT NULL UNIQUE, posted_at INTEGER NOT NULL,
        parse_status TEXT NOT NULL, matched_rule_id TEXT NULL,
        retention_expires_at INTEGER NOT NULL)

package_allowlist(package_name TEXT PK, label TEXT, enabled INTEGER NOT NULL DEFAULT 1)

pending_transaction(
  id TEXT PK, source TEXT NOT NULL,   -- 'SMS' | 'NOTIFICATION' | 'OCR' | 'MANUAL'
  dedupe_key TEXT NOT NULL,           -- §3.1 cross-source key
  suppressed_by_id TEXT NULL,         -- set when deduped against another source
  raw_ref_id TEXT NULL,              -- sms_raw.id or attachment.id
  extracted_json TEXT NOT NULL,      -- typed payload, versioned
  confidence REAL NOT NULL,
  status TEXT NOT NULL,              -- 'PENDING' | 'APPROVED' | 'DISCARDED' | 'FAILED'
  needs_manual_fill INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, reviewed_at INTEGER NULL,
  approved_entry_id TEXT NULL
)
INDEX(status, created_at DESC)

pending_line_item(...)               -- mirrors line_item, pre-approval

parser_rule(id TEXT PK, ruleset_version INTEGER NOT NULL, priority INTEGER NOT NULL,
            sender_pattern TEXT NOT NULL, body_pattern TEXT NOT NULL,
            field_map_json TEXT NOT NULL, direction TEXT NULL,
            confidence_base REAL NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
            is_user_defined INTEGER NOT NULL DEFAULT 0)

sender_allowlist(sender_pattern TEXT PK, label TEXT, enabled INTEGER NOT NULL DEFAULT 1)

-- ── Budgets, attachments, analytics ────────────────────────────────────────
budget(id TEXT PK, category_id TEXT NOT NULL, subcategory_id TEXT NULL,
       period TEXT NOT NULL, amount_minor INTEGER NOT NULL,
       start_date INTEGER NOT NULL, rollover_enabled INTEGER NOT NULL DEFAULT 0,
       alert_thresholds TEXT NOT NULL DEFAULT '80,100', deleted_at INTEGER NULL)

attachment(id TEXT PK, entry_id TEXT NULL, file_path TEXT NOT NULL,
           mime TEXT NOT NULL, sha256 TEXT NOT NULL, bytes INTEGER NOT NULL,
           created_at INTEGER NOT NULL)

-- Every PK column is NOT NULL and uses '' as the "no such dimension" sentinel
-- (uncategorized spend, no merchant, …). Room requires non-null PK fields, and
-- SQLite treats NULLs as distinct in a composite key — so nullable dimensions
-- would both fail Room codegen AND silently fan one logical bucket out into
-- many un-mergeable rows. Rollup writers MUST map NULL -> ''. See §6.1.1.
daily_rollup(local_date INTEGER NOT NULL, ledger TEXT NOT NULL,
             category_id TEXT NOT NULL DEFAULT '', subcategory_id TEXT NOT NULL DEFAULT '',
             merchant_id TEXT NOT NULL DEFAULT '', payment_method_id TEXT NOT NULL DEFAULT '',
             sum_minor INTEGER NOT NULL, txn_count INTEGER NOT NULL,
             PRIMARY KEY(local_date, ledger, category_id, subcategory_id,
                         merchant_id, payment_method_id))

app_meta(key TEXT PK, value TEXT NOT NULL)   -- schemaVersion, dekWrapVersion, lastBackupAt, canary
```

### 6.1.1 Constraint notes

**`category` uniqueness — sentinels instead of a partial index.** The original constraint was `UNIQUE(parent_id, name, ledger_scope) WHERE deleted_at IS NULL`. Room's `@Index` has no `WHERE` clause, so that index can only be created by raw SQL, which then diverges from the schema Room validates against and throws on every launch. Worse, the naive fix doesn't work either: SQLite treats `NULL`s as *distinct* in a unique index, so with a nullable `parent_id` and a nullable `deleted_at`, two live top-level categories both named "Food" would not collide — the constraint would be decorative.

The fix removes the nulls rather than the constraint:

| Column | Change | Effect |
|---|---|---|
| `parent_key` | `NOT NULL`, `COALESCE(parent_id, '')` | root categories share the `''` key and therefore do collide |
| `deleted_at` | `NOT NULL DEFAULT 0` (0 = live) | live rows share `0` and collide; soft-deleted rows carry distinct millis and don't |
| `name` | `COLLATE NOCASE` | "Food" and "food" are the same category |

The result is a plain `@Index(unique = true)` that Room can declare, export, and validate — mechanically enforced rather than merely intended. `parent_id` stays as the real nullable FK for referential integrity; `parent_key` is maintained alongside it by `CategoryRepository` and asserted by a test.

**`ledger_entry.subcategory_id`.** Denormalizing both `category_id` and `subcategory_id` onto the entry is deliberate — analytics groups by `category_id` without a self-join against `category`. The cost is that the two columns can disagree. SQLite `CHECK` constraints cannot contain subqueries, so this is enforced in `ApproveTransactionUseCase` (the single writer, per Law 1) and asserted by `LedgerEntryConsistencyTest`, which scans for rows where the subcategory's parent is not the recorded category.

**`daily_rollup` sentinels.** See the inline comment above: `''` means "this dimension does not apply", never `NULL`.

**Ledger isolation is enforced at four levels (ADR-0002).** Law 2 is not left to reviewer attention:

| Level | Mechanism | Catches |
|---|---|---|
| Schema | `CHECK (ledger IN ('DEBIT','CREDIT'))`, `NOT NULL` | null or garbage partition values |
| Physical | every index leads with `ledger` | cross-ledger traversal; makes the partition real, not notional |
| Read path | `debit_entries` / `credit_entries` `@DatabaseView`s; DAOs never `SELECT` from `ledger_entry` | any read query that forgets the filter — the predicate is part of the object |
| Test | `LedgerIsolationTest` reflects over every DAO `@Query` and fails any statement naming `ledger_entry` without binding a ledger discriminator | raw queries, new DAOs, anything bypassing the views |

When a migration alters `ledger_entry`, both views must be dropped and recreated **in the same migration**. Room's schema validation will fail otherwise, which is the desired failure mode.

### 6.1.2 `draft_entry` — design notes (**D-06**, closes Q6)

**One row per in-flight entry, not a singleton.** The singleton is simpler and matches the "Resume unsaved entry?" phrasing in §8/BUG6(b), but it has a failure mode that disqualifies it: starting a second entry silently destroys the first. That is *precisely* BUG6 — in-progress expense data vanishing without the user acting to discard it — reintroduced by the mechanism meant to prevent it. A singleton also cannot represent an in-flight **edit** of an existing entry, which is equally state that must survive process death.

**Uniqueness was scoped, and is now unlimited — ADR-0013.** The original design read: *unbounded drafts would accumulate into a list nobody curates*, so `UNIQUE(ledger, editing_entry_key)` allowed exactly one new-entry draft per ledger and one edit-draft per existing entry, and starting a new entry *resumed* the existing draft.

That is superseded. The objection was right about the risk and wrong about the remedy: drafts accumulate unseen only if nothing shows them, and the constraint's cost was that a second in-flight entry could not exist at all — starting one silently resumed the first, which reads from the outside exactly like the singleton D-06 rejected. Schema v3 drops the index and the entry screen shows the unsaved entries **inline, newest first**, with Open / Discard per card and a `Start another` action.

**The surface is a horizontal peek shelf, not a vertical stack.** "Stacked like notifications" was the original request and the first build took it literally, as a vertical list above the form. On device that was the wrong shape for this screen: the drafts are not the content, the form is, and a vertical list pushes the thing the user came to use below the fold in proportion to how many drafts they have — worst exactly when they have the most. A `LazyRow` of fixed-width cards costs one row of height regardless of count, and a partially visible next card is what tells the user the row continues; a vertical list has no equivalent affordance short of scrolling it. Two details are load-bearing rather than cosmetic:

- **`Start another` lives in the shelf header, not in the row.** In the row it was unreachable in the state that needs it most: with a single draft it was the peeking card at the right edge, and at font scale 2.0 its label clipped to "Start anoth". The header is a `FlowRow`, so at large font scales the count and the action wrap as whole controls rather than truncating (BUG9).
- **Opening a draft does not touch its `updated_at`.** Recency orders the shelf, so if merely looking re-dated a draft, checking what is in the shelf would reshuffle the shelf — the ordering would encode "what I last glanced at" instead of "what I last worked on". The write path is gated on the form actually being dirty.

What survives from the original reasoning: the **30-day sweep**, which matters more now rather than less and is what stops "many drafts" becoming "drafts forever"; and **one edit-draft per entry**, now enforced by the repository (`findForEntry`) because a partial index is not expressible through Room's `@Index`.

A draft's identity is its **id**, not its slot: the form holds the id it was given and passes it back on every debounced write. Without that, the dropped index would let a form deposit a fresh draft every 300 ms. The form no longer auto-resumes — it opens empty and the shelf offers what is unsaved, because with several half-finished entries "resume the most recent" is the app guessing which one the user meant. **Cancel parks rather than exits** when the form holds unsaved input: leaving the screen would be the silent destruction D-06 exists to prevent, so Cancel writes the draft to the shelf and clears the form, and only leaves the screen when there is nothing to park.

This covers **manual drafts only**. The SMS/notification queue is `pending_transaction` and the Inbox (§5.1, §5.2) at P2, kept separate per §5.4: one gates a commit and is what Law 1 is about, the other recovers typing and gates nothing.

`editing_entry_key` follows the `category.parent_key` pattern from §6.1.1 for the same reason: SQLite treats `NULL`s as distinct in a unique index, so a nullable `editing_entry_id` in the constraint would let unlimited new-entry drafts collide-free and make the index decorative. `editing_entry_id` remains the real nullable FK, carrying `ON DELETE CASCADE`.

**The payload is JSON, not typed columns.** A draft is partial and invalid by definition — an amount mid-keystroke, no category chosen yet, a line item with a blank name. Mirroring `ledger_entry` as typed columns would require every one of them to be nullable, which forfeits the constraint value that motivated typing them. Against that, the draft is never queried by any dimension: no filter, no aggregate, no join. The decisive argument is the write path — the multi-line-item editor means a typed model needs a `draft_line_item` child table, so every 300 ms debounce tick becomes a multi-row delete-and-reinsert transaction. As one JSON column it is a single-row upsert, which is what keeps the form off the StrictMode `penaltyDeath` tripwire (§11).

`ledger` is a real column and is **authoritative**; it is deliberately absent from `payload_json` so the two can never disagree. `payload_version` guards form-state format changes: a draft whose version is unrecognised is not offered for resume and is never deserialized, but the row is retained rather than deleted — the app does not destroy user input to tidy up after itself.

**Retention.** A draft is deleted when its entry is saved or the user explicitly discards it. Orphans — the app was killed and the user never returned — are purged at 30 days by a single `DELETE` on app open. `draft_entry` participates in `.lfbk` backup like every other table; the backup is a whole-database logical export and a per-table inclusion list is exactly the kind of thing that rots silently.

### 6.2 Money type
```kotlin
@JvmInline value class Money(val minor: Long) {
    operator fun plus(o: Money) = Money(minor + o.minor)
    // no Float, no Double, no BigDecimal in hot paths
}
```
Room `TypeConverter` maps `Money ↔ Long`. Display formatting lives in `MoneyFormat` (`:core:designsystem`) and nowhere else.

Two things there are load-bearing rather than incidental:

- **Both directions go through `BigDecimal`, never `Double`.** Law 3's ban does not stop at arithmetic: `format(aDouble)` on the way out, or `text.toDouble() * 100` on the way in, reintroduces exactly the rounding it exists to prevent at the last step before the user sees the number, or the first step after they type it.
- **Grouping is a property of the currency, not of the device.** Indian digit grouping writes ₹1,24,000 where the western convention writes 124,000, and a rupee amount should look like rupees on a phone set to English (US). It is also hand-rolled rather than delegated: `NumberFormat` for `en-IN` returns western grouping on the desktop JDK the unit tests run on, and `java.text.DecimalFormat` carries a single grouping size and structurally cannot express the 3-then-2s shape at all. Only the separator *characters* come from the locale.

---

## 7. Security & Data Durability (the section that prevents BUG4/BUG8)

### 7.1 Encryption at rest
- **Database:** SQLCipher 4 (`net.zetetic:sqlcipher-android`) via `SupportOpenHelperFactory`, wired into Room. PRAGMA: `cipher_page_size=4096`, `kdf_iter` default (do NOT lower), `cipher_memory_security=ON`.
- **Attachments:** AES-256-GCM per-file, same DEK, stored in `filesDir/attachments/` (internal storage only — never `cacheDir`, never external).
- **Preferences:** DataStore encrypted with the same DEK (or `EncryptedSharedPreferences` for the tiny pre-unlock bootstrap set).

### 7.2 Key hierarchy — **multi-wrapped DEK** — **D-03**

**Decision: 24-word recovery phrase is the primary and mandatory recovery factor. The optional passphrase wrap (KEK-C) is dropped — see D-05 / ADR-0011.**

```
        ┌─────────────────────────────────────────┐
        │  DEK  (32 random bytes, generated once) │  ← the only thing that decrypts data
        └───────────────┬─────────────────────────┘
                        │ wrapped independently by each factor
      ┌─────────────────┴────────────┐            ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐
      ▼                              ▼              (KEK-C: RESERVED, NOT SHIPPED)
 KEK-A: Keystore              KEK-B: recovery phrase │ slot kept so adding it   │
 AES-256-GCM                  HKDF-SHA256(BIP-39 seed) later is additive and
 StrongBox if avail           256-bit entropy        │ needs no format change.  │
 userAuth = FALSE             MANDATORY                        ADR-0011
      │                              │              └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
      ▼                              ▼                          ┆
 wrapped_dek_ks.bin           wrapped_dek_phrase.bin      wrapped_dek_pass.bin
 (filesDir — contents are wrapped, safe at rest)          (never written)
```

**Why the phrase is primary, not the passphrase:** effective security of a multi-wrapped key equals the **weakest** wrap. A human-chosen 12-character passphrase is nowhere near 256 bits. Making the passphrase the recovery factor would silently downgrade the whole scheme to whatever the user typed at 11 p.m. during onboarding. The generated phrase has fixed, known entropy and cannot be weak.

**Threat-model separation (the important subtlety):**

| Artifact | Protected by | Attacker needs |
|---|---|---|
| On-device DB | KEK-A (Keystore) | Physical device + device unlock. The device itself is the outer boundary. |
| `.lfbk` backup file | **KEK-B (phrase) only.** | 256-bit phrase. A leaked backup file is computationally useless. |

The separation still matters even with KEK-C dropped: it is what forbids any future device-local convenience factor from ever touching the backup path. A file that may end up in Google Drive or a WhatsApp chat is protected by 256 bits of generated entropy or it is not protected.

**Key derivation — pinned byte-for-byte.** "HKDF-SHA256(24-word phrase seed)" is ambiguous: "seed" could mean the 256-bit BIP-39 *entropy* or the 512-bit BIP-39 *seed*. These are different values, and choosing differently in a later version renders every existing backup permanently undecryptable — precisely the P4 catastrophe this section exists to prevent. The derivation is therefore fixed:

```
mnemonic    24 words, BIP-39 English wordlist, NFKD-normalized,
            single U+0020 between words, no leading/trailing whitespace
entropy     256 bits; the BIP-39 checksum is validated BEFORE any KDF runs
seed        PBKDF2-HMAC-SHA512(password = mnemonic UTF-8 bytes,
                               salt     = "mnemonic",
                               c = 2048, dkLen = 64)        ← the BIP-39 seed
KEK-B       HKDF-SHA256(ikm = seed, salt = kekBSalt[16],
                        info = "ledgerflow-kek-b-v1", L = 32)
backupKey   HKDF-SHA256(ikm = seed, salt = container.salt[16],
                        info = "lfbk-backup-v1",      L = 32)
keyCheck    HKDF-SHA256(ikm = seed, salt = container.salt[16],
                        info = "lfbk-keycheck-v1",    L = 4)
```

- The BIP-39 optional passphrase (the "25th word") is **empty and unused**. Supporting it would reintroduce a user-chosen secret into the backup path, which is exactly what D-03 forbids.
- `info` strings are versioned. Changing one is a breaking format change and requires a `formatVersion` bump in §5.9.
- **A golden test vector is committed** in `:core:crypto`: one fixed known mnemonic → expected `seed`, `KEK-B`, `backupKey`, and `keyCheck`, as hex. This test is what stops a refactor from silently changing the derivation. It must never be "re-recorded" to match new output — if it fails, the code is wrong, not the fixture.

**KEK-C is dropped — D-05, ADR-0011.** The P0 deferral is resolved as "do not ship it". KEK-A already provides frictionless daily unlock and KEK-B already provides complete recovery, so KEK-C's entire value was the narrow case of "Keystore was invalidated *and* the user would rather not type 24 words". Against that it wanted either a native `.so` per ABI (Argon2id at m=64 MiB) against a 15 MB budget SQLCipher is already taxing, or a pure-Java implementation whose only speed lever is the memory parameter that *is* its security. The decisive cost was neither: a third wrap adds a branch to the §7.3 unlock state machine, to §7.4 onboarding, and to **both** §7.7 rotation procedures, and every one of those branches is a place where a wrap goes stale and the failure is shaped like data loss. `wrapped_dek_pass.bin` and `app_meta.dekWrapVersion` keep the slot reserved, so reintroducing it later is additive and needs no format change. Full reasoning and reversal triggers in ADR-0011.

**The cost is accepted explicitly and paid in interaction design.** A user whose Keystore is invalidated types 24 words. The Recovery screen (§7.3) is therefore a first-class surface, not a fallback: BIP-39 autocomplete, per-word validation, checksum verified before any KDF work, visible progress, no dead ends. Friction we declined to remove with a passphrase gets removed there instead.

**Recovery Kit — plaintext, behind an explicit confirmation (D-07, closes Q8).** At onboarding the app generates the phrase and offers a one-tap **"Save Recovery Kit"** → writes a plain-text `.txt` + a printable PDF to a user-chosen SAF location, containing the 24 words, the install date, and restore instructions. Also displayed on screen for manual transcription, and the user is prompted to store it in a password manager.

The file is **not** encrypted, and the tap that writes it is gated behind a dialog that says so in those terms — that this file is the master key to every backup, that it is being written to shared storage which may be cloud-synced, and where it is going. The rejected alternative was a password-protected PDF: it reintroduces a user-chosen secret into the recovery path, which is exactly what D-03 forbids for the backup path and for the same reason, and PDF password encryption is weak on its own terms. Encrypting the kit also creates a regress — the thing that protects the thing that protects everything — whose answer is always another secret the user can forget. Plaintext plus informed consent is the honest version of a trade-off that cannot be engineered away.

### 7.3 Unlock flow (self-healing)
```
1. Try unwrap DEK with KEK-A (Keystore).
   ✅ → open DB → verify canary row in app_meta decrypts to known value → proceed.
2. ❌ KeyPermanentlyInvalidatedException / UnrecoverableKeyException / canary mismatch:
   → show RECOVERY screen (never a crash, never a wipe prompt)
   → 24-word phrase entry (word autocomplete from the BIP-39 list, per-word
     validation, and checksum validation before any expensive KDF work)
   → unwrap DEK → regenerate KEK-A in Keystore → re-wrap → overwrite wrapped_dek_ks.bin
   → proceed. User loses nothing.
3. ❌ Neither factor available:
   → offer restore from .lfbk backup file (needs the phrase)
   → offer "start fresh" ONLY behind an explicit type-the-word-DELETE dialog. Never automatic.
```

**The canary stays — D-08, closes Q9.** The objection was correct on its own terms: SQLCipher fails the database open with an HMAC error on a wrong key, so the canary cannot detect the case it superficially appears aimed at. What it does detect is a *mismatch* between a correctly-opened database and the DEK that was expected to open it — a `.lfbk` restored alongside the wrong wrapped blob, or a §7.7 DEK rotation whose page-swap and blob-commit steps did not both land. Both of those open cleanly and then serve wrong or partial data, which is the failure mode with no other detector in the system. It costs one row read on a path that is already doing a database open, and step 2 above already routes its failure somewhere safe. Cheap defence-in-depth against a silent failure is worth keeping; the honest correction is to stop describing it as wrong-key detection, which this paragraph does.

### 7.4 Onboarding hard gate

The user **cannot** reach the main app until they have:
1. Chosen a base currency (§5.8).
2. Been shown the generated **24-word recovery phrase** (BIP-39 wordlist, 256-bit entropy).
3. Passed a **word challenge** — re-enter 3 randomly chosen positions. No skip button. A "Remind me later" here would defeat the entire durability design.
4. Either saved the Recovery Kit to a SAF location **or** explicitly dismissed the offer after a warning dialog.
5. Granted a SAF tree URI for automatic backups **or** explicitly declined with a warning dialog.

There is no optional-passphrase step. It was specified here and is removed by D-05 / ADR-0011; the gate is five steps, not six.

This is friction. It is intentional. It is the single control that makes "data permanently unrecoverable" structurally impossible.

### 7.5 Android Auto Backup
`android:allowBackup="false"` and `android:dataExtractionRules` deny-all.

**Reason:** Auto Backup / D2D transfer copies the *encrypted DB file* but **cannot** copy the Keystore key. Restoring onto a new device yields an undecryptable database and the exact failure mode you named. LedgerFlow ships its own phrase-derived `.lfbk` backup instead, which is device-independent by construction.

### 7.6 Optional app lock
Biometric / device-credential gate on app open (`BiometricPrompt`, `DEVICE_CREDENTIAL` fallback). **This gates UI access only — it never gates the DEK**, so biometric re-enrollment can never lock the user out of their data.

### 7.7 Key rotation — **ADR-0009**

A recovery factor that can never be changed is a recovery factor that stays compromised forever. Two constraints shape the design:

- **`PRAGMA rekey` is not crash-atomic.** It rewrites every page in place; a process death midway leaves a database encrypted under two different keys with no way to tell which pages are which, and no rollback. It is never used.
- **Rotating a *factor* is not the same as rotating the *DEK*.** The multi-wrap design in §7.2 exists precisely so these separate. Re-wrapping the DEK under new words touches one small file. Rewriting the database is only necessary if the DEK itself is suspect. Conflating them would make the common case needlessly slow and crash-sensitive.

| Scenario | What changes | Cost |
|---|---|---|
| Words exposed / user wants new words | the **wrap** only — DEK unchanged | milliseconds |
| DEK or database file compromised | every page of the database | full rebuild |

**1. Phrase rotation (common).** Generate a new mnemonic → word challenge (no skip, §7.4) → unwrap the DEK with the current factor → derive the new KEK-B → write `wrapped_dek_phrase.bin.tmp`, fsync, **verify by unwrapping back to the live DEK**, atomic rename → bump `app_meta.dekWrapVersion` → write and verify a fresh `.lfbk` under the new phrase. The database is never opened for writing.

**2. DEK rotation (device compromise).** Verified `.lfbk` snapshot (rollback point) → new DEK, wrapped under KEK-A and KEK-B → `ATTACH` a sidecar file keyed by the new DEK and `SELECT sqlcipher_export()` → verify the sidecar (`integrity_check`, `foreign_key_check`, canary, per-table row equality) → atomic swap `live → .rotating.old`, `sidecar → live` → commit the wrapped blobs → reopen successfully, **then** delete `.rotating.old`. Everything before the swap is discardable; everything after is idempotent on retry. On next launch, a `.rotating.old` beside a healthy live database means cleanup was interrupted — resume; beside an unopenable one means the swap was interrupted — roll back.

**Rotation cannot un-leak existing backups.** `.lfbk` files are encrypted with a phrase-derived key (§5.9), so every copy already written remains decryptable with the **old** words, forever. Rotation protects future backups only. The flow therefore ends on an explicit screen listing where backups are known to have been written, instructing the user to destroy them. Rotating silently and letting the user assume otherwise would be worse than not offering rotation at all.

---

## 8. Known-Bug Countermeasures (explicit, testable)

| Bug | Root causes | Countermeasures | Verification |
|---|---|---|---|
| **BUG1** — data vanishing during builds/testing | `fallbackToDestructiveMigration()`; debug build overwriting release with different signing key → forced uninstall; `clearPackageData` in test runner | (a) Destructive migration **banned** in `release` + `debug` (allowed only in a `dev` source set that ships never). (b) `applicationIdSuffix ".debug"` so debug and release coexist as separate apps with separate data. (c) Single dev keystore committed-by-reference in `keystore.properties` (gitignored, backed up off-repo). (d) Never `adb uninstall`; use `adb install -r`. (e) Instrumented tests never touch the app's real database: each suite opens its **own** SQLCipher file via `VaultSession`'s injected database name. (Not an in-memory DB — the durability guarantees under test are properties of a real encrypted file on disk, and an in-memory one would assert nothing about them.) `connectedAndroidTest` also uninstalls both APKs when it finishes, which takes the app's data with it, so `android.injected.androidTest.leaveApksInstalledAfterRun=true` disables that — it is the same destruction (d) forbids, arriving through the test task. | CI: grep for `fallbackToDestructive` in release sources → fail. Manual: install-over-install matrix in `TESTING.md`. |
| **BUG2** — data erased on restart / OS / kernel update | Data written to `cacheDir` or app-specific external storage (both OS-clearable); unflushed WAL; app hibernation auto-revoke | (a) **All** persistent data in `filesDir` / `databases/`. Lint rule bans `cacheDir` outside the image-decode path. (b) `PRAGMA wal_checkpoint(TRUNCATE)` on `ON_STOP` lifecycle event + after every batch write. (c) `setAutoRevokeWhitelisted(true)` request + Settings deep link to disable "Pause app activity if unused". (d) Nightly `.lfbk` backup to SAF tree (survives everything). | Instrumented test: write → force-stop → simulate `LowMemoryKiller` → relaunch → assert row count. Manual OTA-update checklist. |
| **BUG3** — version rollback / non-persisting app version | Sideloading an older APK; debug/release signature mismatch; Play rollback; `versionCode` collisions | (a) `versionCode` derived from a monotonic counter in `version.properties`, auto-incremented by a Gradle task, committed. CI fails if `versionCode` ≤ previous git tag's. (b) One signing key for all release builds, period. (c) `AppVersionGuard` on startup: if `installedVersionCode < app_meta.lastSeenVersionCode` → block writes, show "Downgrade detected — restore from backup or reinstall latest" screen. Never auto-migrate downward. (d) `android:rollbackDataPolicy="retain"`. | Startup guard has a unit test with a forged `app_meta`. |
| **BUG4** — backup errors / undecryptable restore | Keystore key not portable; corrupt/truncated backup; silent worker failure | (a) §7.2 dual-wrap + §7.5 own backup format. (b) Every `.lfbk` write is atomic: write to `.tmp` → fsync → verify by full decrypt-and-parse → rename. A backup is only recorded as successful after a **round-trip verification**. (c) `BackupWorker` failure posts a persistent notification; `lastBackupAt` age > 7 days shows a Dashboard warning banner. (d) Keep the last **5** backups, rotate oldest. | Instrumented: backup → wipe → restore → assert full row-level equality across all tables. Runs in CI on every PR. |
| **BUG5** — UI overlap / misalignment | Android 15+ mandatory edge-to-edge; no inset handling; fixed dp assumptions; large font scale | (a) `enableEdgeToEdge()` + every screen uses `LfScaffold`, which consumes **system bars and display cutout** for layout and the **keyboard exactly once**, via `imePadding()` on the scaffold itself. Deliberately *not* `safeDrawing`: that set includes the IME, and consuming it on both the content and a pinned bottom bar subtracts the keyboard twice — measured on device, the bar rendered 8 pixels tall at the top of the screen with no room left for content. Removing the IME entirely fails the other way, because `enableEdgeToEdge()` makes the manifest's `adjustResize` a no-op. No hardcoded status/nav bar padding. (b) `@PreviewScreenSizes` + `@PreviewFontScale` (up to 2.0x) + RTL preview on every top-level screen. (c) Screenshot tests (Roborazzi/Paparazzi) on 5 device configs, diffs gate the PR. (d) Zero fixed heights on text containers. | Screenshot diff CI job. |
| **BUG6** — expense data vanishing before save | Draft state held only in ViewModel memory → lost on process death / config change / interruption | (a) All in-progress entry state persists to a `draft_entry` Room row (or `SavedStateHandle` for small forms) on **every field change**, debounced 300 ms. (b) Unsaved entries are listed on the entry screen as an "Unsaved (n)" stack, newest first, and opening one restores it field-for-field (ADR-0013). The form itself opens empty — with several half-finished entries, auto-resuming one is the app guessing which. (c) `Don't keep activities` developer option is part of the mandatory manual test matrix. | Instrumented: fill form → `killProcess` → relaunch → assert draft restored field-for-field. |
| **BUG7** — app crashing | Unhandled exceptions in receivers/workers; `!!`; ANRs from main-thread I/O | (a) `Detekt` rule: `!!` is a build error outside tests. (b) `StrictMode` (thread + VM policies, `penaltyDeath` in debug) enabled in `Application.onCreate` for debug builds. (c) Global `Thread.setDefaultUncaughtExceptionHandler` → writes an encrypted crash record to `filesDir/crash/` → on next launch offers "Send diagnostics" (export to file, no network). (d) All `BroadcastReceiver` work delegated to `WorkManager` within 5 s. (e) Every `Worker` returns `Result.retry()` with backoff, never throws. | Crash-free-session target ≥ 99.5% across a 14-day dogfood window. |
| **BUG8** — data loss from schema migrations / key violations | Destructive fallback; missing migration; FK constraints failing mid-migration | (a) `room.schemaLocation` exported to `:core:database/schemas/`, **committed to git**. CI fails if a schema JSON changes without a new `Migration` class + a `MigrationTest`. (b) Every migration ships with a `MigrationTestHelper` test that seeds v(N-1) data and asserts v(N) content equality. (c) `PRAGMA foreign_keys=OFF` during migration, `PRAGMA foreign_key_check` after, abort+rollback on violation. (d) **Pre-migration auto-backup**: before any migration runs, a `.lfbk` snapshot is written and verified. If migration throws, restore automatically and surface a report. (e) Migrations are `INSERT INTO new SELECT ... FROM old` + `DROP` + `RENAME` — never `ALTER` chains that can half-apply. | Full migration chain test: v1 → vN with seeded data, run on every PR. |
| **BUG9** — text broken or clipped inside a control | A control's label allowed to wrap: a row of actions overflows its container and the last label breaks **mid-word** ("Delete" rendered as "Delet" above a lone "e"). Root causes: `Text` inside a button defaulting to `softWrap = true`; a fixed `Row` of three or more actions inside a card; labels that grow with the font scale while the card does not. Found on a real device in the category list — nothing caught it, because unit tests assert behaviour and previews were not being diffed. | (a) **Control labels never wrap.** `LfButton` (and every `Lf*` control carrying a label) renders it with `maxLines = 1, softWrap = false` and **no ellipsis** — "Delet…" is no more usable than a broken word. The control measures at its natural width instead. (b) **Containers wrap, not words.** Any cluster of two or more actions uses `LfActionRow` (a `FlowRow`), so an overflowing control moves to the next line whole. A bare `Row` of actions is the defect. (c) Enforced in `:core:designsystem`, never per screen — a rule each screen has to remember is a rule that is eventually forgotten. (d) The screenshot suite (§12) runs at fontScale 2.0, where almost any three-action row overflows a phone-width card. **Note the residual case:** `softWrap = false` converts overflow into *clipping* when a control is squeezed below its natural width, so a label that still does not fit is a content problem — shorten the label. That is what the 2.0x screenshot gate is for. | `Bug9_ControlLabelsNeverWrapTest` — renders the exact failing shape and asserts on the real `TextLayoutResult` (`lineCount == 1`, `hasVisualOverflow == false`) pulled through the `GetTextLayoutResult` semantics action. Asserting the *text content* would not have caught this: the string was always correct, only its layout was wrong. |

### 8.1 Pre-migration backup — operating design

BUG8(d) requires a verified `.lfbk` snapshot before any migration runs. The policy is right; without a design it collides with the ≤700 ms cold-start budget in §11, because a full encrypt-and-verify of a multi-year database is seconds of work on the app-open path.

| Concern | Resolution |
|---|---|
| **Where it runs** | Not on the cold-start path. A migration-pending state is detected before opening the database for normal use; the app routes to a dedicated **Upgrading** screen that owns the work. Cold-start budgets apply to the normal path and explicitly do not apply to a version-upgrade launch. |
| **User feedback** | The Upgrading screen shows determinate progress (bytes written / verified) and a plain-language explanation. It is **not cancellable** — a half-taken backup is worse than none — but it must never present as a frozen app. |
| **Low storage** | Required free space is checked as `2.2 × dbSize` before starting (snapshot + verification scratch + margin). On failure the migration **does not run**: the user is shown a "free up space to continue updating" screen with the exact figure needed. Blocking is correct here; migrating without a rollback path is what BUG8 is about. |
| **Backup fails** | Migration is aborted, the old database is left untouched, and a diagnostics report is offered. An unverified snapshot is never treated as a snapshot (§7, backup writer rule). |
| **Migration fails** | Restore the snapshot automatically, then surface the report. This is the only automatic restore path in the app. |
| **Retention** | The pre-migration snapshot is kept until the first successful post-migration launch, then folded into the normal 5-backup rotation. It is never the file that rotation deletes first. |

---

## 9. UI / UX Specification

### 9.1 Visual direction
Toshl-adjacent: soft, rounded, generous whitespace, colour used as *information* rather than decoration. **Semi-dark** as the default and primary theme (not OLED black — a warm-neutral dark that's easy on the eyes at night).

**Palette (dark, default):**
| Token | Value | Use |
|---|---|---|
| `surfaceBase` | `#15171C` | app background |
| `surfaceRaised` | `#1D2027` | cards, sheets |
| `surfaceOverlay` | `#252932` | dialogs, menus, elevated chips |
| `outline` | `#333846` | hairlines, dividers |
| `textPrimary` | `#E8EAF0` | |
| `textSecondary` | `#9AA1B4` | |
| `textTertiary` | `#7F8798` | |
| `accent` | `#6E8BFF` | primary actions, selection |
| `debit` | `#FF7A85` | expense amounts (muted coral, not alarm-red) |
| `credit` | `#5FD0A6` | income amounts (muted mint) |
| `warn` | `#F2B457` | budget warnings |

**Contrast is a gate, not a guideline.** §9.6 mandates WCAG AA, which is **4.5:1 for normal text** and 3:1 for large text (≥18.66 px bold / ≥24 px regular). `textTertiary` was originally `#6B7285`, which measures **3.73:1** against `surfaceBase` — it failed AA for body text while being specified as a text colour. It is now `#7F8798` (**4.97:1**). Every text-on-surface token pair is asserted by a unit test in `:core:designsystem` that computes the WCAG contrast ratio from the token values; a palette edit that drops a pair below its threshold fails the build rather than shipping. For reference the other pairs against `surfaceBase` are `textPrimary` ≈ 13.5:1 and `textSecondary` ≈ 6.9:1.

Light theme is a required mirror (same tokens, light values). Dynamic colour (Material You) is **off by default**, offered as an opt-in toggle — brand consistency wins for a finance app.

**Category colours** come from a curated 16-swatch palette, all WCAG AA against both surfaces. Never let users pick arbitrary colours that break contrast.

### 9.2 Typography
Single variable font family (Inter or Manrope, bundled — no runtime download). Tabular figures **mandatory** for all amounts (`FontFeature "tnum"`) so columns align.

Scale: `displayL 34/40` · `titleL 22/28` · `titleM 18/24` · `bodyL 16/24` · `bodyM 14/20` · `label 12/16` · `amountL 28/32 tnum` · `amountM 18/22 tnum`.

**Settled: Inter.** `InterVariable.ttf` from the Inter 4.1 release, unmodified, at `core/designsystem/src/main/res/font/`, ~859 KB against §11's provisional 15 MB budget. SIL Open Font License 1.1. Manrope was the other candidate and is a fifth of the size; Inter was chosen for the finance-specific features it carries beyond `tnum` — `zero` and `case` — and for the larger glyph set. Neither candidate has Arabic, so AED's "د.إ" falls back to the system font either way.

The OFL requires the licence to travel with every copy of the font, and an APK is a copy. It ships at `assets/licenses/Inter-OFL.txt` — **`assets/`, not `res/raw/`**, because release builds set `isShrinkResources = true` and an unreferenced raw resource is stripped, which would ship the font without its licence in release only, with nothing failing.

**Weights are cut in XML, not by `variationSettings`.** Compose's `Font(resId, …, variationSettings)` applies the axis but yields metrics systematically heavier than the master Inter actually draws. A `res/font/inter_*.xml` `<font-family>` carrying `android:fontVariationSettings` yields metrics identical to Inter's shipped static instances, which is the ground truth. Measured on device and pinned by `LfFontAxisTest`.

**Six weights are registered, not three.** The scale uses 400/500/600; 700/800/900 exist because Android's "Bold text" accessibility setting is a `fontWeightAdjustment` added to every requested weight before matching (`+300` on the primary device). With only three registered, all three requests match the heaviest entry and the type hierarchy collapses to a single weight for exactly the users who turned the setting on. The extra cuts come from the same file and cost no binary size. See §9.6.

`mnemonicWord` additionally enables Inter's `ss02` ("Disambiguation": `l` gains a tail, `1` a foot, `0` a slash). That style has always claimed to keep similar glyphs distinct; under the platform default there was no such feature to ask for. A recovery word transcribed wrong is the one copying error in this app with no recovery path of its own.

### 9.3 Navigation
- **Single Activity**, Navigation Compose with type-safe routes (`@Serializable` route objects).
- **Bottom bar (4 items) + centre FAB:** `Dashboard` · `Ledger` · **[ + FAB ]** · `Analytics` · `More`.
- FAB → expanding speed-dial: `Scan receipt` · `Manual entry` · `Inbox (n)`.
- **Top app bar:** collapsing large-title on Dashboard/Analytics, small on list screens. Actions: search, filter, period selector.
- **Tabs:** on Ledger (`Expenses | Income`), on Analytics (`Overview | Categories | Merchants | Trends`).

### 9.4 Component inventory (`:core:designsystem`)
`LfScaffold`, `LfTopBar`, `LfBottomBar`, `LfFab`, `LfCard`, `LfListItem`, `LfChip` (filter/assist/input), `LfSegmentedControl`, `LfTabRow`, `LfButton` (filled/tonal/outlined/text/inline), `LfIconButton`, `LfSwitch`, `LfCheckbox`, `LfTextField`, `LfAmountField` (editable, system keyboard — ADR-0012), `LfDateRangeBar`, `LfBottomSheet`, `LfDialog`, `LfSnackbar` (toast-equivalent — Material `Snackbar`, not `android.widget.Toast`), `LfEmptyState`, `LfShimmer`, `LfProgressRing`, `LfCategoryDot`, `LfMerchantAvatar`, `LfChart*`.

### 9.5 Motion
Spring-based, `MotionScheme` tokens. Durations: micro 120 ms, standard 240 ms, emphasized 400 ms. Shared-element transitions for card → detail. `AnimatedContent` for period switching. Predictive back supported on all screens. **Every animation respects `Settings.Global.ANIMATOR_DURATION_SCALE` and reduced-motion accessibility settings.**

### 9.6 Accessibility (not optional)
Min touch target 48 dp. Content descriptions on every icon-only control. Amounts announced as "spent 1,240 rupees on groceries", not "₹1240". Full TalkBack pass per release. Contrast AA minimum. Font scale to 2.0x without truncation or overlap.

**The font scale is not the only text setting that changes layout.** Android's "Bold text" accessibility option raises every requested font weight before font matching, and the primary test device has it on (`font_weight_adjustment = 300`). It is a real user setting, respected rather than worked around: §9.2 registers weights far enough up the scale that the hierarchy survives it. A manual pass with it enabled is on the matrix (`TESTING.md` H2), because no preview and no screenshot config exercises it.

**Decorative glyphs are pinned to their shape, not to the text scale.** `LfCategoryDot` draws a category's initial inside a 24 dp circle. Sized in `sp` the letter scaled while the circle did not, so at 2.0x its em box matched the circle's *diameter* and the curve clipped it on all four sides — visible on every row of the Ledger list. The initial carries nothing the adjacent label does not (it is excluded from semantics for exactly that reason), so it is sized from the swatch via `Dp.toSp()` and renders identically at every font scale. A row's *text* still scales in full, and degrades by wrapping the name rather than clipping it (§8/BUG9).

---

## 10. Charts

Use **Vico** (Compose-native, Canvas-based) or a hand-rolled Compose `Canvas` chart layer. Do **not** use MPAndroidChart (View-based, `AndroidView` interop, fights Compose recomposition and kills frame budget). Decision in ADR-005.

Requirements: 60fps pan/zoom on 5 years of daily buckets (~1,825 points) — achieved by pre-binning to the display resolution before handing data to the chart. No chart ever receives more points than it has horizontal pixels.

---

## 11. Performance Budget

| Metric | Target | Gate |
|---|---|---|
| Cold start (P50, mid-tier device) | ≤ 700 ms to first frame | Macrobenchmark, CI regression check ±10% |
| Warm start | ≤ 250 ms | Macrobenchmark |
| Frame rendering | **0 frames > 16.6 ms** during Ledger scroll (locked 60fps); on 120 Hz panels target 8.3 ms | Macrobenchmark `FrameTimingMetric`, P99 |
| Analytics screen (5Y range) | ≤ 300 ms to rendered chart | Instrumented timing test |
| SMS → notification latency | ≤ 1.5 s | Instrumented |
| OCR (single receipt page) | ≤ 2.5 s | Instrumented |
| APK size (arm64 split) | ≤ 15 MB | CI check |
| Memory (steady state) | ≤ 150 MB PSS | Macrobenchmark |

**The 15 MB budget is provisional and must be re-validated at P4.** Competing for it: SQLCipher's native libraries, a bundled variable font (§9.2 forbids runtime download), Compose, and — the real unknown — ML Kit Text Recognition v2. §2.2 named ML Kit without resolving **bundled vs unbundled**, and the two choices are not interchangeable here:

| | Bundled model | Unbundled (Play Services) |
|---|---|---|
| APK cost | several MB, paid by every install | negligible |
| Requires network | no | **yes**, to fetch models on first use |
| Requires Play Services | no | **yes** |
| Compatible with Law 6 / P3 | ✅ | ❌ |
| Compatible with `smsFull` sideloading | ✅ | ❌ on Play-Services-less devices |

The unbundled variant is incompatible with "no `INTERNET` permission in release" and with sideloaded distribution, so **the bundled model is the only option consistent with the rest of this spec** — and the budget must absorb it. If measurement at P4 shows 15 MB is unreachable with the bundled model, the budget moves, not the principle. Record the real figure in ADR-0010 and amend this table rather than quietly shipping over budget.

**Techniques (mandatory):**
- **Baseline Profiles** generated by `:benchmark` and shipped. Non-negotiable — this is the single biggest startup win.
- R8 full mode + resource shrinking in release.
- `LazyColumn` with stable `key = { it.id }` and `contentType`.
- All list/UI state classes annotated `@Immutable`; enable Compose strong-skipping; run the Compose Compiler stability report in CI and fail on new unstable params in hot composables.
- Paging 3 for all ledger lists. Never load a full ledger into memory.
- `derivedStateOf` for computed UI state; hoist lambdas to avoid recomposition churn.
- All DB work on `Dispatchers.IO`, exposed as cold `Flow`s, collected with `collectAsStateWithLifecycle()`.
- `JankStats` wired in debug builds, logging to the diagnostics screen.

---

## 12. Testing Strategy

| Layer | Tooling | Gate |
|---|---|---|
| Unit | JUnit5, Turbine (Flow), MockK (sparingly — prefer fakes), Kotest property tests for the parser | ≥80% line coverage on `:core:*` and all use cases |
| Parser | Golden-file corpus: `testdata/sms/*.txt` with expected JSON output. Every real-world SMS that ever fails becomes a permanent regression case. | 100% of corpus passes |
| OCR | Golden receipt images + expected line items, tolerance-based assertions | ≥90% item extraction recall on the corpus |
| DB migration | `MigrationTestHelper`, full v1→vN chain with seeded data | **Blocking.** No merge without it. |
| Backup/restore | Instrumented round-trip with row-level equality assertion | **Blocking on every PR.** |
| UI | Compose UI tests (`createAndroidComposeRule`), semantics-based selectors only | Critical flows: SMS→approve, OCR→approve, manual entry, export |
| Screenshot | Roborazzi/Paparazzi, 5 configs (phone/tablet × light/dark × fontScale 2.0) | Diff gate |
| Performance | Macrobenchmark (startup, scroll, baseline profile) | Regression gate ±10% |
| Manual matrix | `TESTING.md`: install-over-install, force-stop, "Don't keep activities", OTA update, airplane mode, low storage, permission revoke/regrant, 2.0x font, RTL | Pre-release checklist |

**Recursive-testing rule:** every bug fixed gets a test named after it (`Bug6_DraftSurvivesProcessDeathTest`). The bug table in §8 maps 1:1 to test classes. The suite only grows.

---

## 13. Delivery Phases

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0 — Foundation** | Modules, version catalog, DI, theme, encrypted Room + SQLCipher, key management (§7.2, **KEK-A + KEK-B only** — KEK-C since dropped, ADR-0011), 24-word phrase onboarding + word challenge + Recovery Kit, base-currency selection, backup/restore round-trip | See §13.1 — the criteria are restated there because the original "verified on a second device" is not achievable in the stated dev environment. |
| **P1 — Manual core** | Unlock flow wired (§7.3), Hilt, `:core:domain` + `:core:data`, schema v2, manual entry with draft persistence **and itemised entries (ADR-0018)**, categories/subcategories, merchants (addable from the entry form), payment methods, both ledgers, Ledger list with filters + Paging 3, CSV export, **`TransactionIngestSource` abstraction + `smsFull`/`playSafe` flavour skeleton (both compiling, both installable)** | Can fully use the app without SMS/OCR. Both flavours build in CI. |
| **P2 — Automated ingest** | Shared rule engine, `ParseIngestWorker`, cross-source dedupe, Inbox, notification actions, approve/discard — **plus both capture adapters: SMS receiver (`smsFull`) and `NotificationIngestService` (both flavours)** | 50-SMS + 50-notification golden corpus passing. Dedupe test: same UPI txn via both sources → exactly one pending row. |
| **P3 — Analytics** | Rollup table + worker, all chart views, filters, period comparison, budgets + alerts | 5Y query < 300 ms. |
| **P4 — OCR** | CameraX, file/PDF import, line-item extraction, review editor, category memory, attachments | ≥90% recall on receipt corpus. |
| **P5 — Polish & harden** | Baseline profiles, screenshot suite, a11y pass, XLSX export, diagnostics screen, Play listing + `playSafe` release track, API 37 readiness | All §11 budgets met. |

### 13.1 P0 exit criteria

The original wording required "phrase-only restore verified on a **second device**". The stated dev environment is **one** physical device and no local emulator, so that criterion could only ever be signed off dishonestly. What it was really testing is *key portability* — that a restore works where the original Android Keystore material is absent and a different Keystore/StrongBox implementation is in play. That is testable without a second phone:

1. **Backup → wipe → restore, green.** Instrumented. Seeds every table with fixture data, backs up, destroys **both** the database and the Keystore key, restores using only the 24 words, and asserts **row-level content equality** across every table — not row counts.
2. **Key portability, green.** The same round-trip executed where the Keystore entry is not merely deleted but was never present, and the restore runs against a Keystore alias generated fresh on that machine. This runs on the CI emulator matrix at **API 26 and 36**, which are genuinely different Keystore implementations — a stronger signal than two phones from the same vendor.
3. **Migration harness proven.** A throwaway v1→v2 migration demonstrates `MigrationTestHelper` end-to-end, then is reverted.
4. **`preMergeCheck` green on both flavours**, `smsFull` and `playSafe`.
5. **Manual leg on the physical device:** install-over-install, force-stop → relaunch, and one real onboarding through the word challenge.

A second physical device remains the ideal confirmation and stays on the pre-release manual matrix in `TESTING.md`. It is no longer a gate that blocks P0, because a gate nobody can pass is a gate that gets waived.

---

## 14. Architecture Decision Records

ADRs live in `docs/adr/NNNN-title.md`. Required before implementation:

| ADR | Decision | Status |
|---|---|---|
| 0001 | Flutter vs Native Compose | ✅ **Accepted** — Native Kotlin + Compose (§2) |
| 0002 | Separate tables vs partitioned single table for DEBIT/CREDIT ledgers | ✅ **Accepted** — one `ledger_entry` partitioned by a mandatory `ledger` column, read through per-ledger `@DatabaseView`s (§6.1) |
| 0003 | Key hierarchy & recovery model | ✅ **Accepted** — multi-wrap, phrase-primary (§7.2) |
| 0004 | XLSX generation library on Android | Open — needed by P5. Unblocked by nothing: the CSV export (ADR-0017) shares no writer with it |
| 0005 | Charting library | Open — needed by P3 |
| 0006 | Rollup strategy: incremental triggers vs worker-driven rebuild | Open — needed by P3 |
| 0007 | Ingest source strategy & Play distribution | ✅ **Accepted** — dual co-equal sources, flavour split at P1 (§3.1) |
| 0008 | Currency model | ✅ **Accepted** — single base currency, manual FX capture (§5.8) |
| 0009 | SQLCipher key rotation procedure | ✅ **Accepted** — two procedures: phrase re-wrap vs DEK sidecar rebuild (§7.7) |
| 0010 | Crypto library selection (Argon2id, BIP-39 wordlist, HKDF, Tink vs hand-rolled AES-GCM) | ✅ **Accepted** — platform primitives, hand-rolled RFC constructions, **zero new dependencies** |
| 0011 | KEK-C (optional passphrase wrap): implement at P1 or drop | ✅ **Accepted** — **dropped**; KEK-A + KEK-B permanently, no Argon2id, slot stays reserved (§7.2) |
| 0012 | Amount entry: in-app keypad vs system keyboard | ✅ **Accepted** — system keyboard, natural typing, integer-only parse (§5.4, §9.4) |
| 0013 | `draft_entry`: one in-flight entry per ledger vs many | ✅ **Accepted** — **many**, shown as a stack; supersedes D-06's uniqueness half (§6.1.2) |
| 0014 | Does Paging 3 reach `:core:domain`, or stop below it? | ✅ **Accepted** — `PagingData` on `LedgerRepository`; `:core:domain` takes `paging-common` (JVM-only) and **that artifact alone** (§5.5, CLAUDE.md §3) |
| 0015 | Must the bin obey §5.5's "separate lists"? | ✅ **Accepted** — **no**: one mixed chronological list, two queries underneath, no figure derived across rows. Amends §5.5 for that screen only (§5.5, CLAUDE.md §2 Law 2) |
| 0016 | Does hidden taxonomy get a restore, and may it be hard-deleted? | ✅ **Accepted** — yes to both. Hidden lists and restore for all three types; a purge behind a reassign-or-block rule **in code**, because `merchant_id` is `ON DELETE SET NULL` and `category_id` has no key at all. No schema change (§5.5, CLAUDE.md §7) |
| 0017 | What shape is the CSV export? | ✅ **Accepted** — faithful per-table dump; money and timestamps written twice (integer + rendered); `ledger_entry` split per book; soft-deleted rows included; file list driven by `BackupPayload` (§5.9, CLAUDE.md §2 Laws 2 and 3) |
| 0018 | What is an entry's category once its line items carry their own? | ✅ **Accepted** — **nothing**: an itemised entry stores no entry-level category, `line_item` is its only filing, and line categories are validated exactly as the entry's are. Rollups and budgets read line grain at P3 (§5.4, §5.6, `CLAUDE.md` §2 Law 2) |

**ADR-0003 is not reopened.** The kickoff listed it as a blocking decision, but §14 has it Accepted and §7.2 specifies it. The *design* — multi-wrapped DEK, phrase-primary — is settled and stays settled. What was genuinely open is the **library and implementation** choice underneath it, which is a different decision with different trade-offs (binary size, native dependencies, maintenance status) and therefore gets its own record: **ADR-0010**. Amending an accepted ADR to smuggle in a new decision is how decision logs stop being trustworthy.

`docs/adr/` also carries a template (`0000-template.md`). Every ADR states context, options considered, the decision, and consequences — including the ones we accept.

---

## 15. Git & CI/CD

### 15.1 Windows 11 → Linux CI compatibility (do this before the first push)

Your dev box is Windows; every CI runner is Linux. Four things silently break across that boundary:

| Hazard | Symptom | Fix |
|---|---|---|
| **CRLF line endings** | Golden SMS/receipt fixtures fail on CI but pass locally. Parser regex `$` anchors match differently. | `.gitattributes` with `* text=auto eol=lf`, `*.bat text eol=crlf`, and **`testdata/** -text`** (binary — preserve exact bytes). Set `git config --global core.autocrlf false`; let `.gitattributes` own it. |
| **`gradlew` loses its exec bit** | CI fails with `Permission denied` on `./gradlew`. | `git update-index --chmod=+x gradlew` once, commit. `.gitattributes` marks it `eol=lf`. |
| **Case-insensitive filesystem** | `LedgerEntry.kt` and `Ledgerentry.kt` coexist on Windows, collide on Linux. Import resolves locally, fails on CI. | CI job `case-collision-check` runs `git ls-files | tr A-Z a-z | sort | uniq -d` → fail if non-empty. |
| **260-char path limit** | Deep module paths + Room generated sources fail to check out. | `git config --global core.longpaths true` on the dev box. |

Also: **Git LFS** for `testdata/receipts/**` and `**/screenshots/**` — golden images bloat the repo and make diffs useless otherwise.

### 15.2 Branch model

Trunk-based. `main` is always releasable and protected.

```
main ──●──●──●──●──●──────●─────────► (protected, linear history)
        \        /        /
         feat/x─┘   fix/bug6─┘        (short-lived, squash-merged)
```

**Branch protection on `main`:** require PR, require `ci / pr-gate` to pass, require linear history (squash merge only), no force-push, no deletion. Dismiss stale approvals on new commits.

**Conventional Commits** (`feat:`, `fix:`, `perf:`, `refactor:`, `db:`, `chore:`) — enables auto-generated release notes and lets the `db:` prefix flag schema-touching PRs for extra scrutiny.

### 15.3 Secrets (GitHub → Settings → Secrets → Actions)

| Secret | Contents | Notes |
|---|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` | **Losing this means you can never update the app on Play. Store a copy offline, outside GitHub.** This is BUG3's root cause. |
| `RELEASE_KEYSTORE_PASSWORD` | | |
| `RELEASE_KEY_ALIAS` | | |
| `RELEASE_KEY_PASSWORD` | | |

`keystore.properties`, `local.properties`, and `*.jks` are gitignored and must **never** be committed. A CI job greps for them and fails the build if they appear.

### 15.4 Pipeline

Two workflows: `ci.yml` (every PR + push to `main`) and `release.yml` (tag-triggered).

| Job | Runner | Blocking | What it catches |
|---|---|---|---|
| `guards` | ubuntu | ✅ | Case collisions, committed secrets, **schema-immutability violation**, non-monotonic `versionCode` |
| `static-analysis` | ubuntu | ✅ | Detekt (incl. the `!!` ban), ktlint, Android Lint, banned-API check (`Float` money, `cacheDir`, `fallbackToDestructiveMigration`) |
| `unit-test` | ubuntu | ✅ | Both flavours × debug. Parser golden corpus. Kotest property tests. |
| `screenshot` | ubuntu | ✅ | Roborazzi/Paparazzi diffs — **BUG5**. JVM-only, no emulator needed. |
| `instrumented` | ubuntu + KVM emulator, matrix API **26 / 36** | ✅ | **Migration chain (BUG8)**, **backup→wipe→restore round-trip (BUG4)**, draft-survives-process-death (BUG6), approval-transaction integrity, cross-source dedupe |
| `compose-stability` | ubuntu | ⚠️ warn | New unstable params in hot composables |
| `assemble` | ubuntu | ✅ | Both flavours build; APK size budget (≤15 MB arm64) |
| `benchmark` | **self-hosted (your Win11 box + phone)** | manual / nightly | Startup, scroll jank, baseline profile. **Emulator numbers are noise — this must run on real hardware.** |

**Why `instrumented` is non-negotiable:** the migration test and the backup round-trip are the only automated things standing between you and BUG4/BUG8. If emulator jobs get flaky and someone marks them `continue-on-error`, the entire durability guarantee in §7 becomes decorative.

### 15.5 The schema-immutability guard

Committed Room schema JSONs are **append-only**. The guard enforces:

1. An existing `core/database/schemas/**/N.json` was **modified** → **fail**. Schemas are immutable once shipped; editing one means a released build's migration path no longer matches reality.
2. A new `N.json` was **added** → require a `Migration_${N-1}_${N}` class **and** a test referencing it in the same PR, else **fail**.
3. `fallbackToDestructiveMigration` appears anywhere outside `dev` source sets → **fail**.

Implemented in `scripts/guard-schema.sh`, run by the `guards` job.

### 15.6 versionCode monotonicity guard (BUG3)

`version.properties` holds a single monotonic integer, auto-incremented by a Gradle task and committed. CI reads the `versionCode` from the latest `v*` git tag and fails if `HEAD`'s is not strictly greater. Combined with the runtime `AppVersionGuard` (§8, BUG3), a downgraded install is caught in two independent places.

### 15.7 Release flow

```
git tag v1.4.0 && git push origin v1.4.0
   → release.yml
   → decode keystore from secrets → assemble + bundle BOTH flavours (release, signed)
   → run the full instrumented suite against the RELEASE build
   → generate + attach: smsFull APK, playSafe AAB, mapping.txt, baseline profile
   → auto-generate release notes from Conventional Commits
   → publish GitHub Release
   → keystore material scrubbed from the runner (`always()` cleanup step)
```

`mapping.txt` is attached to **every** release — without it, R8-obfuscated crash reports from §8/BUG7 are unreadable.

### 15.8 What CI cannot cover (your manual matrix)

Automation stops at the boundary of real-device behaviour. `TESTING.md` remains mandatory pre-release, specifically for:
- OTA / OS update survival (**BUG2**) — no runner can simulate a kernel update.
- Install-over-install across signing configs (**BUG1**).
- OEM battery-killer behaviour on the `NotificationIngestService`.
- Real bank SMS and real UPI notifications (any that fail become permanent golden fixtures).
- Physical-device 60fps feel — `benchmark` gives numbers, your eyes give the verdict.

---

## 16. Open Questions

1. **Raw-message retention:** default 90-day purge of `sms_raw` / `notification_raw` bodies — acceptable, or keep indefinitely to improve parser rules? (Trade-off: parser quality vs. sensitive-data footprint.)
2. **OCR language:** Latin script only, or bundle the Devanagari ML Kit model (+~2 MB)?
3. **Tablet/foldable:** adaptive two-pane layouts in v1, or phone-only?
4. **Notification package allowlist:** ship a curated default list (GPay/PhonePe/Paytm/major banks) or start empty and let the user add packages from a "recently seen" picker?
5. **Attachment retention:** keep receipt images forever (storage growth) or offer auto-downscale/purge after N months?

Added in v0.3.0 — gaps found during the Phase 0 spec audit and deliberately *not* decided unilaterally:

6. ~~**`draft_entry` has no schema.**~~ **CLOSED — D-06.** One row per in-flight entry, keyed by a client-generated UUIDv7, uniqueness scoped by `UNIQUE(ledger, editing_entry_key)`, form state carried as a versioned JSON payload. Table in §6.1, reasoning in §6.1.2. Lands in schema v2.
7. **`pending_line_item` is elided** as `(...)` in §6.1. It needs a real definition before P2/P4. Presumably mirrors `line_item` minus `entry_id`, plus `pending_id` — but "presumably" is not a schema.
8. ~~**Recovery Kit is written in plaintext.**~~ **CLOSED — D-07.** Plaintext as originally specified, gated behind an explicit confirmation naming what the file is and where it is going. The password-protected PDF was rejected for reintroducing a user-chosen secret into the recovery path. Reasoning in §7.2.
9. ~~**Is `app_meta.canary` load-bearing?**~~ **CLOSED — D-08.** Kept, with its purpose restated honestly: it detects a DEK/database *mismatch* (bad restore, half-applied rotation), not a wrong key — SQLCipher's HMAC gets there first. Reasoning in §7.3.
10. **Confirm the APK budget at P4.** §11 now records 15 MB as provisional pending a real measurement with bundled ML Kit. The number needs to be either defended or moved on evidence.

Added in v0.5.0 — found while wiring the unlock flow at P1:

11. **§7.3 step 3 has no entry point at first run.** Steps 1 and 2 (Keystore, then the 24 words) are implemented and verified on device. Step 3 — "restore from a `.lfbk` backup file", and the type-DELETE "start fresh" dialog — is only reachable from the Recovery screen, which itself is only reachable when a phrase wrap already exists on disk. A user reinstalling after a factory reset has **no wrap and no database**, so `openOnLaunch` routes them to onboarding and hands them a *new* phrase; there is no "I already have a recovery phrase and a backup file" branch in the §7.4 gate. `DatabaseBackupManager` can already do the restore, so this is a missing route rather than missing machinery. Sub-question: does the branch belong on the first onboarding screen (discoverable, but adds a fork to the one flow §7.4 deliberately keeps linear), or behind a quieter affordance? Needed before the first release, not before P1 ships.

Added while building the `TransactionIngestSource` abstraction at P1:

12. **`RawIngestEvent.sender` is undefined for a notification.** §3.1 gives every event a non-null `sender`, and §5.2 says the rule engine matches notifications on `packageName` instead — which leaves `sender` with no specified meaning on that half of the split. Three readings are plausible and they are not interchangeable: the posting app's user-visible label ("Google Pay"), the package name repeated, or the notification's `EXTRA_TITLE` (often the bank's name, sometimes the merchant's). It is persisted in `notification_raw`, it is what the Inbox shows as the origin of a pending row, and `merchantNormalized` in the §3.1 dedupe key can be derived from the wrong one. Nothing forces the answer yet — the P1 notification adapter captures nothing — so it is open rather than decided. Needed before the first notification is read at P2.
