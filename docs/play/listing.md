# Play listing — store text (playSafe, `com.ledgerflow.playsafe`)

Draft for the owner to paste into Play Console → Grow → Store presence → Main
store listing. Limits are Play's; the counts beside each field are measured by
`docs/play/README.md`'s check, not estimated.

Every claim here must stay true of the **playSafe** build. It has no SMS
permission (D-04), and it does declare `INTERNET`, because Google's ML Kit
text recognizer merges a usage-statistics uploader (ADR-0021, Option D kept by
the owner on 2026-10-02). So nothing below says "no internet", "no network" or
"no data collected". What it says instead is what is true: your financial data,
messages and receipt images never leave the phone.

## App name (≤ 30)

```
LedgerFlow: Private Expenses
```

## Short description (≤ 80)

```
Offline, encrypted expense tracker. Nothing is booked until you approve it.
```

## Full description (≤ 4,000)

```
LedgerFlow is a private expense tracker that keeps your money records on your phone, encrypted, and never adds anything to your books without your tap.

YOU APPROVE EVERYTHING
Payments it notices land in an Inbox as suggestions. Approve, edit or discard each one. Nothing reaches your ledger on its own.

CAPTURE WITHOUT TYPING
• Payment notifications: with your permission, LedgerFlow reads notifications from a built-in list of payment and banking apps (Google Pay, PhonePe, Paytm and major Indian banks). Notifications from any other app are never read.
• Receipts: scan a paper receipt or import a photo or PDF. Text recognition runs on the phone, in English and Devanagari, and can split a bill into items.
• Manual entry, with a draft that survives the app being closed.
• One payment that shows up twice (for example a bank alert and a payment-app alert) becomes one suggestion, not two. The duplicate stays visible if you want to check it.

TWO BOOKS THAT NEVER MIX
Spending and income are kept as two separate ledgers. No screen nets one against the other, so a salary never hides a month of spending.

SEE WHERE IT GOES
Charts by category, merchant and payment method, trends over time, period comparisons, recurring charges and what is due before the month ends. Budgets with alerts.

PRIVATE BY DESIGN
• No account, no sign-in, no cloud sync.
• The database is encrypted on the phone.
• Your entries, messages and receipt images are processed on the phone and never uploaded.
• An optional app lock puts the ledger behind your fingerprint or screen lock.

YOU HOLD THE KEY
At setup you write down a 24-word recovery phrase. It is the only way to restore your data on a new phone, and LedgerFlow cannot recover it for you. Encrypted backups go to a folder you choose, and can be restored on another phone with the phrase.

EXPORT
CSV and Excel (XLSX), whenever you want them.

ABOUT THE INTERNET PERMISSION
LedgerFlow itself sends nothing anywhere. The permission is there because Google's on-device text recognition library includes a component that reports usage statistics (such as device model, app version and error codes) to Google. It never receives your receipts, entries or messages. Details are in the privacy policy and the Data safety section.

Amounts are kept in one base currency chosen at setup (INR by default). Foreign spending can be recorded with the rate you enter.
```

## Release notes — v0.1.0 (≤ 500)

```
First release. Notification capture for payment and banking apps, receipt scanning, manual entry, an approval Inbox, separate spending and income ledgers, analytics, budgets, CSV and Excel export, and encrypted backups protected by your 24-word recovery phrase.
```

## Categorisation

| Field | Value |
|---|---|
| App or game | App |
| Category | Finance |
| Tags (pick up to 5 in the console) | Budgeting, Expense tracker, Personal finance — whichever of these the console offers |
| Contact email | **Owner to choose.** It is shown publicly on the listing. |
| Website | Optional. The repository is public: `https://github.com/swaroop-rayker/LedgerFlow` |
| Privacy policy URL | `https://github.com/swaroop-rayker/LedgerFlow/blob/main/docs/play/privacy-policy.md` |
