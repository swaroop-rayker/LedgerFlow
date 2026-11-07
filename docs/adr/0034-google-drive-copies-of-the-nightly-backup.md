# ADR-0034 — Google Drive keeps a copy of the nightly backup, through the user's own Google account

- **Status:** **Accepted** (owner, 2026-11-07). Build order: this ADR and the text it changes, then sign-in and upload, then restore from Drive.
- **Date:** 2026-11-07
- **Deciders:** Swaroop (owner, chose option B and each decision below), lead engineer
- **Amends:** **Law 6** (`CLAUDE.md` §2): its first sentence becomes "nothing this
  app *computes* touches a network, and the one thing that leaves the phone is a
  file already encrypted on it" (wording under *Law 6, rewritten*). **ADR-0021**
  stands; its line "the `INTERNET` permission is not used for anything we write"
  stops being true and is replaced by the module rule below.
- **Settles:** `docs/V2-PLAN.md` decision **D1**. The plan reserved ADR-0029 for
  it; that number went to the SQLCipher key (BUG56), and 0034 is the next free.
- **Spec sections touched:** `SPEC.md` §5.9, §7, §9.3, §16; `docs/V2-PLAN.md` §1
  (D1), §3.6; `CLAUDE.md` §0, §2 (Law 6), §3, §7; `TESTING.md` (new rows)

## Context

Every backup LedgerFlow makes stays on the phone. The nightly pass (ADR-0027)
writes a sealed `.lfbk` and the sealed receipt images (ADR-0023) into a folder
the user chose, and keeps the newest five. A phone that is lost, stolen or
broken takes that folder with it. The Recovery Kit brings back the *key*, not
the *data*.

D1 asked how a copy gets off the phone. Two options were put to the owner on
2026-11-07:

| | A: "Save a copy to…" through the system picker | B: Drive's API with Google sign-in |
|---|---|---|
| Automatic | No: one tap and a picker per copy | Yes: every nightly backup |
| Law 6 | Unchanged: Drive's own app uploads | Amended: LedgerFlow uploads |
| Dependency | None | `play-services-auth` |
| Owner setup | None | A Google Cloud project and OAuth clients |

Option A cannot be made automatic. Drive's document provider offers single-file
saves but does not appear in the folder picker (`ACTION_OPEN_DOCUMENT_TREE`),
which is what the nightly pass writes into. This is to be confirmed on the SM-S721B
before the build PR and recorded here. **The owner chose B.**

## Decisions (owner, 2026-11-07)

| | Question | Chosen |
|---|---|---|
| a | Which mechanism | **B**, Drive's REST API with Google sign-in |
| b | What goes up | **The `.lfbk` and the sealed receipt images**, so that Drive alone is enough to restore with receipts |
| c | Which network | **Any network**, mobile data included |
| d | How many backups on Drive | **The newest five**, as on the phone |
| e | Restore from Drive | **Yes, as a later PR**; sign-in and upload ship first |

## Design

### 1. What leaves the phone, and what never does

**Only files the nightly pass has already written, sealed and verified**,
copied byte for byte. Nothing is decrypted to be uploaded, and nothing is
encrypted again: the `.lfbk` is sealed to the phrase-derived public key
(ADR-0027) and each receipt sidecar to its own key (ADR-0023). Neither can be
opened by anything on the phone, so neither can be opened by Google.

**Google sees:** the account, a folder named "LedgerFlow backups", file names
(timestamps and receipt ids), sizes, upload times and the IP address of each
upload. **Google never sees:** the phrase, the DEK, any key, the ledger, an
amount, a merchant, or anything LedgerFlow computes. Parsing, OCR, analytics
and the backup's encryption stay on the phone, as Law 6 has always required.

**A compromised Google account yields ciphertext.** Opening a `.lfbk` takes the
24-word phrase (256 bits of entropy), which is never on the phone and never
uploaded.

### 2. One module talks to the network: `:core:drive`

Every network call LedgerFlow makes lives in a new module, `:core:drive`: the
authorization call and Drive's REST endpoints. **This is the replacement for
Law 6's old grep**, because a rule nobody can check is a rule that will be
broken:

- `bannedApiCheck` gains the JDK's network entry points (`java.net.URL`,
  `HttpURLConnection`, `HttpsURLConnection`, `openConnection`, `Socket`) and
  fails on any of them outside `core/drive/`.
