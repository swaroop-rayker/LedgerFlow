package com.ledgerflow.navigation

import kotlinx.serialization.Serializable

/**
 * Every destination in the app, as type-safe routes (SPEC.md §9.3).
 *
 * **Routes live in `:app`, not in the feature modules.** Feature modules expose
 * stateless screens plus navigation callbacks; the shell decides what those
 * callbacks do. If a feature owned its own route type, any feature that wanted
 * to navigate to another would have to depend on it -- which is exactly the
 * "features never depend on features" rule (CLAUDE.md §3) broken through the
 * back door. Keeping the graph in one place keeps that rule true for navigation
 * as well as for code.
 *
 * `@Serializable` objects rather than string routes: a typo in a string route
 * is a runtime crash, and arguments arrive as untyped strings.
 */
public sealed interface Destination {

    /** The four bottom-bar destinations, in bar order. */
    @Serializable
    public data object Dashboard : Destination

    @Serializable
    public data object Ledger : Destination

    @Serializable
    public data object Analytics : Destination

    /**
     * The fourth tab since design-system step D4 (DESIGN_SYSTEM_PLAN Q8,
     * `docs/V2-PLAN.md` D7 as amended). It replaced Settings in the bar, and
     * Settings is its first row.
     */
    @Serializable
    public data object Profile : Destination

    /**
     * The Settings index, reached from Profile: one row per section. Full-screen
     * since D4; it was the fourth tab from V2-2, when it replaced More.
     */
    @Serializable
    public data object Settings : Destination

    /**
     * One Settings section's page, reached from the index.
     *
     * [section] is a `SettingsPage` name. **The property name is a contract**,
     * as [Entry.draftId] is, and a string rather than the enum so the route
     * stays a plain serializable type; `settingsPageNamed` reads it back, and
     * `ProfileSettingsRoutesTest` fails if a page's name stops round-tripping.
     */
    @Serializable
    public data class SettingsSection(val section: String) : Destination

    /**
     * Full-screen, reached from the centre action — or from a pending row in
     * the Ledger, which opens one specific draft.
     *
     * **The property name is a contract.** Navigation Compose derives the
     * argument name from it, and `:feature:entry` reads that argument out of
     * its `SavedStateHandle` by string: it cannot reference this type, because
     * routes live in `:app` precisely so features never depend on each other.
     * `EntryViewModel.DRAFT_ID_ARG` is the other half; the two must agree, and
     * `EntryDraftArgumentTest` fails the build if they drift.
     *
     * Null means "a new entry", which is what the centre action asks for.
     *
     * [amount] is the calculator's "Use as amount" (V2-7): a new entry's
     * amount in minor units, as text. The same contract, with
     * `EntryViewModel.AMOUNT_ARG`.
     */
    @Serializable
    public data class Entry(val draftId: String? = null, val amount: String? = null) : Destination

    /**
     * Reached from Settings > Organise, and from Analytics' "Categorise in
     * Merchants" (V2-6), which opens it on [view] =
     * `CategoriesViewModel.VIEW_UNCATEGORISED_MERCHANTS`. **The property name is
     * a contract**, as [Inbox.filter] is: `CategoriesViewModel.VIEW_ARG` reads
     * it by string, and `OrganiseViewArgumentTest` fails the build if the two
     * drift. Null opens Organise as it always opened.
     */
    @Serializable
    public data class Categories(val view: String? = null) : Destination

    /**
     * One merchant's entries in one book and window, opened from a merchant in
     * Analytics (V2-6). [ledger] is a `LedgerType` name; [from] and [to] are
     * days since epoch, Analytics' window; [analyticsFiltered] says its filters
     * were on, which this page does not apply. **The property names are a
     * contract**: `MerchantEntriesViewModel` reads them by string, and
     * `MerchantEntriesArgumentTest` fails the build if they drift.
     */
    @Serializable
    public data class MerchantEntries(
        val ledger: String,
        val merchantId: String,
        val merchantName: String,
        val from: Int,
        val to: Int,
        val analyticsFiltered: Boolean,
    ) : Destination

    /** Budget management (§5.7). Reached from Settings, like Categories. */
    @Serializable
    public data object Budgets : Destination

    /** Savings goals (V2-7, V2-PLAN §4.1), reached from Profile's Money tools. */
    @Serializable
    public data object Goals : Destination

    /** One goal's page. The argument's name is `GoalViewModel.GOAL_ID_ARG`. */
    @Serializable
    public data class Goal(val goalId: String) : Destination

    /** Recurring items (V2-7, V2-PLAN §4.2), reached from Profile's Money tools. */
    @Serializable
    public data object Recurring : Destination

    /** One recurring item. The argument's name is `RecurringItemViewModel.ITEM_ID_ARG`. */
    @Serializable
    public data class RecurringItem(val itemId: String) : Destination

    /**
     * New recurring (empty [itemId]), Edit, or "Add" on a suggestion
     * ([fromMerchant] and [fromLedger], a `LedgerType` name).
     */
    @Serializable
    public data class RecurringEditor(
        val itemId: String = "",
        val fromMerchant: String = "",
        val fromLedger: String = "",
    ) : Destination

    /** The calculator (V2-7, V2-PLAN §4.4), reached from Profile's Money tools. */
    @Serializable
    public data object Calculator : Destination

