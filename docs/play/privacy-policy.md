# LedgerFlow privacy policy

Last updated: 7 November 2026

LedgerFlow is a personal expense tracker for Android. This policy covers both
versions: the one on Google Play (`com.ledgerflow.playsafe`), and the one on
Samsung Galaxy Store and in this repository's releases (`com.ledgerflow`), which
can also read bank SMS.

## What stays on your phone

Everything you put into LedgerFlow stays on your phone:

- your entries, amounts, merchants, categories and budgets;
- the payment notifications LedgerFlow reads, and (in the Galaxy Store and
  repository version) the SMS it receives;
- receipt photos and PDFs you scan or import, and the text read from them;
- your backups and exports.

The database is encrypted on the phone. Text recognition, message parsing and
all analysis run on the phone. LedgerFlow has no account and no server. It
never uploads your financial data, messages or receipts in a form anyone else
can read. The one thing that can leave your phone is an encrypted backup, and
only to your own Google Drive, only if you turn that on (see below).

## Notifications LedgerFlow reads

If you grant notification access, LedgerFlow reads notifications only from a
built-in list of payment and banking apps. Notifications from every other app
are ignored before their content is read. What it reads is used only to suggest
entries in your Inbox, on your phone.

## SMS (Galaxy Store and repository version only)

If you grant the SMS permission, LedgerFlow receives incoming text messages.
Messages from known bank senders are analysed on the phone to suggest entries.
Messages from other senders are not analysed. Every message is stored
encrypted on the phone, and its text is deleted after 90 days; it is kept that
long so it can be re-checked if a bank sender is added. LedgerFlow never sends
SMS, never reads your existing SMS inbox, and never stops a message reaching
your messaging app. The Google Play version has no SMS permission.

## What is sent off your phone

LedgerFlow reads receipts with Google's ML Kit text recognition, which runs
entirely on your phone; your images are never sent anywhere. The ML Kit
library does report usage statistics to Google. According to Google, these
are device information (such as model and Android version), app information
(package name and version), a per-installation identifier, performance
metrics, the image format and size used, and error codes. They are sent over
HTTPS, and Google states it does not share them with third parties. They
never include your receipts, entries or messages. Google describes this at
https://developers.google.com/ml-kit/android-data-disclosure.

Apart from Google Drive backup, described next, LedgerFlow itself sends
nothing.

## Backups and your recovery phrase

Backups are encrypted with a key derived from the 24-word recovery phrase you
write down at setup, and saved to a folder you choose. LedgerFlow does not
store your phrase and cannot recover it. Where your backups go after that, for
example a cloud folder you picked, is governed by that service.

**Google Drive backup** is off until you turn it on in Settings > Data >
Google Drive and sign in with your Google account. Then, after each nightly
backup, LedgerFlow copies the backup file and your receipt images to a folder
called "LedgerFlow backups" in your own Drive. They are encrypted on your phone
before they leave it, and opening them needs your 24-word phrase, which
LedgerFlow never has and never sends. Google can see the file names, their
sizes and when they were uploaded, and that you use LedgerFlow, but not what is
inside them. LedgerFlow asks only to see the files it creates in your Drive,
never anything else there, and keeps the newest five backups. Uploads may use
mobile data. Turning it off stops the uploads; the copies already in your
Drive stay there until you delete them.

## Permissions

- **Camera**: to scan receipts and recovery-phrase QR codes. Images are
  processed on the phone.
- **Notifications**: to tell you when a payment is waiting in your Inbox, and
  when a budget reaches a threshold.
- **Notification access** (granted in Android's settings): to read payment
  notifications, as described above.
- **Internet**: used only for Google Drive backup, if you turn it on. It is
  also brought in by the ML Kit library described above.
- **Receive SMS** (Galaxy Store and repository version only, not the Google
  Play version): to receive bank transaction messages, as described above.

## Children

LedgerFlow is not directed at children under 13.

## Deleting your data

Uninstalling LedgerFlow, or clearing its storage, deletes everything it holds
on your phone. Backups you saved elsewhere, including copies in your Google
Drive, are yours to delete. LedgerFlow holds no copy anywhere else.

## Changes and contact

Changes to this policy are published at this address, with a new date above.

Contact: **[owner: contact email]**
