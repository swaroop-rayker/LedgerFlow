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

    /** Full-screen, reached from the centre action. */
    @Serializable
    public data object Entry : Destination

    /** Reached from More. */
    @Serializable
    public data object Categories : Destination

    @Serializable
    public data object Export : Destination
}

/** The bottom bar's four destinations, in the order §9.3 specifies. */
internal val BottomBarDestinations: List<Destination> = listOf(
    Destination.Dashboard,
    Destination.Ledger,
    Destination.Analytics,
    Destination.More,
)
