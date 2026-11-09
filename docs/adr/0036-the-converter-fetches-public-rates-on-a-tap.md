# ADR-0036 — The converter fetches public exchange rates on a tap, and an entry's rate stays the user's

- **Status:** **Accepted** (owner, 2026-11-09). Docs first; the code lands with
  V2-7's Utilities PR.
- **Date:** 2026-11-09
- **Deciders:** Swaroop (owner, chose "fetch on tap" and each decision below), lead engineer
- **Amends:** **Law 6** (`CLAUDE.md` §2), as ADR-0034 left it: a second kind
  of request is allowed, for a public table that carries nothing about the
  user, and the network lives in two modules instead of one. **`CLAUDE.md` §0's
  Currency row**: "no FX lookup" becomes "no FX lookup *for an entry*".
  **`SPEC.md` §5.8**'s claim that a live lookup violates P3.
- **Stands:** ADR-0034 in full. ADR-0021's permission pin.
- **Settles:** `docs/V2-PLAN.md` decision **D2**. The plan reserved ADR-0030 for
  it; that number went to device preferences, and 0036 is the next free.
- **Spec sections touched:** `SPEC.md` §1.2 (P3), §5.8, §14; `docs/V2-PLAN.md`
  §1 (D2), §4.4; `CLAUDE.md` §0, §2 (Law 6), §3, §7, §9.

## Context

The owner's v2 brief asked for a currency converter with real-time rates
(V2-PLAN §4.4). Two rules stood in the way, checked against `main` at
`8e258c49`:

- **`CLAUDE.md` §0:** "rates are user-entered, never fetched".
- **Law 6 as ADR-0034 rewrote it:** the only thing that leaves the phone is an
  encrypted backup copied to the user's own Drive, and every network call lives
  in `:core:drive`. `bannedApiCheck` fails on a JDK network API outside
  `core/drive/src/main/`. `moduleDependencyRuleCheck` lets only
  `:feature:settings`, `:feature:onboarding` and `:app` depend on `:core:drive`.

One thing is **not** in the way: `android.permission.INTERNET` is already in
all four variants of `EXPECTED_MERGED_PERMISSIONS` (ADR-0021). A rate fetch
adds no permission and needs no new dependency.

V2-PLAN's recommendation, written before ADR-0034, was a split: the ledger rule
stays, and a utility converter may fetch a public rate table. Three options
were put to the owner on 2026-11-09:

| | A: no fetch | B: fetch on tap (converter only) | C: B, plus a suggested rate on the entry form |
|---|---|---|---|
| What it is | The user types the rate; the app remembers the last one per currency | One fixed public address, fetched only when the user asks | B, and the entry form offers the fetched rate for the user to confirm |
| Sent | Nothing | The request only: no parameters, no account | Same as B |
| Cost | The decision written down | This ADR, a new module, guard changes, Law 6, SPEC and privacy wording | All of B, and a fetched number one tap from `fx_rate_micro` |

**The owner chose B.** C was declined: the figure the user's bank charged,
markup included, is the correct one for an entry (§5.8), and a reference rate
sitting one tap from it invites the wrong one.

## Decisions (owner, 2026-11-09)

| | Question | Chosen |
|---|---|---|
| a | May the converter fetch rates | **Yes, on a tap only** |
| b | May anything fetched reach an entry | **No.** `fx_rate_micro` stays what the user types (§5.8) |
| c | Without a fetch | **A typed rate always works**, offline included |

## Design

### 1. What is fetched, and from where

**The European Central Bank's euro foreign exchange reference rates**, the
daily table at one fixed address:
`https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml`.

- **Why the ECB:** a public body, no account and no API key, a stable format
  for two decades, and it publishes once per working day, so there is nothing
  to poll. Its own terms say the rates are for information only, which is the
  label this app gives them anyway. The table covers about thirty currencies
  against the euro, INR among them.
- **Cross rates go through the euro.** USD to INR is INR per euro divided by
  USD per euro. All of it is `Long` arithmetic in micro-units (rate × 10⁶, the
  same scale as `fx_rate_micro`), rounded half up once at the end. The rate is
  a ratio, not money, so Law 3 does not ban a `Double` here, but micro-units
  keep it on the scale the rest of the app uses. A converted **amount** goes
  through `Money`.
- **A currency the table lacks** is converted only with a typed rate, and the
  screen says the ECB does not publish it.
- **The format is checked in the build PR**, against the live file and a
  committed copy used by the tests. If the address or format has changed by
  then, this ADR is amended before the code is written.

### 2. What the request carries

- **Nothing about the user.** A `GET` of the fixed address: no query string, no
  cookie, no account, no device id.