- `moduleDependencyRuleCheck` allows `:core:drive` to be depended on only by
  `:feature:settings` (sign-in and status) and `:app` (wiring).
- `EXPECTED_MERGED_PERMISSIONS` is re-pinned in the build PR to whatever
  `play-services-auth` actually merges, measured rather than assumed, and the
  difference is recorded here.

`:core:drive` depends on `:core:model`, `:core:common` and `:core:domain`'s port.
It never sees the vault, the database or a key: it is handed file bytes and
names. `AppLockStateTest`'s idea, applied to the network: a test fails the
build if `core/drive/` names `:core:crypto`, `:core:database` or `VaultSession`.

### 3. Sign-in: `AuthorizationClient`, scope `drive.file` only

- **Library:** `com.google.android.gms:play-services-auth`, its
  `Identity.getAuthorizationClient(…).authorize(…)`. This is Google's
  supported way for an Android app to get a Drive access token. The older
  `GoogleSignIn` API is deprecated and is not used.
- **Scope:** `https://www.googleapis.com/auth/drive.file` **only**. With it the
  app sees only the files it created, never the rest of the user's Drive. It is
  one of Google's non-sensitive scopes, so publishing the consent screen needs
  no security review.
- **No token is stored by LedgerFlow.** Each upload asks `authorize` again, and
  Play services returns a fresh access token silently while the grant stands.
  When it does not (access revoked, account removed), the call returns a
  consent intent instead. A background upload cannot show it, so the upload
  stops and Settings shows **Sign in again**.
- **No ID token, no profile scope, no server auth code.** The account's email
  is shown only if the authorization result carries it; otherwise the screen
  says "your Google account".

**Why not something smaller** (`CLAUDE.md` §10):

- The platform `AccountManager` route (`getAuthToken` with an `oauth2:` scope) is
  the legacy path Google discourages for new apps.
- A browser-based OAuth library (AppAuth) would avoid Play services, but Google
  no longer accepts custom-scheme redirects for new Android OAuth clients.
- **Size and maintenance:** `play-services-auth` is Google's, actively released,
  and pulls `play-services-base`, `-basement` and `-tasks`. The release APK's
  growth after R8 is measured in the build PR and recorded here; it must not
  break §11's size budget.
- **Without Play services** (a de-Googled phone), Drive backup says it needs
  Google Play services and nothing else in the app changes.
- **Both flavours** get it: `playSafe` and `smsFull` differ only in SMS.

### 4. Drive's REST API over `HttpsURLConnection`, no Google API client

The Drive Java client and its HTTP stack are several megabytes for four calls.
ADR-0004 turned down Apache POI for the same reason. The calls are plain HTTPS
with a bearer token:

| Call | Endpoint |
|---|---|
| Find or create the folder | `GET /drive/v3/files?q=…`, `POST /drive/v3/files` (`mimeType` folder) |
| Upload a file | `POST /upload/drive/v3/files?uploadType=multipart`; `uploadType=resumable` above 5 MB |
| List what is there | `GET /drive/v3/files?q='<folder>' in parents&fields=files(id,name,md5Checksum,size)` |
| Delete an old backup | `DELETE /drive/v3/files/<id>` |

**Layout on Drive:** "LedgerFlow backups/" holds the `.lfbk` files under the
names the phone gives them, and "LedgerFlow backups/receipts/" holds the
sidecars under theirs. This mirrors the phone's folder, so a later restore from
Drive is the existing folder restore, reading a different place.

### 5. When it runs, and how it is verified

- **A separate worker, after the nightly pass, never inside it.** A successful
  nightly backup enqueues a unique `DriveUploadWorker` (replacing any queued
  one), constrained to `NetworkType.CONNECTED`. Mobile data is allowed, per the
  owner's choice (c), and WorkManager retries with exponential backoff. **A
  network failure never fails, delays or rolls back the local backup**, and
  `lastBackupAt` keeps meaning what it means.
- **Uploads only what is missing.** The `.lfbk` is new each night. Receipt
  sidecars are uploaded only when Drive has no file of that name, so after the
  first night only new receipts travel. The first upload is the large one, and
  Settings shows its size before Drive backup is turned on.
