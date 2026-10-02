# Play Data safety — answers for `com.ledgerflow.playsafe`

Play Console → Policy → App content → Data safety. Draft answers, with the
reason behind each so they can be re-derived when a dependency changes.

**The rule Play applies:** data processed only on the device is *not*
"collected". Data an SDK in the app sends off the device *is* collected, by the
app, whoever's server receives it.

**What leaves this device:** only what Google's ML Kit text recognizer reports.
The owner chose to keep it and declare it (2026-10-02, ADR-0021 Option D).
Google's own disclosure for the bundled recognizer, read 2026-10-02
(`https://developers.google.com/ml-kit/android-data-disclosure`):

- device information (manufacturer, model, OS version, build);
- application information (package name, app version);
- per-installation identifiers "not intended to uniquely identify a user or
  physical device";
- performance metrics (latency), API configuration (image format,
  resolution), input/output size, feature version, event type, error codes;
- "ML Kit encrypts the data in transit using HTTPS"; "ML Kit does not transfer
  this data to third-parties."

That list is Google's statement. It has not been packet-captured here
(ADR-0021 says the same).

## Answers

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | ML Kit's report, above. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | HTTPS, per Google. |
| Do you provide a way for users to request that their data is deleted? | **No** | The app holds no server-side data and has no account. ML Kit's data is Google's to retain. |

### Data types

| Data type | Collected | Shared | Ephemeral | Required | Purpose |
|---|---|---|---|---|---|
| **App info and performance → Diagnostics** (device model, OS version, latency, error codes) | Yes | No | No | Required | Analytics |
| **Device or other IDs** (ML Kit's per-installation identifier) | Yes | No | No | Required | Analytics |

"Required" because the app offers no way to turn ML Kit's reporting off, and
Google documents none. "Analytics" is the closest of Play's purposes to Google's
stated use (measuring and improving the library). **Owner to confirm the
purpose** in the console's wording.

### Declared NOT collected, and why

| Data | Why it is not "collected" |
|---|---|
| Financial info (amounts, merchants, entries, budgets) | Stays on the device, in the encrypted database. Never transmitted. |
| Messages (notification text) | Read on the device from the built-in list of payment and banking apps, parsed on the device, never transmitted. playSafe has no SMS permission. |
| Photos and files (receipts) | Recognized on the device; the model ships inside the app. Never uploaded. `OcrRunsWithoutNetworkTest` runs recognition with the radio off. |
| Backups and exports | Written by the user to a folder they choose (Android's file picker). The app does not upload them anywhere. |
| Crash logs | The app has no crash reporter. |

## Re-check this file when

- a dependency changes `EXPECTED_MERGED_PERMISSIONS` in `app/build.gradle.kts`;
- the ML Kit version in `gradle/libs.versions.toml` changes (re-read Google's
  disclosure page);
- ADR-0021 flips to Option C (remove `INTERNET`), in which case the answer to
  the first question becomes **No** and the table above disappears.