- **A fixed `User-Agent` of `LedgerFlow`.** Android's default names the
  phone's model, its build and the Android version.
- **Redirects are not followed.** A redirect to another host is a failure, not
  a second request.
- **HTTPS only, with the system's trust store.** No certificate pinning: the
  ECB's certificate is not this app's to track.
- **The response is capped** (64 KiB; the table is about 2 KiB) and parsed
  strictly. Anything unexpected fails, and the last good table stays in use.

**The ECB sees** the phone's IP address, the time, and that it was LedgerFlow
asking. **Nothing else leaves:** no amount, no currency the user picked, nothing
from the vault.

### 3. When it runs

**Only when the user taps "Get today's rates"** in the converter. No background
fetch, no fetch on opening the screen, no worker, no retry loop. The converter
opens on the cached table, or on typed rates when there is none.

### 4. Where it lives: `:core:rates`

A new module, not `:core:drive`. That module's contract is narrow (it is handed
sealed files and names and talks to Drive), and it carries Play services. A
rate table has a different host, a different purpose and different failure
modes.

- **`bannedApiCheck`** allows network APIs in `core/drive/src/main/` and
  `core/rates/src/main/`, nowhere else.
- **`moduleDependencyRuleCheck`:** only the feature module that holds the
  converter (named in the Utilities PR) and `:app` may depend on `:core:rates`.
  `:core:rates` may depend on `:core:model` and `:core:common` only. It may not
  depend on `:core:data`, `:core:database`, `:core:crypto` or `:core:drive`, so
  it cannot compile a reference to the vault, a key or the user's Drive.
- **`EXPECTED_MERGED_PERMISSIONS`** stays as it is, since nothing new is
  merged. The build PR confirms it.

### 5. The cache, and the label

- **The last good table is kept in `filesDir/rates/`** (Law 5), with the date
  the ECB stamped on it. It is public data, not the user's, so it is not in the
  vault and not in a backup.
- **Every fetched figure is labelled indicative, with its date**, for example
  "Indicative · ECB reference rate of 7 Nov 2026". It is the ECB's date, not
  the time of the tap, because a table fetched on a Sunday is Friday's.
- **A typed rate is labelled as the user's** ("Your rate").

### 6. What the converter never does

- **It never writes into an entry.** There is no "Use as amount" on the
  converter. The calculator has one, and it carries only what the user typed.
- **It never fills `fx_rate_micro`**, never offers a rate on the entry form or
  at review, and never converts a parsed foreign amount. §5.8's `needsFxInput`
  rule stands: the review asks for the base-currency amount the bank charged.

## Law 6, rewritten

> 6. **Nothing this app computes touches a network.** Parsing, OCR, analytics and
> encryption are on-device. No user data leaves the phone **except files
> already encrypted on it, copied to the user's own Google Drive when they turn
> that on** (ADR-0034). The one other request is for **a public exchange-rate
> table, when the user asks for it in the converter, and it carries nothing
> about them** (ADR-0036). Every network call lives in `:core:drive` or
> `:core:rates`, and `bannedApiCheck` fails on a network API anywhere else. If
> you think *other* code needs the network, you're wrong: raise it as an ADR
> instead.

## Tests (in the build PR)

- **The request:** against an in-process HTTP server from the JDK, as
  `:core:drive`'s tests do: a `GET` with no query string and no cookie, the
  fixed `User-Agent`, no redirect followed, an oversized or malformed response
  refused with the cached table kept.
- **The arithmetic:** cross rates and conversions in micro-units against worked
  examples from a committed copy of the table, including a currency the table
  lacks and the euro itself.
- **The boundary:** a planted network import outside the two modules fails
  `bannedApiCheck`, and a planted dependency on `:core:database` from
  `:core:rates` fails the module rule.
- **Nothing reaches an entry:** no path from `:core:rates` to the entry form's
  draft or to `ApproveTransactionUseCase`, by the module rule.
- **A device check in `TESTING.md`:** a tap with the network on fetches and
  labels the table; with the network off, the cached table and typed rates
  still work.

## Consequences

- **Gained:** a converter with current reference rates, at the user's tap.
- **Given up:** "the app talks only to the user's Drive" stops being true. The
  ECB learns that someone at this IP address uses LedgerFlow, and when they
  convert.
- **Owner's, outside the code:** the privacy policy and the Data permissions
  sentence for `INTERNET` describe the rate request. Play's Data safety answers
  should not change, since no user data is collected or shared, but the owner
  confirms that before a Play release.
- **Reopening this is cheap.** Removing the fetch leaves a converter with typed
  rates and deletes one module.