- **Verified, like every backup** (`CLAUDE.md` §7, Backup writer). After each
  upload, the MD5 that Drive reports (`md5Checksum`) is compared with the
  local file's. A mismatch is a failure: the file is deleted on Drive and the
  upload is retried. Only a fully verified pass records `lastDriveUploadAt`.
- **Rotation after verification:** the five newest `.lfbk` files the app
  uploaded are kept and older ones deleted (owner's choice (d)). A sidecar is
  deleted from Drive when the phone's folder no longer has it, so Drive mirrors
  the phone.
- **Status lives outside the vault** (ADR-0030): enabled, account,
  `lastDriveUploadAt`, the last outcome and a reason. Settings > Data > Google
  Drive shows "Last copied to Drive: …", "Waiting for a connection", "Sign in
  again", or the failure, worded like the nightly backup's.

### 6. Turning it on and off

- **On:** Settings > Data > **Google Drive** > **Turn on**. Play services'
  consent sheet opens; on success the first upload is queued straight away
  rather than waiting for the night.
- **Off:** uploads stop, the queued worker is cancelled, and access is revoked
  where the library offers it. **Files already on Drive are left there**,
  because they are the user's, and the screen says so and how to delete them.

### 7. Restore from Drive (later PR)

First run and Settings > Restore gain **From Google Drive**: sign in, list the
`.lfbk` files in "LedgerFlow backups", download the chosen one and its
receipts to app storage (`filesDir`, Law 5), then hand them to the existing
restore (ADR-0026, ADR-0033), unchanged. The phrase is typed as it is today.

### 8. What the owner sets up (once, outside the code)

1. A Google Cloud project, with the **Google Drive API** enabled.
2. The OAuth consent screen: External, app name, the owner's email, scope
   `drive.file`. Then set it to **In production**: in *Testing*, Google expires
   a grant after 7 days and the user would be asked to sign in again every week.
3. Android OAuth clients for `com.ledgerflow` (the release key's SHA-1) and
   `com.ledgerflow.debug` (the debug key's SHA-1).

No client ID or secret goes into the code or the repository: an Android client
is matched by package name and signing certificate. Until the clients exist,
sign-in fails with Google's error, and the screen reports it as "Google
sign-in isn't set up for this build".

## Law 6, rewritten

> 6. **Nothing this app computes touches a network.** Parsing, OCR, analytics and
> encryption are on-device, and no user data leaves the phone **except files
> already encrypted on it, copied to the user's own Google Drive when they turn
> that on** (ADR-0034). Every network call lives in `:core:drive`, and
> `bannedApiCheck` fails on a network API anywhere else. If you think *other*
> code needs the network, you're wrong: raise it as an ADR instead.

The paragraph on `INTERNET` and `EXPECTED_MERGED_PERMISSIONS` stays, with
`play-services-auth` added to what the pin covers.

## PRs

1. **This ADR**, and the text changes that follow from it: Law 6, `CLAUDE.md`
   §0, §3 and §7, the spec, V2-PLAN's D1 row, and the Drive folder-picker
   finding from the device.
2. **Sign-in and upload:** `:core:drive`, the guard changes, `DriveUploadWorker`,
   Settings > Data > Google Drive with mockups shown first, a permission re-pin,
   and a size measurement.
3. **Restore from Drive.**

## Consequences

- **Gained:** an automatic off-phone copy of every nightly backup, receipts
  included, which survives the loss of the phone. Together with the Recovery
  Kit, that is a full recovery from nothing.
- **Given up:** "no user data is ever sent anywhere" stops being literally true.
  What is sent is ciphertext to the user's own account, at their choice, but
  Google learns that this person uses LedgerFlow, when, and how much they
  store.
- **New standing cost:** a Google Cloud project to keep alive, a consent screen
  to keep published, and a Play services dependency to keep current.
- **Risk:** a mobile-data user's first upload with years of receipts is large.
  The size shown before turning on, and the owner's choice of any network, are
  deliberate.
- **Tests:**
  - `:core:drive`: the REST calls against an in-process HTTP server from the
    JDK (no new test dependency), covering upload, the MD5 check, rotation,
    "only what is missing", a 401 leading to "Sign in again", and retry on a
    5xx.
  - The guard tests named above.
  - A device check against a real Drive account in `TESTING.md`, because no CI
    runner signs in to Google.
