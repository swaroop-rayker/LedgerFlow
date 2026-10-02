# Releasing playSafe to Google Play (P5 step 6)

What is in this folder, and the order to do things in. Code-side work is done;
everything from step 1 on is the owner's, because it involves the signing key,
the GitHub secrets and the Play Console.

| File | For |
|---|---|
| `listing.md` | Store text: name, short and full description, release notes, category |
| `data-safety.md` | The Data safety form, with the reason for each answer |
| `declarations.md` | Every other App content form, and the testing-track rules |
| `privacy-policy.md` | The privacy policy; its GitHub URL is the policy URL |

Decided by the owner, 2026-10-02: ML Kit's usage reporting is **kept and
declared** (ADR-0021 Option D); the package is **`com.ledgerflow.playsafe`**
(permanent once uploaded); **one key** signs sideloaded smsFull APKs and is
playSafe's **upload key**, with Google holding the Play signing key (Play App
Signing); the account is a **new personal** one, so production needs a 14-day,
12-tester closed test first.

**Samsung Galaxy Store** is a second channel for the smsFull build
(`com.ledgerflow`), from the same tag and the same key: `docs/galaxy/`. Steps
1 to 3 below serve both stores. The privacy policy covers both builds.

## 0. Already true (checked 2026-10-02)

- `targetSdk` 37, above Play's minimum.
- Every arm64 native library is 16 KB page-aligned (`PT_LOAD` align 16384:
  SQLCipher, ML Kit, OpenCV, CameraX, DataStore, graphics-path, libc++), and
  `zipalign -c -P 16` passes on the playSafe release APK.
- playSafe declares no SMS permission (`mergedPermissionCheckPlaySafeRelease`).
- Release signing works and is checked: `:app:verifyReleaseSigning` (BUG46)
  passed on all three artifacts signed with a throwaway test key, and failed
  on the debug key and on an unsigned build.

## 1. Make the release key (owner, once)

Run this **outside the repository** and keep the file and both passwords in at
least two places off this PC. keytool asks for the passwords itself, so they
are never on a command line:

```powershell
& "D:\Software\Android App development\jbr\bin\keytool.exe" -genkeypair -v -keystore "$HOME\ledgerflow-release.jks" -storetype PKCS12 -alias ledgerflow -keyalg RSA -keysize 4096 -validity 10000
```

Losing it means sideloaded smsFull installs can never be updated (BUG3). For
Play it is only the upload key, and Google can reset a lost upload key.

## 2. Give it to GitHub (owner)

`release.yml` reads four repository secrets. `gh` prompts for each value, so
nothing lands in shell history:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\ledgerflow-release.jks")) | gh secret set RELEASE_KEYSTORE_BASE64 --repo swaroop-rayker/LedgerFlow
```
```powershell
gh secret set RELEASE_KEYSTORE_PASSWORD --repo swaroop-rayker/LedgerFlow
```
```powershell
gh secret set RELEASE_KEY_ALIAS --repo swaroop-rayker/LedgerFlow
```
```powershell
gh secret set RELEASE_KEY_PASSWORD --repo swaroop-rayker/LedgerFlow
```

The alias is `ledgerflow` if you used the command above.

## 3. Tag the release

`version.properties` is `versionCode=1`, `versionName=0.1.0`. Pushing tag
`v0.1.0` runs `release.yml`: guards; two signed builds, each verified (the
playSafe AAB and APK for Play, then smsFull's universal APK and per-ABI splits,
including the Galaxy Store arm64 file); the durability suite on an emulator;
and a **public** GitHub Release with the APKs, the AAB and both mapping files.
Ask Claude to tag it, or:

```bash
git tag v0.1.0
```
```bash
git push origin v0.1.0
```

## 4. Play Console (owner)

1. Create the app: name from `listing.md`, App, Free.
2. App content: every form from `declarations.md` and `data-safety.md`.
3. Store listing: text from `listing.md`, plus graphics (section 5).
4. Testing → **Internal testing** → create a release → upload
   `app-playSafe-release.aab` from the GitHub Release. On first upload choose
   **Let Google manage and protect your app signing key**.
5. Add yourself as a tester, install from the opt-in link on the phone, and
   run `TESTING.md` A7 on the Play-delivered build.
6. Then **Closed testing** with at least 12 testers for 14 days, then apply
   for production.

## 5. Graphics (done 2026-10-02, shared with Galaxy Store)

| Asset | Requirement | File |
|---|---|---|
| App icon | 512 × 512 PNG, full square (the store masks it) | `docs/brand/store-icon-512.png` |
| Feature graphic (Play only) | 1024 × 500 PNG or JPEG | `docs/brand/feature-graphic-1024x500.png` |
| Phone screenshots | 2 to 8; each side 320 to 3,840 px; long side no more than twice the short | `docs/store/screenshots/1-…6-….png`, 1080 × 2104 (1.95 : 1), in upload order |

**The icon is "two books"** (owner's choice, 2026-10-02): a debit page and a
credit page side by side and never touching, in the app's own debit and credit
colours. It is original artwork made of plain shapes, drawn for LedgerFlow. The
launcher icon (`app/src/main/res/drawable/ic_launcher_*.xml`, adaptive, with a
themed-icon layer) and `docs/brand/ledgerflow-icon.svg` share identical path
data. The PNGs come from `docs/brand/render_store_graphics.py` (run it from the
repository root); the feature graphic is set in Inter, which the app already
ships under the SIL Open Font License. A trademark search of the name and mark
(the IP India register, for instance) is the owner's to do before publishing.

**The screenshots are from LF Bench**, never the real vault: its synthetic data,
reseeded on 2026-10-02 with generic made-up merchants (no real brand appears),
on SM-S721B at the owner's settings (font scale 1.15, Bold text on). They are
cropped to the app's own area, without the status bar or navigation bar: the
phone's full 1080 × 2340 frame is 2.17 : 1, over Play's limit. Retake them from
LF Bench whenever a screen changes materially.
