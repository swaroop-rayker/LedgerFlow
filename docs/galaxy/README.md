# Releasing smsFull to Samsung Galaxy Store

Galaxy Store is a second public channel beside Google Play, added on the
owner's instruction (2026-10-02); Play stays exactly as `docs/play/` describes.

| File | For |
|---|---|
| `listing.md` | Store text for the Galaxy Store listing |
| `data-safety.md` | Seller Portal's Data Safety tab |
| `../play/privacy-policy.md` | The same privacy policy; it covers both builds |

## What goes where

| Channel | Build | Package | Signed by | File |
|---|---|---|---|---|
| Google Play | playSafe (no SMS) | `com.ledgerflow.playsafe` | Google's app signing key (you upload with yours) | `app-playSafe-release.aab` |
| **Galaxy Store** | **smsFull** (SMS + notifications) | **`com.ledgerflow`** | **Your release key** (Galaxy has no app signing service) | **`app-smsFull-arm64-v8a-release.apk`** |
| GitHub Releases | smsFull | `com.ledgerflow` | Your release key | `app-smsFull-universal-release.apk` |

**Why this split works:**
- **No clash with Play.** The Galaxy package is not the Play package, so the
  two stores never try to update each other's install. That is Samsung's
  recommended setup
  (`https://developer.samsung.com/galaxy-store/cross-store-updates.html`).
- **Galaxy and GitHub are the same app.** Same package, same key, so a
  sideloaded install and a Galaxy install update each other cleanly.
- **The arm64 split is the right file.** The universal APK is about 197 MB,
  and about 130 MB of that is x86 and x86_64 code no Galaxy phone runs. The
  arm64 split was 43.1 MB, measured 2026-10-02; `release.yml` fails a release
  without it, or above the 50 MiB budget.
- **What is left out.** A phone that runs only 32-bit Android is not served by
  the arm64 file. The `armeabi-v7a` split (29.4 MB) exists in the GitHub
  Release if Samsung ever asks for it as a second binary.

**What is unknown:** Samsung publishes no policy on `RECEIVE_SMS` that could be
found (2026-10-02). If review rejects the SMS permission, the fallback is a
playSafe build under its own package (`com.ledgerflow.galaxy`), which needs a
`galaxy` build type in `:app`. It is not built until that happens.

## 0. Already true (checked 2026-10-02)

- targetSdk 37; Galaxy Store requires 33 or higher and a 64-bit binary.
- The arm64 split is signed and verified by `:app:verifyReleaseSigningSmsFullRelease`
  (throwaway test key), 16 KB-aligned (`zipalign -c -P 16`), and declares
  exactly `EXPECTED_MERGED_PERMISSIONS["smsFullRelease"]`.
- Nothing in the app is store-specific: no store links, no installer checks.

## 1. Seller account (owner)

Galaxy Store has no sign-up or annual fee, but **every Android app, free or
paid, needs commercial seller status**. Only watch-face and theme sellers can
stay private sellers.

1. Create a Samsung account, then sign up at Seller Portal
   (`https://seller.samsungapps.com`).
2. Apply for **commercial seller status** with a **D-U-N-S number** (verifying
   one can take up to 10 business days) or business registration documents.
   If a D-U-N-S number is not available to you as an individual, Samsung's
   answer is to contact the Seller Portal team about the options.

## 2. Build

The same tag as Play. `docs/play/README.md` steps 1 to 3 (key, secrets, tag)
produce both. The Galaxy file is `app-smsFull-arm64-v8a-release.apk` in the
GitHub Release.

## 3. Seller Portal (owner)

1. Add a new Android app. Binary tab: upload the arm64 APK.
2. App information: text from `listing.md`; privacy policy URL
   `https://github.com/swaroop-rayker/LedgerFlow/blob/main/docs/play/privacy-policy.md`;
   support email (shown publicly, owner's choice); age rating, adults.
3. Data Safety tab: from `data-safety.md`.
4. Icon and screenshots: the same set as Play (`docs/play/README.md` §5):
   `docs/brand/store-icon-512.png` and `docs/store/screenshots/`. Seller
   Portal shows its own size rules on upload; resize there if it asks.
5. Submit for review. Then run `TESTING.md` A8 on the Galaxy-delivered install.

## Every release after the first

Upload that tag's `app-smsFull-arm64-v8a-release.apk` to Galaxy Store and its
AAB to Play. The versionCode is shared (`version.properties`), so the two never
disagree about which build is newer.
