package com.ledgerflow.core.rates

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **BUG92: the debug app crashed every time the converter opened (2026-11-10).**
 *
 * Found by the owner on the SM-S721B, running TESTING K10 on the playSafe
 * copy: `DiskReadViolation` at `EcbRatesSource.<init>`. Hilt builds the source
 * on the main thread when the converter's ViewModel is made, and its
 * constructor resolved `context.filesDir`, a disk read that StrictMode's
 * penaltyDeath turns into a crash in every debug build. It is BUG51 again, in
 * a new module: the converter's tests use a fake source, and this module's own
 * tests handed the constructor a folder, so nothing in the suite constructed
 * the real one the way Hilt does, and Robolectric does not enforce StrictMode.
 *
 * So these assert the property directly, as BUG51's test does: construction
 * resolves no folder, and the first read happens on the injected `io`
 * dispatcher, never on the thread that asks.
 */
class Bug92_ConverterTouchesNoDiskOnTheMainThreadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "io-test") }
    private val io = ioExecutor.asCoroutineDispatcher()
    private val resolvedOn = mutableListOf<String>()

    private fun source() = EcbRatesSource(
        address = "http://127.0.0.1:1/unused",
        directory = {
            resolvedOn += Thread.currentThread().name
            File(temp.root, "rates")
        },
        io = io,
    )

    @After
    fun tearDown() = ioExecutor.shutdown()

    @Test
    fun Bug92_constructingTheSource_touchesNoDisk() {
        source()
        assertThat(resolvedOn).isEmpty()
    }

    @Test
    fun Bug92_theFolderIsResolvedOnIo_once() = runBlocking<Unit> {
        val source = source()

        source.cached()
        source.cached()

        // Once, and on the io thread (coroutine debug mode appends "@coroutine#n" to its name).
        assertThat(resolvedOn).hasSize(1)
        assertThat(resolvedOn.single()).startsWith("io-test")
    }
}
