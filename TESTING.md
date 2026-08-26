# TESTING.md — the manual matrix

The pre-release gate for the things CI structurally cannot reach.

`CLAUDE.md` §11 and `SPEC.md` §15.8 both name this file. It is not a
nice-to-have checklist and it is not a substitute for the automated suite: it
exists because a GitHub runner cannot install an OTA update, cannot be killed by
a Samsung battery optimiser, and cannot tell you whether a list *feels* like
60fps. Everything here needs a physical device and a person.

**A release does not ship on a partial run.** A row that could not be run is not
a row that passed — record it as *blocked* and say why.

---

## 0. Before a run

| | |
|---|---|
| **Build** | The exact artefact you intend to release, installed the way a user would get it. Not a fresh `installSmsFullDebug` off your working tree. |
| **Device** | At least the primary: Samsung SM-S721B (Galaxy S24 FE), Android 16 / API 36. A second device with a different OEM skin is worth more than a second pass on the first. |
| **State** | A vault with **real-shaped data** in it — several weeks of entries across both books, a few categories, at least one itemised entry, something in the bin. An empty app passes almost everything here. |
| **`adb`** | `C:\Users\swaro\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Confirm with `adb devices -l` first; the device drops off when the screen locks. |

**Never `adb uninstall`.** It destroys the vault and masks BUG1 — which is one
of the things this matrix exists to catch. Every upgrade row below is
install-over-install on purpose. If a row genuinely needs a clean install, it
says so, and it says to use a device or user profile that is not carrying data
you want.

**The 24-word recovery phrase is handled by the repository owner and nobody
else.** Rows that need it are marked **[owner]**. Do not photograph it, do not
paste it into a terminal, do not let a screen recorder run during those steps.
An agent running this matrix stops at an **[owner]** row and hands it over.

### `playSafe` on device

Building and installing `playSafe` for device testing is **deferred by owner
instruction** until Play distribution is actually on the table. That does not
relax `preMergeCheck`, which builds and tests both flavours on every PR — that
is CI parity. The rows below marked *(playSafe)* are therefore blocked-by-choice
rather than skipped, and they become live the day Play distribution does.

---

## 1. How to read a row

| Mark | Meaning |
|---|---|
| **P1** | Live now. Run it. |
| **P2** / **P3** / **P4** | The feature it tests has not shipped. The row is written now so the phase that ships the feature cannot ship it without a manual check — the failure this file exists to prevent is a durability guarantee arriving with no way to verify it. |
| **[owner]** | Involves the recovery phrase. Owner runs it. |

A row is **pass** only if you watched the stated observation happen. "Nothing
looked broken" is not an observation.

---

## 2. Install, upgrade and version (BUG1, BUG3)

| # | Phase | Test | Steps | Expected |
|---|---|---|---|---|
| A1 | P1 | **Install over install, same key** | Install release build. Add an entry. Install the next release build over it without uninstalling. | App opens straight to Home. The entry is still there. No Recovery screen. |
| A2 | P1 | **Debug and release coexist** | With a release build installed and holding data, `installSmsFullDebug`. | Two separate app icons. The debug app has its own empty vault; the release app's data is untouched. This is `applicationIdSuffix ".debug"` working (BUG1(b)) — if the release app's data changed, stop the release. |
| A3 | P1 | **Downgrade is refused, not absorbed** | Attempt to install an APK with a lower `versionCode` over the installed one. | Android refuses the install. The app is not left half-updated. Never resolve this with an uninstall. |
| A4 | P3 | **Downgrade guard screen** | Forge a higher `lastSeenVersionCode` in `app_meta`, relaunch. | `AppVersionGuard` blocks writes and shows the downgrade screen (BUG3(c)). No automatic downward migration. Row activates when `AppVersionGuard` ships. |
| A5 | — *(playSafe)* | **Flavour coexistence** | Install `smsFull` and `playSafe` release builds together. | Separate application ids, separate vaults, neither disturbs the other. Deferred by owner instruction (§0). |

---

## 3. OS-level survival (BUG2)

This block is the reason the file exists. No runner reproduces any of it.

| # | Phase | Test | Steps | Expected |
|---|---|---|---|---|
| B1 | P1 | **OTA / OS update survival** | With data in the app, take a real system update (or a full OEM firmware update) and relaunch. | Every entry, category and merchant is intact. No Recovery screen. This is BUG2 and it is the single highest-value row here. |
| B2 | P1 | **Reboot** | Add an entry, reboot the device, relaunch. | Entry present. The WAL was checkpointed on `ON_STOP`, so nothing was left only in `-wal`. |
| B3 | P1 | **Force-stop mid-write** | Start typing an entry, and while the draft is saving `adb shell am force-stop com.ledgerflow`. Relaunch. | App opens normally. The draft appears in the unsaved stack, restored field-for-field (BUG6). No corruption, no Recovery screen. |
| B4 | P1 | **Low-memory kill** | Open the entry form with a part-filled draft. Background the app, open several heavy apps until it is killed. Return. | Draft restored from `draft_entry`, not from memory. |
| B5 | P1 | **App hibernation / auto-revoke** | Leave the app unused past the OS's unused-app window, or trigger "Pause app activity if unused" manually from App info. Relaunch. | Data intact. Permissions may need regranting (see I2); data must not. |
| B6 | P2 | **OEM battery killer** | With notification ingest running, put the app under the OEM's aggressive battery mode (Samsung: "Deep sleeping apps"). Leave it overnight. Send a test UPI notification. | Either the notification is captured, or the app has surfaced that ingest is suspended. Silent non-capture is a fail — this is what "OEM battery-killer behaviour on `NotificationIngestService`" in §15.8 means. |

---

## 4. Process death and lifecycle (BUG6)

| # | Phase | Test | Steps | Expected |
|---|---|---|---|---|
| C1 | P1 | **Don't keep activities** | Enable Developer options → "Don't keep activities". Walk the whole app: Home, Ledger (both books), an itemised entry, Categories, Export, the bin. | No screen loses state, no crash, no duplicated dialog. Turn the setting back off afterwards. |
| C2 | P1 | **Rotation and multi-window** | Rotate on every screen; try split-screen. | No overlap, no clipped label, no lost draft. |
| C3 | P1 | **Predictive back** | Use the back gesture from every screen, including mid-dialog. | Predicted target is the one you land on. No dialog leaks past its screen. |
| C4 | P1 | **Draft survives everything** | Fill an entry form partially. Rotate, background, force-stop, relaunch. | The draft is in the unsaved stack. It does **not** auto-open — the form opens empty by design (ADR-0013). |

---

## 5. Encryption, recovery and backup (BUG4, SPEC §7)

| # | Phase | Test | Steps | Expected |
|---|---|---|---|---|
| D1 | P1 | **Cold unlock** | Force-stop, relaunch. | Unlocks from the Keystore-wrapped DEK with no prompt. Time it: this is on the cold-start path. |
| D2 | P1 **[owner]** | **Onboarding gate is not skippable** | Fresh install on a spare device or profile. Walk onboarding. | There is no skip on the word challenge, and no "remind me later" anywhere. If you find one, that is a release blocker (SPEC §7.4). |
| D3 | P1 **[owner]** | **Recovery from the phrase alone** | On a spare device or profile: seed data, take a `.lfbk`, wipe the app data *and* the Keystore key, then recover using only the 24 words. | Every row comes back. The app never offers to wipe on failure — it routes to Recovery (SPEC §7.3). |
| D4 | P1 | **Decryption failure routes to Recovery, never to a wipe** | Corrupt the Keystore key (e.g. change the device lock in a way that invalidates it) and relaunch. | Recovery screen. The words "reset", "start over" and "erase" do not appear on it. Data still on disk. |
| D5 | P1 **[owner]** | **Recovery Kit warning is honest** | On the Recovery Kit step, choose "Save as text file". | The dialog says the file is plaintext, says what it grants, and says where it is going, *before* the picker opens (D-07). Then open the saved file and confirm it contains what the dialog said it would. |
| D6 | P3 | **Nightly backup actually runs** | Grant a backup folder at onboarding. Leave the device overnight on charge. | A verified `.lfbk` appears in the chosen folder and `lastBackupAt` advances. Row activates when `BackupWorker` ships — it does not exist yet, so the onboarding grant currently has no consumer. |
| D7 | P3 | **Backup failure is loud** | Revoke the SAF grant on the backup folder, then let a backup run. | A persistent notification, and a Dashboard warning once `lastBackupAt` is over 7 days old. Silent failure is BUG4's exact shape. |
| D8 | P3 | **Key rotation across a crash** | Trigger a rotation and kill the process during the swap. Relaunch. | Rotation resumes from `.rotating.old` and completes; no data loss. Required by ADR-0009, which says explicitly that CI does not reproduce crash-during-swap. |

---

## 6. Destructive operations

`PurgeDeletedEntriesUseCase` is the only irreversible operation in the app.
These rows are run on a **disposable vault**, never on real data.

| # | Phase | Test | Steps | Expected |
|---|---|---|---|---|
| E1 | P1 | **Purge is confirmed and counted** | More → Deleted entries → erase everything. | A `Warning` dialog that names the actual count. It does not offer to back up first — the app never holds the phrase, so it cannot (ADR-0011); it tells you to export. |
| E2 | P1 | **Purge leaves a readable vault** | Complete a purge of a reasonable number of rows. Force-stop. Relaunch. | The app opens normally. The `VACUUM` rewrote the whole encrypted file; a mistake there does not fail loudly, it surfaces here as an unreadable vault. |
| E3 | P1 | **The bin shows both books and erases the right one** | Delete one expense and one income. Open the bin. Erase only the expense. | Both were listed (ADR-0015). The income is still in the bin and still restorable. |
| E4 | P1 | **Restore puts it back in its own book** | Restore the income entry. | It reappears under Income, not Expenses, with its original amount and date. |

---

## 7. Ingest (P2)

Every row here is P2. They are written now because SPEC §15.8 requires real
messages in the loop, and because a parser that has never met a real bank SMS
has not been tested.

| # | Phase | Test | Expected |
|---|---|---|---|
| F1 | P2 | **Real bank SMS** | A genuine debit SMS from a real bank produces a `PENDING` row. Nothing reaches the ledger without a tap (Law 1). |
| F2 | P2 | **Unparseable SMS is still captured** | An SMS from an allowlisted sender that the rules cannot parse produces a `PENDING` row with `confidence = 0`. It is never silently dropped. |
| F3 | P2 | **Real UPI notification** | A genuine GPay/PhonePe notification produces a `PENDING` row. |
| F4 | P2 | **Cross-source dedupe on a real payment** | One real UPI payment that fires both a bank SMS and an app notification produces **one** pending row, with the other visible under "Suppressed". |
| F5 | P2 | **Non-allowlisted package is never read** | A notification from a package outside the allowlist leaves nothing in `notification_raw` and nothing in the logs. This is a stated privacy guarantee, not an implementation detail. |
| F6 | P2 | **Every failure becomes a fixture** | Any real message that fails to parse is added to `testdata/` as a golden fixture before the fix. The corpus only grows. |

---

## 8. Permissions (D-04, Law 6)

| # | Phase | Test | Expected |
|---|---|---|---|
| G1 | P1 | **No network, ever** | Put the device in airplane mode and use the entire app. Nothing degrades, nothing waits, nothing reports an error. The release manifest has no `INTERNET`; this confirms nothing behaves as though it did. |
| G2 | P2 | **`RECEIVE_SMS` in `smsFull` only** | App info → Permissions on the `smsFull` build lists SMS. `restrictedPermissionCheck` guards the manifests; this confirms what the OS actually granted. |
| G3 | P2 | **Revoke and regrant** | Revoke SMS (and notification access) from Settings while the app runs, then regrant. No crash, no lost data, and ingest resumes without a reinstall. |
| G4 | P2 | **Notification listener survives a reboot** | Grant notification access, reboot, send a test notification. Still captured. |

---

## 9. Accessibility and layout (BUG5, BUG9, SPEC §9.6)

Previews and the screenshot suite cover the common cases. These are the ones
they have historically missed.

| # | Phase | Test | Expected |
|---|---|---|---|
| H1 | P1 | **Font scale 2.0** | Set the device to the largest font size. Walk every screen. No label breaks mid-word, no control is clipped, and **every screen's primary action is reachable without hunting** — onboarding's CTAs are pinned for exactly this reason. Restore the device's own scale immediately afterwards (it is **1.15**, not 1.0). |
| H2 | P1 | **Bold text** | Enable Settings → Accessibility → Bold text (`font_weight_adjustment`, which is `+300` on the primary device). Walk the app. The type hierarchy is still visible — headings heavier than body. A single flat weight everywhere means the font family stopped covering the shifted weights; see `LfFontAxisTest`. |
| H3 | P1 | **RTL** | `adb shell cmd locale set-app-locales com.ledgerflow --locales ar`, walk the app, then reset with an empty `--locales`. Layout mirrors, nothing overlaps, amounts stay readable. This changes one app only — never the system-wide setting. |
| H4 | P1 | **TalkBack, full pass** | Every icon-only control announces itself. Amounts are announced as "1,240 rupees", not "₹1240". The currency symbol is never read as a glyph. |
| H5 | P1 | **Touch targets** | Every tappable thing is at least 48 dp, including the compact in-card actions. |
| H6 | P1 | **Keyboard does not eat the screen** | On the entry form and the Recovery screen, focus a field near the bottom. The pinned bar rides above the keyboard and the focused field is visible. A bar rendering a few pixels tall at the top of the screen is the double-inset bug (BUG5) returning. |
| H7 | P1 | **Reduced motion** | Turn animator duration scale off. Nothing depends on an animation completing. |
| H8 | P1 | **Dark and light** | Both themes on every screen. Debit and credit stay distinguishable; nothing relies on colour alone. |

---

## 10. Performance, by feel (SPEC §11)

Macrobenchmark gives the numbers. This block is the part the numbers only
approximate — run it on the device, not an emulator.

| # | Phase | Test | Expected |
|---|---|---|---|
| I1 | P1 | **Cold start** | From force-stop to first frame feels immediate. Budget is ≤ 700 ms; if it feels slow, it is, and the benchmark is measuring the wrong thing. |
| I2 | P1 | **Ledger scroll** | Fling a ledger with a realistic number of entries. No stutter, no blank rows waiting for a page. §11 wants zero frames over 16.6 ms. |
| I3 | P1 | **Entry in ≤ 4 taps** | A repeat expense, from launch to saved, in four taps. If it takes five, the flow regressed. |
| I4 | P1 | **Export a real dataset** | CSV export of a full vault completes without an ANR and the files open correctly in a spreadsheet. |
| I5 | P3 | **Analytics at 5Y** | Chart rendered in ≤ 300 ms, 60fps pan and zoom. |
| I6 | P3 | **Upgrading screen** | On a build that ships a migration: the pre-migration backup runs on a dedicated screen with determinate progress, not on the cold-start path, and the app never looks frozen (SPEC §8.1). |

---

## 11. Storage and resource pressure

| # | Phase | Test | Expected |
|---|---|---|---|
| J1 | P1 | **Low storage** | Fill the device to near-full, then export and (when it ships) back up. The failure is explicit and names what is needed. Nothing is half-written. |
| J2 | P3 | **Low storage blocks a migration** | With less than `2.2 × dbSize` free, launch a build carrying a migration. The migration does **not** run; the app says how much space is needed. Blocking is correct here. |
| J3 | P1 | **Nothing persistent in `cacheDir`** | Clear the app's cache from App info. Relaunch. Nothing is lost. Law 5 says `cacheDir` is decode scratch only; this is how you find out it was not. |

---

## 12. Recording a run

Copy this into the release PR or the tag's notes. A run with no record did not
happen.

```
Release:        v0.0.0 (versionCode NNN)
Build:          smsFull release, <sha>
Device:         SM-S721B, Android 16 / API 36
Tester:         <name>
Date:           YYYY-MM-DD
Device state:   font scale <x>, bold text <on/off>, <n> entries

  A1 pass   A2 pass   A3 pass   A4 n/a(P3)  A5 blocked(playSafe, §0)
  B1 ...
  ...

Blocked:  <row> — <why>
Failed:   <row> — <what happened, and the issue it became>
```

**A failed row is a release blocker until it is either fixed or explicitly
accepted in writing by the owner.** A fixed one gets a named regression test
(`BugN_...`) wherever a test can reach it — CLAUDE.md §2, Law 7. Several rows
here exist precisely because no test can, and those get their finding written
into this file instead, so the next run looks for it.
