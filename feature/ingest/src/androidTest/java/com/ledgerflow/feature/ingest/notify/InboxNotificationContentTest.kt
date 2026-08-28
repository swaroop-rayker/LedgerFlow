package com.ledgerflow.feature.ingest.notify

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.inbox.PendingTransaction
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.usecase.GetPendingUseCase
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.testing.inbox.FakePendingRepository
import com.ledgerflow.core.testing.ledger.FakeLedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What §5.1's notification actually **is**, once a real device has built it.
 *
 * The unit tests cover *which* candidates get announced;
 * `InboxNotificationChannelTest` covers the channel. Neither can see the
 * notification itself, because `NotificationCompat.Builder` off a device
 * produces an object nobody has rendered and `activeNotifications` does not
 * exist. This drives the real [AndroidInboxNotifier] and then reads back what
 * the platform is holding.
 *
 * **It exists because the alternative is a real bank payment.** `adb` cannot
 * deliver an SMS — `BROADCAST_SMS` is signature-level — so the end-to-end path
 * can only be exercised by the owner spending money. Everything downstream of
 * "a candidate exists" is checkable here instead, which leaves that manual run
 * to prove capture rather than to discover that an action was wired to the
 * wrong `PendingIntent`.
 *
 * That is not hypothetical: `[Review]` was first written as a broadcast aimed
 * at [InboxActionReceiver], which handles only the two actions that write. It
 * would have done nothing at all, and no unit test could have seen it.
 */
