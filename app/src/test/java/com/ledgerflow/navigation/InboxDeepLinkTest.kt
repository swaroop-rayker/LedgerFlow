package com.ledgerflow.navigation

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.feature.inbox.ReviewViewModel
import org.junit.Test

/**
 * §5.1's `ledgerflow://inbox/{pendingId}`, parsed. P2-7.
 *
 * The link is a **contract between two modules that cannot see each other**:
 * `:feature:ingest` builds the `PendingIntent` from a string prefix, `:app` owns
 * the route it lands on, and neither may depend on the other (CLAUDE.md §3).
 * `InboxReviewArgumentTest` guards the other seam in the same chain — the
 * argument *name* — and this guards the URI shape.
 *
 * A JVM test rather than an instrumented one because [InboxDeepLink] takes a
 * `String`: `android.net.Uri` is a stub that throws off-device, and making the
 * parser depend on it would have pushed this whole file onto the phone for no
 * behaviour that needs one.
 */
class InboxDeepLinkTest {

    // ── What the notification actually builds ───────────────────────────────

    @Test
    fun parse_aCandidateLink_routesToThatCandidatesReviewScreen() {
        val destination = InboxDeepLink.parse("ledgerflow://inbox/01a046b9-d35c-7c74-9254-90")

        assertThat(destination).isEqualTo(
            Destination.InboxReview("01a046b9-d35c-7c74-9254-90"),
        )
    }

    /** The group summary's target: it stands for several, so it names none. */
    @Test
    fun parse_theBareInboxLink_routesToTheQueue() {
        assertThat(InboxDeepLink.parse("ledgerflow://inbox")).isEqualTo(Destination.Inbox)
        assertThat(InboxDeepLink.parse("ledgerflow://inbox/")).isEqualTo(Destination.Inbox)
    }

    /**
     * The prefix the notification is built from is the prefix this parses.
     *
     * Both halves of the contract are in this repository and neither can see the
     * other, so the only thing keeping them together is that they are asserted
     * against the same literal. `AndroidInboxNotifier.DEEP_LINK_PREFIX` is
     * `"$PREFIX/"`; if either moves, this fails.
     */
    @Test
    fun prefix_isTheOneTheNotificationBuildsFrom() {
        assertThat(InboxDeepLink.PREFIX).isEqualTo("ledgerflow://inbox")
        assertThat(InboxDeepLink.parse("${InboxDeepLink.PREFIX}/p1"))
            .isEqualTo(Destination.InboxReview("p1"))
    }

    /**
     * The review destination's argument name, end to end.
     *
     * A link can resolve perfectly and still land on a screen that cannot read
     * its own id. `ReviewViewModel` pulls the argument out of a
     * `SavedStateHandle` by string, and Navigation Compose derives that string
     * from the property name — so this asserts the whole chain the notification
     * tap depends on, not just the URI half.
     */
    @Test
    fun parse_producesADestinationWhoseArgumentTheReviewScreenCanRead() {
        val destination = InboxDeepLink.parse("ledgerflow://inbox/p1")

        assertThat(destination).isInstanceOf(Destination.InboxReview::class.java)
        val fields = requireNotNull(destination).javaClass.declaredFields.map { it.name }
        assertThat(fields).contains(ReviewViewModel.PENDING_ID_ARG)
    }

    // ── What must not route ─────────────────────────────────────────────────

    /**
     * An unrecognised link leaves the user where they are.
     *
     * Null rather than a fallback to the Dashboard: a deep link the app does not
     * understand should not silently move someone who was in the middle of
     * something.
     */
    @Test
    fun parse_somethingElse_routesNowhere() {
        assertThat(InboxDeepLink.parse(null)).isNull()
        assertThat(InboxDeepLink.parse("")).isNull()
        assertThat(InboxDeepLink.parse("https://example.com/inbox/p1")).isNull()
        assertThat(InboxDeepLink.parse("ledgerflow://ledger/p1")).isNull()
        // Close, but not ours: a host that merely starts with "inbox".
        assertThat(InboxDeepLink.parse("ledgerflow://inboxes/p1")).isNull()
    }

    /**
     * A second path segment is not a candidate id.
     *
     * A `pendingId` is a UUIDv7 and contains no slash, so anything with one is a
     * link this app did not write. Routing it would hand `ReviewViewModel` an
     * id it will never find and produce a screen that says a real candidate is
     * missing.
     */
    @Test
    fun parse_aDeeperPath_routesNowhere() {
        assertThat(InboxDeepLink.parse("ledgerflow://inbox/p1/edit")).isNull()
    }

    /**
     * A query or fragment is trimmed rather than folded into the id.
     *
     * Nothing we build appends one, but a link arriving from anywhere else
     * would otherwise produce a `pendingId` of `p1?utm=x` that matches no row —
     * a tap that appears to do nothing, which is the failure mode this whole
     * step exists to remove.
     */
    @Test
    fun parse_aQueryOrFragment_isNotPartOfTheId() {
        assertThat(InboxDeepLink.parse("ledgerflow://inbox/p1?from=test"))
            .isEqualTo(Destination.InboxReview("p1"))
        assertThat(InboxDeepLink.parse("ledgerflow://inbox/p1#top"))
            .isEqualTo(Destination.InboxReview("p1"))
    }
}
