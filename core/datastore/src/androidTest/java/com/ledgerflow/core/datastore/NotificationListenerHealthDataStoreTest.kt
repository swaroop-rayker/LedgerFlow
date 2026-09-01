package com.ledgerflow.core.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The durability property ADR-0020 chose this store for, on a real file.
 *
 * Option A in that ADR — `app_meta`, inside the vault — was rejected because the
 * writer is `onListenerConnected`, which runs at boot in a process with no
 * Activity and often before anything can be unlocked. This suite exercises the
 * half of that argument a JVM test cannot.
 *
 * ## What "durable" is checked against, and why not a second instance
 *
 * The obvious test — write through one [ListenerHealthDataStore], read through a
 * fresh one — **proves nothing**, and it took writing it to see why.
 * `preferencesDataStore` is a delegate on `Context`, so every instance
 * constructed against the same context shares one underlying `DataStore`, and
 * `data.first()` is served from that object's in-memory cache. A value never
 * written to disk at all would sail through it. That is precisely the shape the
 * P2-8 kickoff's §2.5 warns about: a green assertion that is not asking the
 * question in its own name.
 *
 * So durability is asserted against **the bytes on disk**. Preferences stores
 * key names as plain strings in its protobuf, so finding the key in the file is
 * direct evidence the write landed — and reading the path also pins Law 5:
 * `filesDir`, never `cacheDir`.
 */
@RunWith(AndroidJUnit4::class)
class NotificationListenerHealthDataStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Where `preferencesDataStore(name = "listener_health")` puts it. */
    private val storeFile: File
        get() = File(context.filesDir, "datastore/listener_health.preferences_pb")

    private fun healthStore() = ListenerHealthDataStore(context)

    private fun setupStore() = NotificationSetupDataStore(context)

    /**
     * Law 5: persistent data lives in `filesDir`.
     *
     * Asserted on the real path rather than trusted from the delegate's
     * documentation, because the whole point of this module is that it is the
     * one persistent thing outside the vault — so where it sits is a fact worth
     * a test rather than a fact worth a comment.
     */
    @Test
    fun theStore_livesInFilesDirAndNotInCacheDir() = runTest {
        healthStore().recordConnected(1_700_000_111_000L)

        assertThat(storeFile.isFile).isTrue()
        assertThat(storeFile.canonicalPath).startsWith(context.filesDir.canonicalPath)
        assertThat(storeFile.canonicalPath).doesNotContain(context.cacheDir.canonicalPath)
    }

    /**
     * The banner's whole reason for existing: the timestamp reaches the disk.
     *
     * Checked in the file's bytes, not through a second reader — see the class
     * KDoc for why a second reader would have proved nothing.
     */
    @Test
    fun recordConnected_writesTheKeyToDisk() = runTest {
        healthStore().recordConnected(1_700_000_111_000L)

        assertThat(storeFile.readBytes().decodeToString())
            .contains("listener_last_connected_at")
    }

    @Test
    fun recordDisconnected_writesTheKeyToDisk() = runTest {
        healthStore().recordDisconnected(1_700_000_222_000L)

        assertThat(storeFile.readBytes().decodeToString())
            .contains("listener_last_disconnected_at")
    }

    @Test
    fun recordConnected_isReadBackWithItsValue() = runTest {
        val at = 1_700_000_333_000L
        healthStore().recordConnected(at)

        assertThat(healthStore().current().lastConnectedAt).isEqualTo(at)
    }

    /** `connected` flips on the instance that was told. */
    @Test
    fun connected_flipsOnTheInstanceThatWasTold() = runTest {
        val store = healthStore()

        store.recordConnected(1_700_000_444_000L)
        assertThat(store.current().connected).isTrue()

        store.recordDisconnected(1_700_000_445_000L)
        assertThat(store.current().connected).isFalse()
    }

    /**
     * A fresh instance starts disconnected however recent the timestamp is.
     *
     * **This is the assertion that proves `connected` is not persisted**, and it
     * is worth naming why the more obvious version was not used: asserting that
     * the file's bytes do not contain "connected" looks stronger and is in fact
     * broken, because `listener_last_connected_at` contains that substring. It
     * would have failed for a reason having nothing to do with the property, and
     * the temptation on seeing it fail is to weaken it until it passes.
     *
     * A persisted flag is what this rules out, and it matters because it would
     * say "connected" forever after the exact event the banner exists to report
     * — the OEM battery manager §5.2 names does not send a callback on its way
     * out.
     */
    @Test
    fun aFreshStore_startsDisconnectedHoweverRecentTheTimestamp() = runTest {
        val at = 1_700_000_555_000L
        healthStore().recordConnected(at)

        val fresh = healthStore().current()

        assertThat(fresh.connected).isFalse()
        // The timestamp *did* survive, so this is not passing because the read
        // came back empty.
        assertThat(fresh.lastConnectedAt).isEqualTo(at)
    }

    /** [ListenerHealthStore.record] emits the same picture the one-shot read gives. */
    @Test
    fun record_emitsTheSameStateAsCurrent() = runTest {
        val store = healthStore()
        store.recordConnected(1_700_000_666_000L)

        assertThat(store.record.first()).isEqualTo(store.current())
    }

    /**
     * The first observation wins.
     *
     * The value is a reference point for measuring staleness, so re-stamping on
     * every poll would reset the interval it exists to measure — a listener that
     * never binds would read as freshly granted forever. Written twice, the
     * second time with a later value, because a naive implementation passes a
     * single-call test.
     */
    @Test
    fun recordGrantObserved_keepsTheFirstObservation() = runTest {
        val first = 1_700_000_777_000L
        val store = healthStore()

        store.recordGrantObserved(first)
        store.recordGrantObserved(first + 90_000_000L)

        assertThat(store.current().grantObservedAt).isEqualTo(first)
    }

    /**
     * The explainer flag, in the same file and independent of the health record.
     *
     * Asserted together because they share a preferences file: a write to one
     * that clobbered the other would be invisible to either store's own tests.
     */
    @Test
    fun markSetupSeen_survivesAndLeavesTheHealthRecordAlone() = runTest {
        val at = 1_700_000_888_000L
        healthStore().recordConnected(at)

        setupStore().markSetupSeen()

        assertThat(setupStore().hasSeenSetup()).isTrue()
        assertThat(healthStore().current().lastConnectedAt).isEqualTo(at)
    }
}
