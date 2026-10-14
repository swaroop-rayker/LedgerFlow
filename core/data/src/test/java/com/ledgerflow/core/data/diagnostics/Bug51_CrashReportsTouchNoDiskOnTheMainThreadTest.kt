package com.ledgerflow.core.data.diagnostics

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.diagnostics.CrashSummary
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **BUG51: the debug app crashed every time Home opened (2026-10-14).**
 *
 * Found on SM-S721B, Android 17, minutes after BUG7(c) shipped to the owner's
 * install: `DiskReadViolation` at `DefaultCrashReportRepository.<init>`. Hilt
 * builds the repository on the main thread while Home composes, and its
 * constructor resolved `context.filesDir` -- a disk read, which StrictMode's
 * penaltyDeath turns into a crash in every debug build. LF Bench (not
 * debuggable, so no StrictMode) did not crash, Robolectric does not enforce
 * StrictMode, and Home's ViewModel tests use a fake repository: nothing in the
 * suite constructed the real one.
 *
 * So these assert the property directly: construction resolves no folder, and
 * the first read happens on the injected `io` dispatcher, never on the thread
 * that collects.
 */
class Bug51_CrashReportsTouchNoDiskOnTheMainThreadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "io-test") }
    private val io = ioExecutor.asCoroutineDispatcher()
    private val resolvedOn = mutableListOf<String>()

    private fun repository(): DefaultCrashReportRepository = DefaultCrashReportRepository(
        directory = {
            resolvedOn += Thread.currentThread().name
            File(temp.root, CrashReportFiles.DIRECTORY)
        },
        openOutput = { null },
        clock = Clock { 0L },
        io = io,
    )

    @After
    fun tearDown() = ioExecutor.shutdown()

    @Test
    fun Bug51_constructingTheRepository_touchesNoDisk() {
        repository()
        assertThat(resolvedOn).isEmpty()
    }

    @Test
    fun Bug51_theFirstRead_happensOnIo_notOnTheCollectingThread(): Unit = runBlocking {
        CrashReportFiles(File(temp.root, CrashReportFiles.DIRECTORY)).write(atMillis = 5_000L, text = "report")
        val repository = repository()

        val summary = repository.summary.first()

        assertThat(summary).isEqualTo(CrashSummary(count = 1, latestAt = 5_000L))
        // Coroutines' debug mode appends " @coroutine#N" to the thread name.
        assertThat(resolvedOn).hasSize(1)
        assertThat(resolvedOn.single()).startsWith("io-test")
    }
}
