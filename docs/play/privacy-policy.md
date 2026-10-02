# LedgerFlow privacy policy

Last updated: 2 October 2026

LedgerFlow is a personal expense tracker for Android. This policy covers the
LedgerFlow app published on Google Play (`com.ledgerflow.playsafe`) and the
version distributed from this repository.

## What stays on your phone

Everything you put into LedgerFlow stays on your phone:

- your entries, amounts, merchants, categories and budgets;
- the payment notifications LedgerFlow reads, and (in the version from this
  repository) the bank SMS it reads;
- receipt photos and PDFs you scan or import, and the text read from them;
- your backups and exports.

The database is encrypted on the phone. Text recognition, message parsing and
all analysis run on the phone. LedgerFlow has no account, no sign-in, no cloud
sync and no server, and it never uploads your financial data, messages or
receipts.

## Notifications LedgerFlow reads

If you grant notification access, LedgerFlow reads notifications only from a
built-in list of payment and banking apps. Notifications from every other app
are ignored before their content is read. What it reads is used only to suggest
entries in your Inbox, on your phone.

## What is sent off your phone

LedgerFlow reads receipts with Google's ML Kit text recognition, which runs
entirely on your phone; your images are never sent anywhere. The ML Kit
library does report usage statistics to Google. According to Google, these
are device information (such as model and Android version), app information
(package name and version), a per-installation identifier, performance
metrics, the image format and size used, and error codes. They are sent over
HTTPS, and Google states it does not share them with third parties. They
never include your receipts, entries or messages. Google describes this at
https://developers.google.com/ml-kit/android-data-disclosure. LedgerFlow
itself sends nothing.

## Backups and your recovery phrase

Backups are encrypted with a key derived from the 24-word recovery phrase you
write down at setup, and saved to a folder you choose. LedgerFlow does not
store your phrase and cannot recover it. Where your backups go after that, for
example a cloud folder you picked, is governed by that service.

## Permissions

- **Camera**: to scan receipts and recovery-phrase QR codes. Images are
  processed on the phone.
- **Notifications**: to tell you when a payment is waiting in your Inbox, and
  when a budget reaches a threshold.
- **Notification access** (granted in Android's settings): to read payment
  notifications, as described above.
- **Internet**: present because of the ML Kit library described above.
  LedgerFlow does not use it.
- **SMS** (only in the version from this repository, not the Google Play
  version): to read bank transaction messages on the phone.

## Children

LedgerFlow is not directed at children under 13.

## Deleting your data

Uninstalling LedgerFlow, or clearing its storage, deletes everything it holds
on your phone. Backups you saved elsewhere are yours to delete. LedgerFlow
holds no copy anywhere else.

## Changes and contact

Changes to this policy are published at this address, with a new date above.

Contact: **[owner: contact email]**
