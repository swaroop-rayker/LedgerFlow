package com.ledgerflow.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shipped baseline profile (`app/src/main/baseline-prof.txt`), recorded
 * from the journeys a user makes daily: start, the Ledger scrolled, Analytics
 * opened. Run with `.\gradlew generateBaselineProfile`, which copies the
 * output into place; review the diff before committing it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        ensureSeeded()
        pressHome()
        startActivityAndWait()
        device.openTab("Ledger")
        device.scrollingList().flingDownAndUp(device, times = 2)
        device.openTab("Analytics")
        device.waitForIdle()
        device.openTab("Home")
    }
}