    /** The currency converter (V2-7, ADR-0036), reached from Profile's Money tools. */
    @Serializable
    public data object Converter : Destination

    /** New goal (empty [goalId]) or Edit goal. */
    @Serializable
    public data class GoalEditor(val goalId: String = "") : Destination

    @Serializable
    public data object Export : Destination

    /** The bin, reached from Settings. Everything deleted, both books (ADR-0015). */
    @Serializable
    public data object DeletedEntries : Destination

    /**
     * "Back up now", reached from Settings (§16 Q23). Full-screen: it takes the 24
     * words, and a bottom bar under that is an invitation to wander off with
     * them half-typed.
     */
    @Serializable
    public data object BackUpNow : Destination

    /**
     * Restore from a backup over this phone's ledger (ADR-0033), reached from
     * Settings > Data. Full-screen for the same reason as [BackUpNow]: it takes
     * the 24 words.
     */
    /**
     * Import a statement into the Inbox (V2-4, D8), reached from Settings > Data.
     * Full-screen: three pages of its own, with nothing to wander off to.
     */
    @Serializable
    public data object ImportStatement : Destination

    @Serializable
    public data object RestoreBackup : Destination

    /** Google Drive keeps a copy of the nightly backup (ADR-0034), from Data. */
    @Serializable
    public data object GoogleDrive : Destination

    /** Data > Receipts (V2-5): browse by period, select and delete. */
    @Serializable
    public data object Receipts : Destination

    /**
     * One receipt's image and what it belongs to (V2-5).
     *
     * **The property name is a contract**, as [Entry.draftId] is:
     * `ReceiptViewModel.ATTACHMENT_ID_ARG` reads it by string, and
     * `ReceiptArgumentTest` fails the build if the two drift.
     */
    @Serializable
    public data class Receipt(val attachmentId: String) : Destination

    /**
     * The approval queue, reached from the centre action's dial (§9.3) — and
     * from the diagnostics screen, which opens it on the Suppressed filter.
     *
     * [filter] is an `InboxFilter` name, or null for the queue. **The property
     * name is a contract**, as [Entry.draftId] is: `InboxViewModel.FILTER_ARG`
     * reads it by string, and `InboxFilterArgumentTest` fails the build if the
     * two drift. A string rather than the enum so the route stays a plain
     * serializable type without a custom `NavType`.
     */
    @Serializable
    public data class Inbox(val filter: String? = null) : Destination

    /** Ingest diagnostics (P5), reached from Settings. */
    @Serializable
    public data object Diagnostics : Destination

    /** The notification allowlist editor (V2-3, V2-PLAN §3.4), reached from Settings. */
    @Serializable
    public data object PaymentApps : Destination

    /** What the app may use, why, and the way to Android's settings (V2-3, V2-PLAN §3.5), from Security & privacy. */
    @Serializable
    public data object DataPermissions : Destination

    /** What's new, the privacy policy or the terms (V2-3, V2-PLAN §3.8): `AboutDocument.name`. */
    @Serializable
    public data class AboutDocument(val document: String) : Destination

    /** Help & FAQ (V2-3), from About. */
    @Serializable
    public data object Help : Destination

    /** Report a bug (V2-3), from About: the user reviews every line before sharing. */
    @Serializable
    public data object ReportBug : Destination

    /** Open-source licences (V2-3, ADR-0032), from About. */
    @Serializable
    public data object Licences : Destination

    /** Organise > Merchants > Merchant categories (V2-6, V2-PLAN §5.1). */
    @Serializable
    public data object MerchantCategories : Destination

    /** One component's licences: `LicensedComponent.id`. */
    @Serializable
    public data class Licence(val component: String) : Destination

    /**
     * Receipt capture (§5.3), reached from the centre action's dial.
     *
     * Beside manual entry rather than behind a Settings row: it is one of the
     * four ways an entry begins, and §9.3 puts those on the centre action.
     */
    @Serializable
    public data object ScanReceipt : Destination

    /**
     * §5.2's permission explainer, reached from Settings and from the Dashboard
     * health banner.
     *
     * **Two entry points to one screen, and both are needed.** The banner is
     * where a user *notices* capture is not working; Settings is where they go
     * looking when nothing has told them. The same destination serves the
     * first-run presentation too, which is rendered outside this graph — the
     * vault has just been created and the shell does not exist yet — so the
     * screen is a route here and a direct call there, over one
     * `NotificationAccessRoute`.
     */
    @Serializable
    public data object NotificationAccess : Destination

    /**
     * Reviewing one candidate, reached from the Inbox — and at P2-7 from the
     * `ledgerflow://inbox/{pendingId}` deep link §5.1 specifies.
     *
     * **The property name is a contract**, exactly as [Entry.draftId] is.
     * Navigation Compose derives the argument name from it, and
     * `:feature:inbox` reads that argument out of its `SavedStateHandle` by
     * string because it cannot reference this type — routes live in `:app` so
     * features never depend on each other. `ReviewViewModel.PENDING_ID_ARG` is
     * the other half, and `InboxReviewArgumentTest` fails the build if the two
     * drift.
     */
    @Serializable
    public data class InboxReview(val pendingId: String) : Destination
}

/** The bottom bar's four destinations, in the order §9.3 specifies. */
internal val BottomBarDestinations: List<Destination> = listOf(
    Destination.Dashboard,
    Destination.Ledger,
    Destination.Analytics,
    Destination.Profile,
)