@RunWith(AndroidJUnit4::class)
class InboxNotificationContentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))

    private val pending = FakePendingRepository()
    private val ledger = FakeLedgerRepository()
    private lateinit var notifier: AndroidInboxNotifier

    @Before
    fun setUp() {
        // The grant, without androidx.test:rules. POST_NOTIFICATIONS is declared
        // in this source set's own manifest (see it for why) but a runtime
        // permission still has to be granted, and `connectedAndroidTest` does not
        // pass `-g`. UiAutomation is platform API and needs no new dependency.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        clearOurNotifications()
        InboxNotifications.ensureChannel(context)
        notifier = AndroidInboxNotifier(context, GetPendingUseCase(pending), ledger)
    }

    @After
    fun tearDown() = clearOurNotifications()

    private fun clearOurNotifications() {
        manager.activeNotifications.forEach { manager.cancel(it.id) }
    }

    private fun candidate(
        id: String,
        amount: Money? = Money(6_900L),
        direction: ExtractedDirection = ExtractedDirection.DEBIT,
        merchant: String? = "SWIGGY",
        needsManualFill: Boolean = false,
    ) = PendingTransaction(
        id = id,
        source = EntrySource.SMS,
        extracted = ExtractedTransaction(
            amount = amount,
            currency = "INR",
            direction = direction,
            merchantRaw = merchant,
            confidence = if (needsManualFill) 0.0 else 0.9,
        ),
        confidence = if (needsManualFill) 0.0 else 0.9,
        status = PendingStatus.PENDING,
        needsManualFill = needsManualFill,
        suppressedById = null,
        createdAt = 1_700_000_000_000L,
        reviewedAt = null,
        approvedEntryId = null,
    )

    /**
     * The posted notification, once the system server admits to holding it.
     *
     * **Polled rather than read once**, because `notify()` is a *oneway* binder
     * call handed to a handler inside NotificationManagerService while
     * `activeNotifications` is a synchronous read of that service's state. A
     * bare read after `notify()` returns is a race, and it is the race that
     * first showed up here as four tests failing and five passing on the same
     * code path. The production fix is in `updateGroupSummary`; this is the
     * test's own half of the same truth.
     *
     * Returns null after the timeout rather than throwing, so "nothing was
     * posted" stays a thing a test can assert.
     */
    private fun awaitPosted(id: String, timeoutMillis: Long = 3_000L): StatusBarNotification? {
        val target = InboxNotifications.notificationId(id)
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            manager.activeNotifications.firstOrNull { it.id == target }?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    /** The group summary, on the same terms. */
    private fun awaitSummary(timeoutMillis: Long = 3_000L): StatusBarNotification? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            manager.activeNotifications
                .firstOrNull { it.id == InboxNotifications.SUMMARY_ID }
                ?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    /** Absence, after a `cancel()` that is also a oneway call. */
    private fun awaitGone(id: String, timeoutMillis: Long = 3_000L): Boolean {
        val target = InboxNotifications.notificationId(id)
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (manager.activeNotifications.none { it.id == target }) return true
            SystemClock.sleep(POLL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    /**
     * That something is **absent**, which a poll cannot prove by waiting.
     *
     * Given one settle interval and then asserted once. Any longer would only
     * make a passing test slower; the negative cases here are about state the
     * code never creates, not state it creates late.
     */
    private fun settle() = SystemClock.sleep(SETTLE_MILLIS)

    private companion object {
        const val POLL_MILLIS = 50L
        const val SETTLE_MILLIS = 750L
    }

    private fun actionLabels(sbn: StatusBarNotification): List<String> =
        sbn.notification.actions.orEmpty().map { it.title.toString() }

    // ── What lands in the shade ─────────────────────────────────────────────

    @Test
    fun notifyCandidate_postsOnTheChannelFiveOneNames() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")

        val sbn = requireNotNull(awaitPosted("p1")) { "nothing was posted" }
        assertThat(sbn.notification.channelId).isEqualTo("inbox_high")
        assertThat(sbn.notification.group).isEqualTo(InboxNotifications.GROUP_KEY)
    }

    /**
     * The amount and merchant are on the notification, in base-currency form.
     *
     * `MoneyFormat` is what renders it, so this also catches the day someone
     * hands the builder raw minor units — ₹69.00 becoming "6900" is the kind of
     * thing that looks fine in code and absurd on a phone.
     */
    @Test
    fun notifyCandidate_showsTheAmountAndMerchant() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")

        val extras = requireNotNull(awaitPosted("p1")).notification.extras
        assertThat(extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .contains("69.00")
        assertThat(extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("SWIGGY")
    }

    /** §7c: the shade has no snackbar, so the 30 days are stated. */
    @Test
    fun notifyCandidate_statesThatDiscardingIsReversible() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")

        val big = requireNotNull(awaitPosted("p1")).notification.extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertThat(big).contains("30 days")
    }

    // ── The actions ─────────────────────────────────────────────────────────

    @Test
    fun notifyCandidate_whenApprovable_offersAllThreeActions() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")

        assertThat(actionLabels(requireNotNull(awaitPosted("p1"))))
            .containsExactly("Approve", "Review", "Discard")
            .inOrder()
    }

    /**
     * §5.1's never-drop row gets no `[Approve]`.
     *
     * There is no amount and no direction to approve *from*, so the action
     * could only ever fail. It still gets a notification — that row is the one
     * the user most needs to know about, because nothing was extracted and
     * nothing else will remind them it happened.
     */
    @Test
    fun notifyCandidate_whenNotApprovable_offersReviewAndDiscardOnly() = runBlocking {
        pending.put(
            candidate(
                "p2",
                amount = null,
                direction = ExtractedDirection.UNKNOWN,
                merchant = null,
                needsManualFill = true,
            ),
        )

        notifier.notifyCandidate("p2")

        val sbn = requireNotNull(awaitPosted("p2")) { "a never-drop row must still notify" }
        assertThat(actionLabels(sbn)).containsExactly("Review", "Discard").inOrder()
    }

    /**
     * **`[Review]` opens the Activity; the other two are broadcasts.**
     *
     * The regression this pins actually happened: `[Review]` was built as a
     * broadcast aimed at [InboxActionReceiver], which handles only `APPROVE`
     * and `DISCARD`. The button would have been completely inert, and the
     * failure is invisible from every other kind of test — the notification
     * looks perfect right up until you tap it.
     */
    @Test
    fun notifyCandidate_reviewOpensAnActivityRatherThanABroadcast() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")

        val actions = requireNotNull(awaitPosted("p1")).notification.actions.orEmpty()
        val review = actions.single { it.title.toString() == "Review" }
        val discard = actions.single { it.title.toString() == "Discard" }

        assertThat(review.actionIntent.isActivity).isTrue()
        assertThat(discard.actionIntent.isBroadcast).isTrue()
    }

    /** The body tap is the deep link, not a bare launch. */
    @Test
    fun notifyCandidate_bodyTapIsTheDeepLink() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")

        assertThat(requireNotNull(awaitPosted("p1")).notification.contentIntent.isActivity).isTrue()
    }

    // ── Privacy, and grouping ───────────────────────────────────────────────

    /**
     * A lock screen shows a count, never a payment.
     *
     * The fields a bank message carries are exactly the ones §5.2 exists to
     * keep private, and a lock screen shows them to whoever is holding the
     * phone. Asserted on the public version's own extras rather than on
     * `visibility` alone, because `VISIBILITY_PRIVATE` with a public version
     * that leaked the merchant would still pass a visibility check.
     */
    @Test
    fun notifyCandidate_lockScreenVersionNamesNoMerchantOrAmount() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")

        val notification = requireNotNull(awaitPosted("p1")).notification
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)

        val public = requireNotNull(notification.publicVersion) {
            "VISIBILITY_PRIVATE with no public version lets the system invent one"
        }
        val shown = listOfNotNull(
            public.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        ).joinToString(" ")
        assertThat(shown).doesNotContain("SWIGGY")
        assertThat(shown).doesNotContain("69")
    }

    /** §5.1: grouped past three, and not before. */
    @Test
    fun notifyCandidate_summaryAppearsOnlyPastThree() = runBlocking {
        listOf("p1", "p2", "p3").forEach {
            pending.put(candidate(it))
            notifier.notifyCandidate(it)
        }
        settle()
        assertThat(manager.activeNotifications.map { it.id })
            .doesNotContain(InboxNotifications.SUMMARY_ID)

        pending.put(candidate("p4"))
        notifier.notifyCandidate("p4")

        val summary = awaitSummary()
        assertThat(summary).isNotNull()
        assertThat(requireNotNull(summary).notification.flags and Notification.FLAG_GROUP_SUMMARY)
            .isNotEqualTo(0)
    }

    /** Cancelling takes it back out of the shade, which is §3.1's flip. */
    @Test
    fun cancelCandidate_removesItFromTheShade() = runBlocking {
        pending.put(candidate("p1"))
        notifier.notifyCandidate("p1")
        assertThat(awaitPosted("p1")).isNotNull()

        notifier.cancelCandidate("p1")

        assertThat(awaitGone("p1")).isTrue()
    }

    /**
     * Re-posting the same candidate replaces rather than stacks.
     *
     * The parse pass is idempotent and re-runs routinely; a notification per
     * WorkManager wake would turn one payment into a stream.
     */
    @Test
    fun notifyCandidate_postedTwice_leavesOneNotification() = runBlocking {
        pending.put(candidate("p1"))

        notifier.notifyCandidate("p1")
        notifier.notifyCandidate("p1")
        settle()

        assertThat(
            manager.activeNotifications.count {
                it.id == InboxNotifications.notificationId("p1")
            },
        ).isEqualTo(1)
    }
}
