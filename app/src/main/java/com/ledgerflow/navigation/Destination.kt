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

    @Serializable
    public data object More : Destination

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
     */
    @Serializable
    public data class Entry(val draftId: String? = null) : Destination

    /** Reached from More. */
    @Serializable
    public data object Categories : Destination

    @Serializable
    public data object Export : Destination

    /** The bin, reached from More. Everything deleted, both books (ADR-0015). */
    @Serializable
    public data object DeletedEntries : Destination

    /** The approval queue, reached from the centre action's dial (§9.3). */
    @Serializable
    public data object Inbox : Destination

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
    Destination.More,
)
