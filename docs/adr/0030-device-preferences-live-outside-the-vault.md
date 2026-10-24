# ADR-0030 — Device preferences live outside the vault, beside ADR-0020's store

- **Status:** Accepted
- **Date:** 2026-10-24
- **Deciders:** Swaroop (owner), lead engineer
- **Supersedes / Superseded by:** Reopens the scope of ADR-0020 (does not supersede it; its four listener/setup keys and three capture-gap keys stand unchanged).
- **Spec sections touched:** `SPEC.md` §9.3, §9.5; `docs/V2-PLAN.md` §3.2, §3.3, §3.5

## Context

V2-3 adds Settings pages whose choices must take effect **before the vault
opens**: the theme paints the unlock and Recovery screens, the start screen is
chosen the instant the vault opens, and the app lock (V2-3, §3.5) must know
whether it is on before there is anything to gate. Number grouping, date order,
the screenshot block and hide-amounts follow the same shape.

The vault cannot hold them: it is unavailable in exactly the states those
screens exist for (onboarding, Recovery, a wrap that is gone), and an app lock
that had to open the vault to learn whether to lock would have it backwards.

ADR-0020 built the only out-of-vault store, and scoped it to "operational
metadata about the app's own machinery". Its "What would make us revisit this"
names this case: *a proposal to put user-meaningful data in this store (reopen
rather than widen)*. These are the user's choices, not machinery, so this ADR
reopens the question rather than adding names to ADR-0020's list.

## Options considered

### Option A — `app_meta`, inside the vault

Backed up with everything else, so a restore would bring the theme along. But
nothing applies before unlock: the first screens draw in the phone's theme and
then switch, and the app lock cannot read its own setting before the vault it is
meant to gate. Rejected on availability, the same ground ADR-0020 rejected it on.

### Option B — a separate `:core:preferences` module

Same storage technology as `:core:datastore`, with a module boundary between
"machinery" and "choices". It leaves ADR-0020's text untouched, at the cost of a
second module, a second Hilt module and a second key-surface guard for what is
one more DataStore file. The boundary it draws is real but is drawn just as well
by a file and a rule.

### Option C — `:core:datastore`, a separate file, a narrower admission test

The module ADR-0020 created, holding each concern in its own preferences file
(`appearance`, and later files per page), with its own admission rule below and
the same mechanical guard.

## Decision

**Option C** (owner, 2026-10-24). User preferences may live in `:core:datastore`
when **all three** hold:

1. **Worth nothing to someone who stole the file.** No amounts, merchants,
   categories, message content, package names, or anything derived from them. An
   enum name is fine; a string the user typed is not.
2. **Needed before, or regardless of, the vault opening.** A preference that
   only matters once the vault is open belongs in the vault.
3. **Device-local by nature.** They do **not** travel in a `.lfbk`; a restore
   onto a new phone starts on the defaults. For display choices that is a
   tolerable loss, and for the app lock it is the correct behaviour (a lock
   setting must not arrive on a device whose owner did not set it).

ADR-0020's rule is unchanged for its own keys. This ADR adds a second class of
key, not a looser reading of the first.

### The permitted key surface (this ADR's share)

| Key | File | Why it qualifies |
|---|---|---|
| `appearance_theme` | `appearance` | `SYSTEM`/`LIGHT`/`DARK`. Paints the unlock screen. |
| `appearance_animations` | `appearance` | `FULL`/`REDUCED`/`OFF`. Applies from the first frame. |
| `appearance_start_screen` | `appearance` | `HOME`/`LEDGER`/`ANALYTICS`. Chosen when the vault opens. |
| `region_number_grouping` | `region` | `BY_CURRENCY`/`LAKH`/`WESTERN`. Groups amounts on the unlock screen and in notifications posted with no Activity. |
| `region_date_order` | `region` | `AUTO` and three explicit orders. Same reach as the grouping. |

The base currency is **not** here: it lives in the vault (`app_meta`) because
every stored amount is in it, and it fails test 3 — it must travel with the
data it labels.

Later V2-3 pages amend this table in the commit that adds their keys.

## Consequences

**Easy:** every V2-3 preference is readable in any vault state, from a plain
`Flow`, with no unwrap.

**Hard:** backup is less total again — `ExportCoversEveryTableTest` already
does not mean "every persisted byte" (ADR-0020), and now a restore also resets
appearance. And the store has two admission rules, so a reviewer has to know
which one a key is claiming.

**Forever:** `DatastoreKeySurfaceTest` lists every key by hand, and a new key
means coming to it and to the right ADR's table.

**Revisit if:** a preference fails test 1 (it belongs in the vault, or needs
encryption here); or `.lfbk` is extended to carry device state, at which point
both stores need one restore path.

## Verification

- `DatastoreKeySurfaceTest` (`:core:datastore`): the keys above, by name,
  in both directions. `RegionConventionsParseTest` does for `region` what the
  appearance test does for its file.
- `AppearancePreferencesParseTest` (`:core:datastore`): an empty file, an
  unknown name (a newer build's file after a downgrade) and every value read back
  without throwing, because this read paints the first frame.
