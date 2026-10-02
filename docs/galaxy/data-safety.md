# Galaxy Store Data Safety — answers for `com.ledgerflow` (smsFull)

Seller Portal's Data Safety tab asks what data the app collects and what it
shares with third parties. The answers are the Play ones
(`docs/play/data-safety.md`, read it for the reasoning and Google's source) with
one addition: this build also reads SMS.

## Collected (leaves the device)

Only what Google's ML Kit text recognizer reports, kept and declared on the
owner's decision (ADR-0021 Option D, 2026-10-02):

| Data | Collected | Shared with third parties | Purpose | Encrypted in transit |
|---|---|---|---|---|
| Diagnostics: device model, OS version, latency, error codes, API configuration | Yes | No (Google states it does not transfer it to third parties) | Analytics, the library's own | Yes, HTTPS |
| Device or other IDs: ML Kit's per-installation identifier | Yes | No | Analytics | Yes, HTTPS |

Required: there is no way to turn it off. Deletion: the app holds nothing
server-side and has no account.

## Not collected (stays on the device)

| Data | Why |
|---|---|
| **SMS** (this build only) | `RECEIVE_SMS`: every incoming SMS is written to the encrypted database on the phone; only those from allowlisted bank senders are parsed. Bodies are deleted after 90 days (D-09, `purgeExpiredBodies`). Never transmitted. The receiver never calls `abortBroadcast()`, so other SMS apps still receive every message. |
| Notification text | Read only from the built-in list of payment and banking apps, parsed on the phone. |
| Financial info (entries, amounts, merchants, budgets) | Encrypted database on the phone. |
| Photos and files (receipts) | Recognized on the phone by a model inside the app. |
| Backups and exports | Written by the user to a folder they choose. |
| Crash logs | No crash reporter. |

## If Samsung asks why the app needs SMS

"LedgerFlow is an expense tracker. It receives incoming SMS so that
transaction alerts from bank senders can be offered for approval in the app's
Inbox. Messages are stored only on the device, encrypted, and are never
uploaded; only bank senders' messages are analysed, and message text is
deleted after 90 days. Nothing is recorded until the user approves it. The app
does not send SMS, read the SMS inbox, or stop messages reaching the user's
messaging app."

That last sentence is literal: the build holds `RECEIVE_SMS` and not
`READ_SMS`, pinned by `mergedPermissionCheckSmsFullRelease`.
