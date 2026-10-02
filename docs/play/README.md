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
`v0.1.0` runs `release.yml`: guards, signed build of both flavours plus the
playSafe AAB, `verifyReleaseSigning`, the durability suite on an emulator, and
a **public** GitHub Release with the APKs, the AAB and both mapping files.
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

## 5. Graphics (not done)

| Asset | Play's requirement | Status |
|---|---|---|
| App icon | 512 × 512 PNG | **Blocked**: the app has no launcher icon at all (it shows Android's default). Owner decision. |
| Feature graphic | 1024 × 500 PNG or JPEG | Follows from the icon. |
| Phone screenshots | 2 to 8, 16:9 or 9:16, each side 320 to 3,840 px | To capture from **LF Bench** (synthetic data, public test phrase), never the real vault. Needs the phone. |
