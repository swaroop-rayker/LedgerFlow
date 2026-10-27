# LedgerFlow screen inventory

Everything the app can show, as of 2026-10-25 on `main` after V2-3 (`0cc93ea`,
PRs #8–#12). V2-3 added the lock screen, the Payment apps page, two dialogs, and
eleven new Settings rows under three new headings.
Built from the navigation graph (`app/.../navigation/LedgerFlowShell.kt`), the
pre-app routing in `MainActivity.kt`, and every `LfDialog`, `ModalBottomSheet`,
`DatePickerDialog`, `LfEmptyState` and notification builder in the source.

**76 entries:** 31 full screens · 1 bottom sheet · 30 dialogs and pickers ·
10 empty, error and banner states · 4 system notifications.

Inline controls (chips, segmented controls, cards inside a screen) and the
system's own file and folder pickers are described under their parent screen
rather than listed separately. There is no login, profile or account screen:
the app has no accounts.

**Status key:** **OK** = works and is reachable · **Gap** = something is
missing or doesn't match what the code says · **Thin** = placeholder-level UI ·
**Note** = works, worth knowing.

| Screen | File | Purpose | How it's reached | Status |
|---|---|---|---|---|
| Unlocking | `app/src/main/java/com/ledgerflow/MainActivity.kt:395` | Shown while the encrypted data opens | Every launch, before anything else | **OK.** A first-run step with its heading; after 10 s a line says it is still going (E2, `UnlockingScreenTest`) |
| Choose your currency (onboarding 1) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt:303` | Pick the base currency, which can't be changed later | First launch | **OK.** Also holds the "Restore from a backup" link |
| Your recovery phrase (onboarding 2) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt:350` | Reveal and write down the 24 words | After the currency step | **OK.** Words are hidden until "Reveal phrase" |
| Confirm your phrase (onboarding 3) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt:406` | Re-type 3 of the words | After the phrase step | **OK.** A mismatch shows an error and three new positions. No skip, by design |
| Save your Recovery Kit (onboarding 4) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt:440` | Save the kit as PDF (with QR code) or text file, or skip | After confirming the phrase | **OK.** Opens the system save dialog |
| Recovery Kit warning (dialog) | `core/ui/src/main/java/com/ledgerflow/core/ui/phrase/LfRecoveryKitWarningDialog.kt:24` | Warns that the kit file can unlock your data, before saving or skipping | Onboarding kit step, Back up now's "Save Recovery Kit" | **OK.** Shared by both screens |
| Where should backups go? (onboarding 5) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt:494` | Choose a folder for nightly backups, or "Not now" | After the kit step | **OK.** "Not now" asks first, in a sheet (BUG67, E2); goldens `onboarding-backup-*`, `onboarding-skip-backups-*` |
| Setting up / You're set up (onboarding 6) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt:508` | Progress while the key and database are created | Last onboarding step; moves on by itself | **OK.** A failed setup says so and offers Try again (BUG66, E2); goldens `onboarding-setup-failed-*` |
| Onboarding error (state) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt:263` | Shows a failure with "Dismiss" | Any onboarding step that fails | **OK.** An error notice since E2; golden `onboarding-kit-failed-*` |
| Restore a backup | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/restore/RestoreScreen.kt:74` | Pick a backup folder or file, enter or scan the 24 words, "Open my ledger" | "Restore from a backup" on the currency step | **OK.** About 8 result messages (wrong phrase, damaged, newer version, …) |
| Finish restoring | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/restore/RestoreScreen.kt:74` | Same screen, with no way back | Relaunch after an interrupted restore | **OK** |
| Recovery | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/recovery/RecoveryScreen.kt:36` | Type, paste or scan the 24 words to unlock | Launch, when the phone's key can't open the data | **OK.** 5 explanations by cause, plus failure messages |
| QR phrase scanner | `core/ui/src/main/java/com/ledgerflow/core/ui/phrase/LfPhraseScanner.kt:67` | Scan the Recovery Kit's QR code instead of typing | Recovery, Restore, Back up now | **OK** |
| Updating your ledger | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/upgrade/UpgradingScreen.kt:41` | Progress during a database upgrade | Launch after an app update that changes the database | **OK.** "A copy of your data was saved first" is the step's safe-note line since E2 |
| Update blocked | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/upgrade/UpgradingScreen.kt:75` | Explains why the update can't go ahead | A failed or blocked upgrade | **Gap.** 4 versions: free up space, did not finish, did not start, version older than data. No action on any of them (SPEC §16 Q28) |
| Notification capture explainer (first run) | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/notifications/NotificationAccessScreen.kt:84` | Grant notification reading and alerts, or "Not now" | Once, right after setup | **OK.** Same code as "Notification capture" below |
| LedgerFlow is locked (V2-3) | `app/src/main/java/com/ledgerflow/applock/LockScreen.kt:31` | "Unlock" with the phone's fingerprint, face or screen lock; leaving sends the app to the background | Launch, or a return after the "Lock after" time, when App lock is on | **OK.** Locks the screens only, never the data key (`CLAUDE.md` §7) |
| Home | `feature/dashboard/src/main/java/com/ledgerflow/feature/dashboard/DashboardScreen.kt:133` | The month's two totals, to-review count, budgets, upcoming charges, recent entries | Bottom bar tab 1 (the start screen) | **OK** |
| Home banners (state) | `feature/dashboard/src/main/java/com/ledgerflow/feature/dashboard/DashboardScreen.kt:663` | Missed captures ("Got it"), crash report (Save / Dismiss), backup reminder, capture off or stopped | Appear on Home when they apply | **OK.** Four cards, lines 663–808 |
| Home empty state | `feature/dashboard/src/main/java/com/ledgerflow/feature/dashboard/DashboardScreen.kt:243` | "Nothing here yet" | Home with both books empty | **OK** |
| Ledger | `feature/ledger/src/main/java/com/ledgerflow/feature/ledger/LedgerScreen.kt:81` | Expenses or Income list, unsaved drafts and to-review items, delete | Bottom bar tab 2, Home "See all" | **Gap.** A saved entry can't be opened or edited, only deleted. Only recent days are listed |
| Delete entry (dialog) | `feature/ledger/src/main/java/com/ledgerflow/feature/ledger/LedgerScreen.kt:151` | Confirm moving an entry to the bin | Bin icon on a Ledger row | **OK** |
| Discard unsaved entry (dialog) | `feature/ledger/src/main/java/com/ledgerflow/feature/ledger/LedgerScreen.kt:151` | Confirm throwing away a draft | Discard on a draft row | **OK** |
| Ledger empty states | `feature/ledger/src/main/java/com/ledgerflow/feature/ledger/LedgerScreen.kt:685` | "No expenses/income yet", "Nothing in the last N days" | Empty book, or nothing recent | **Note.** The second one sends you to Analytics or Export for older entries |
| Analytics | `feature/analytics/src/main/java/com/ledgerflow/feature/analytics/AnalyticsScreen.kt:82` | Period picker plus 11 sections (charts, categories, merchants, recurring, …) | Bottom bar tab 3, Home "Open Analytics" / "Details" | **OK** |
| Analytics empty states | `feature/analytics/src/main/java/com/ledgerflow/feature/analytics/AnalyticsScreen.kt:153` | Loading, Nothing matched, Nothing in this range, Nothing to chart yet | Analytics with no data for the view | **OK** |
| Filters (dialog) | `feature/analytics/src/main/java/com/ledgerflow/feature/analytics/AnalyticsFilterSheet.kt:58` | Filter by category or merchant; opens a multi-select picker (`core/ui/.../picker/LfMultiPickerDialog.kt`) | "Filters" link on Analytics | **Note.** The file is named "Sheet", but it's a dialog |
| Custom range (dialog) | `feature/analytics/src/main/java/com/ledgerflow/feature/analytics/AnalyticsFilterSheet.kt:316` | Set a custom period | Custom period chip | **OK** |
| Date-range picker (dialog) | `feature/analytics/src/main/java/com/ledgerflow/feature/analytics/AnalyticsFilterSheet.kt:403` | Pick exact start and end dates | "Pick exact dates" in Custom range | **OK** |
| Settings | `feature/settings/src/main/java/com/ledgerflow/feature/settings/SettingsScreen.kt:50` | 19 rows under Notifications, Organise, Data, Security & privacy, Appearance, Currency & region, Tools. Rows that open a page, switches (App lock, Block screenshots), inline choices (Theme, Animations, Start screen, Number grouping), and a read-only Base currency | Bottom bar tab 4 | **Note.** Three row kinds (link, switch, choice) now share one list; "Budget alerts" opens Android's own notification page, outside the app |
| Delete receipts (dialog) | `feature/settings/src/main/java/com/ledgerflow/feature/settings/SettingsScreen.kt:320` | Delete every receipt image | Settings "Receipts" row | **Gap.** It's the only receipts action: there's no way to view them |
| Options (dialog, V2-3) | `feature/settings/src/main/java/com/ledgerflow/feature/settings/SettingsScreen.kt:548` | Pick one value from a short list | Settings "Date order" and "Lock after" rows | **OK** |
| Add sheet (bottom sheet) | `app/src/main/java/com/ledgerflow/navigation/LedgerFlowShell.kt:486` | Scan a receipt / Manual entry / Inbox (count) | Centre "+" in the bottom bar | **Note.** The comments at lines 92 and 478 say Scan is "absent until P4", but it's shown (out of date) |
| Add entry | `feature/entry/src/main/java/com/ledgerflow/feature/entry/EntryScreen.kt:79` | Expense or income, amount, single item or itemised (`core/ui/.../lineitem/LfLineItemEditor.kt`), details, Save | "Manual entry" in the Add sheet, a draft row in the Ledger | **OK.** Has a "Picked up where you left off" bar (Keep it / Start fresh) |
| Entry pickers (dialog) | `feature/entry/src/main/java/com/ledgerflow/feature/entry/EntryDialogs.kt:57` | Pick category, subcategory, merchant or payment method | Tapping a field in Add entry | **OK** |
| Entry date (dialog) | `feature/entry/src/main/java/com/ledgerflow/feature/entry/EntryDialogs.kt:88` | Pick the date | Date field in Add entry | **OK** |
| Start fresh? (dialog) | `feature/entry/src/main/java/com/ledgerflow/feature/entry/EntryDialogs.kt:126` | Confirm clearing the form | "Start fresh" / "Start another" | **OK** |
| Discard ₹X? (dialog) | `feature/entry/src/main/java/com/ledgerflow/feature/entry/EntryDialogs.kt:151` | Confirm discarding a draft | Cancel on a draft with content | **OK** |
| Discard the items? (dialog, entry) | `feature/entry/src/main/java/com/ledgerflow/feature/entry/EntryDialogs.kt:173` | Confirm dropping line items when switching back to single item | Itemised → Single item | **OK** |
| Scan a receipt | `feature/ocr/src/main/java/com/ledgerflow/feature/ocr/capture/OcrCaptureScreen.kt:74` | Take a photo or import a file or PDF, check what was read, "Save to Inbox" | "Scan a receipt" in the Add sheet (only) | **OK.** States: checking camera, starting camera, camera denied, reading, result, failure |
| Inbox | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/InboxScreen.kt:89` | Approve, review or discard captured payments; restore; erase | Add sheet, Home "Open Inbox", Diagnostics "Show in Inbox", group notification, `ledgerflow://inbox` | **Note.** Tabs: Pending, Suppressed, Discarded, Not transactions. Not in the bottom bar |
| Inbox erase confirmation (dialog) | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/InboxScreen.kt:285` | Confirm erasing the selected items or all of them | "Erase N" / "Erase all" in Inbox | **OK** |
| Inbox empty states | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/InboxScreen.kt:139` | A per-tab message when the tab is empty | Empty Inbox tab | **OK** |
| Review | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/ReviewScreen.kt:59` | Fix the details, see the original message, Approve or Discard | Inbox row, Ledger to-review row, tapping a payment notification, `ledgerflow://inbox/{id}` | **OK** |
| Review pickers (dialog) | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/ReviewDialogs.kt:38` | Pick category, merchant or payment method | Tapping a field in Review | **OK** |
| Review date (dialog) | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/ReviewDialogs.kt:68` | Pick the date | Date field in Review | **OK** |
| Discard the items? (dialog, review) | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/ReviewDialogs.kt:90` | Confirm dropping line items | Itemised → Single item in Review | **OK** |
| Not here any more (state) | `feature/inbox/src/main/java/com/ledgerflow/feature/inbox/ReviewScreen.kt:85` | The item was already handled or removed | Review opened for a stale item (for example from an old notification) | **OK** |
| Notification capture | `feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/notifications/NotificationAccessScreen.kt:84` | Check or fix notification access, "Done" | Settings "Notification capture", Home capture banner | **OK** |
| Payment apps (V2-3) | `feature/settings/src/main/java/com/ledgerflow/feature/settings/allowlist/AllowlistScreen.kt:51` | Which apps' notifications LedgerFlow reads: turn built-in ones off, add an installed app, remove added ones | Settings "Payment apps" | **OK.** Has a "Reading the apps on this phone" loading note |
| Add an app? (dialog, V2-3) | `feature/settings/src/main/java/com/ledgerflow/feature/settings/allowlist/AllowlistScreen.kt:186` | Confirm reading notifications from an app that isn't a known payment app ("Add anyway") | "Add an app" on Payment apps | **OK** |
| Organise | `feature/categories/src/main/java/com/ledgerflow/feature/categories/CategoriesScreen.kt:59` | Categories, Merchants and Payment methods tabs: add, rename, hide, merge, erase, restore | Settings "Categories & merchants" | **Note.** The row name and screen title ("Organise") don't match |
| Name prompt (dialog) | `feature/categories/src/main/java/com/ledgerflow/feature/categories/TaxonomyDialogHost.kt:48` | New or rename a category, subcategory or merchant | Add / Rename in Organise | **OK** |
| Hide "…"? (dialog) | `feature/categories/src/main/java/com/ledgerflow/feature/categories/TaxonomyDialogHost.kt:104` | Confirm hiding an item | Hide in Organise | **OK** |
| Erase "…"? (dialog) | `feature/categories/src/main/java/com/ledgerflow/feature/categories/TaxonomyDialogHost.kt:163` | Confirm permanently erasing an item | Erase in Organise | **OK** |
| Move N entries first (dialog) | `feature/categories/src/main/java/com/ledgerflow/feature/categories/TaxonomyDialogHost.kt:195` | Reassign entries before an erase | Erase on an item that has entries | **OK** |
| Reassign (dialog) | `feature/categories/src/main/java/com/ledgerflow/feature/categories/TaxonomyDialogHost.kt:239` | Move entries to another category | Organise | **OK** |
| Merge (dialog) | `feature/categories/src/main/java/com/ledgerflow/feature/categories/TaxonomyDialogHost.kt:282` | Merge a merchant into another | Merge in Organise | **OK** |
| New payment method (dialog) | `feature/categories/src/main/java/com/ledgerflow/feature/categories/TaxonomyDialogHost.kt:322` | Add a card or account | Payment methods tab | **OK** |
| Organise empty states | `feature/categories/src/main/java/com/ledgerflow/feature/categories/CategoriesScreen.kt:204` | "No categories / merchants / payment methods yet" (also lines 380, 438) | Empty Organise tab | **OK** |
| Budgets | `feature/budget/src/main/java/com/ledgerflow/feature/budget/BudgetScreen.kt:45` | Add, edit or delete monthly limits | Settings "Budgets", Home "All budgets" | **OK.** "Done" above the title (BUG70); its messages show (BUG68); Delete asks first (BUG69) |
| Budgets empty state | `feature/budget/src/main/java/com/ledgerflow/feature/budget/BudgetScreen.kt:75` | "No budgets yet" | Budgets with none set | **OK** |
| Budget editor (dialog) | `feature/budget/src/main/java/com/ledgerflow/feature/budget/BudgetEditorDialog.kt:50` | Category, amount, period (inline chips), start date, alert switch | Add budget / Edit | **Note.** The budget-alert switch lives here until a Notifications page exists |
| Budget category / subcategory picker (dialog) | `feature/budget/src/main/java/com/ledgerflow/feature/budget/BudgetEditorDialog.kt:198` | Pick what the budget covers | Fields in the Budget editor | **OK** |
| Budget start date (dialog) | `feature/budget/src/main/java/com/ledgerflow/feature/budget/BudgetEditorDialog.kt:269` | Pick the start date | "Starts" in the Budget editor | **OK** |
| Back up now | `feature/settings/src/main/java/com/ledgerflow/feature/settings/backup/BackupNowScreen.kt:44` | Choose or change the backup folder (inline prompt), enter or scan the 24 words, back up, save a Recovery Kit | Settings "Back up now", Home backup reminder | **Gap.** No on-screen Back, only the system gesture |
| Export | `feature/export/src/main/java/com/ledgerflow/feature/export/ExportScreen.kt:50` | Export to Excel or CSV through the system save dialog | Settings "Export" | **OK.** Exported / Not exported results |
| Export without encryption? (dialog) | `feature/export/src/main/java/com/ledgerflow/feature/export/ExportScreen.kt:262` | Warns that the file is unencrypted | Starting an export | **OK** |
| Deleted entries | `feature/ledger/src/main/java/com/ledgerflow/feature/ledger/BinScreen.kt:65` | Restore or permanently erase deleted entries, both books | Settings "Deleted entries" | **OK** |
| Bin erase confirmation (dialog) | `feature/ledger/src/main/java/com/ledgerflow/feature/ledger/BinScreen.kt:351` | Confirm erasing for good, naming the count | Erase / Erase all in the bin | **OK** |
| Bin empty state | `feature/ledger/src/main/java/com/ledgerflow/feature/ledger/BinScreen.kt:123` | "Nothing deleted" | Empty bin | **OK** |
| Diagnostics | `feature/settings/src/main/java/com/ledgerflow/feature/settings/diagnostics/DiagnosticsScreen.kt:67` | What automatic capture did with each message; "Show in Inbox" | Settings "Diagnostics" | **OK.** Empty, full and troubled versions |
| Payment to review (notification) | `feature/ingest/src/main/java/com/ledgerflow/feature/ingest/notify/AndroidInboxNotifier.kt:102` | Approve / Review / Discard from the shade | Posted when a payment is captured | **OK.** A tap opens Review |
| "N payments to review" (notification) | `feature/ingest/src/main/java/com/ledgerflow/feature/ingest/notify/AndroidInboxNotifier.kt:166` | Group summary when more than 3 are waiting | Posted automatically | **OK.** A tap opens Inbox |
| Could not approve / discard (notification) | `feature/ingest/src/main/java/com/ledgerflow/feature/ingest/notify/InboxActionReceiver.kt` | Reports a failed action from the shade | A notification action that fails | **OK** |
| Budget alert (notification) | `feature/budget/src/main/java/com/ledgerflow/feature/budget/notify/BudgetNotifications.kt:60` | "Groceries at 80%" / "budget used up" | Posted after an approval crosses a threshold | **Gap.** Tapping it does nothing (no tap action), and it uses Android's stock warning icon |

## Not in the app

- **Entry detail / edit** for a saved entry: saved entries can only be deleted.
- **Receipts viewer**: images can only be deleted as a whole.
- **Licences / About**: `core/designsystem/.../theme/LfFonts.kt` says the Inter
  font licence waits for "a Settings → Licences screen … when one exists".
- **Login, profile, account**: not applicable; the app has no accounts.

## Unused code

None. Every screen and dialog above is called from somewhere, and every route in
`Destination.kt` has an entry point. Two comments are out of date
(`MainActivity.kt:273`, `LedgerFlowShell.kt:92` and `:478`).
