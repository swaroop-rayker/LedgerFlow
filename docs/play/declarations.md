# Play App content — the other declarations

Play Console → Policy → App content. Draft answers for the owner. Where Play's
exact wording may differ from what is written here, the intent is given and the
owner picks the matching option.

| Declaration | Answer | Notes |
|---|---|---|
| **Privacy policy** | `https://github.com/swaroop-rayker/LedgerFlow/blob/main/docs/play/privacy-policy.md` | Public, not a PDF, not geo-blocked. Fill in the contact line first. |
| **Ads** | No ads | |
| **App access** | All functionality is available without special access | No account or login. A reviewer creates a vault at first run: a 24-word phrase and a short word check. If Play asks for instructions anyway: "No login. At first run, choose a currency, write down the 24 words shown, and confirm the requested words." |
| **Content rating** (IARC questionnaire) | Category: Utility / Productivity. Every content question: No. | No violence, sexual content, language, controlled substances, gambling, user-to-user communication, location sharing or purchases. |
| **Target audience** | 18 and over | A personal finance tool. Not designed for children; nothing in it appeals to them. |
| **News app** | No | |
| **Health apps** | No health features | |
| **Government app** | No | |
| **Financial features** | The app is a personal expense and budgeting tracker. It does **not** provide banking, loans, payments, money transfer, investment, insurance or crypto services. | Pick the option the console offers for "no financial services / budgeting or expense tracking only". It does not move money or connect to any bank. |
| **Data safety** | See `docs/play/data-safety.md` | |
| **Foreground service permissions** | Not asked | No service declares a `foregroundServiceType`; WorkManager's plain `FOREGROUND_SERVICE` is unused. Checked in the merged manifest 2026-10-02. |
| **Sensitive permissions** | None requiring a declaration | playSafe has no `RECEIVE_SMS`/`READ_SMS` (D-04, pinned by `mergedPermissionCheckPlaySafeRelease`). `CAMERA` and `POST_NOTIFICATIONS` need no form. Notification access is granted by the user in Android's settings, after the app's own explanation screen ("What LedgerFlow reads"). |

## Release track (new personal developer account)

The owner's account is a personal one created after 13 November 2023, so Play
requires a **closed test with at least 12 testers, opted in for 14 days in a
row**, before it grants production access.

1. **Internal testing** first: up to 100 testers, available within minutes,
   no review wait. Use it to check the Play-delivered install on the phone.
2. **Closed testing**: a list of at least 12 testers who opt in through the
   test link and keep the app installed for 14 days.
3. **Apply for production** from the dashboard once the console says the
   requirement is met.
