package com.ledgerflow.benchmark

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** The app under measurement: `:app`'s `benchmark` build type, smsFull. */
internal const val TARGET_PACKAGE = "com.ledgerflow.bench"

private const val SEED_ACTIVITY = "$TARGET_PACKAGE/com.ledgerflow.bench.BenchmarkSeedActivity"
// A fresh seed (~2,000 approvals) measured 7 s on SM-S721B; an already-seeded
// install answers at once. Three minutes is slack, not an estimate.
private const val SEED_TIMEOUT_MS = 3 * 60 * 1000L
// Generous: right after Macrobenchmark resets compilation the app runs
// interpreted. (An apparent 30 s "Unlocking" on 2026-09-29 was a stale node
// cache in this harness, not the app; see appRoots.)
private const val UI_TIMEOUT_MS = 30_000L
private const val FIRST_RUN_SHEET_MS = 5_000L
private const val POLL_MS = 250L
private const val MAX_WALK_DEPTH = 40
private const val SWIPE_STEPS = 6
private const val EDGE_DIVISOR = 5

/*
 * **Why this file reads the accessibility tree itself instead of using
 * UiAutomator's selectors.** Measured 2026-09-29 on SM-S721B, with UiAutomator
 * 2.3.0 (what benchmark-macro 1.4.1 brings) against this Compose: every
 * `findObject(By...)` missed every Compose node for minutes, and
 * `dumpWindowHierarchy` stopped at the window's frames. Walking the same
 * connection's raw `AccessibilityNodeInfo` tree found all of it, the Compose
 * view and the text inside, with every declared child returned. So the nodes
 * are there and not withheld; the selector layer skips them. The walk also
 * needs the node cache cleared before every look (see [appRoots]); clearing
 * the cache alone, with UiAutomator's selectors, was tried first and was not
 * enough, and neither was declaring a feedback type on the connection.
 */

/**
 * Makes sure the benchmark install has its throwaway vault and five years of
 * synthetic entries (`BenchmarkSeedActivity`, `:app`'s src/benchmark). A fresh
 * install is seeded through the approval path; every later run finds the
 * marker and returns at once.
 */
internal fun MacrobenchmarkScope.ensureSeeded() {
    device.executeShellCommand("am start -W -n $SEED_ACTIVITY")
    val seeded = awaitAppNode(SEED_TIMEOUT_MS) { it.text?.startsWith("Seeded") == true }
    checkNotNull(seeded) { "The benchmark vault was not seeded. On screen: ${screenText()}" }
    killProcess()
    dismissFirstRunSheet()
}

/**
 * A fresh install opens on the notification-capture sheet, over the tabs. It is
 * answered once, as a user would ("Not now"), and stays answered, so every
 * measured start after this one opens on Home.
 */
private fun MacrobenchmarkScope.dismissFirstRunSheet() {
    startActivityAndWait()
    val notNow = awaitAppNode(FIRST_RUN_SHEET_MS) { it.text?.toString() == "Not now" }
    if (notNow != null) {
        val bounds = Rect().also(notNow::getBoundsInScreen)
        device.click(bounds.centerX(), bounds.centerY())
        device.waitForIdle()
    }
    killProcess()
}

/** Taps a bottom-navigation tab by its label. */
internal fun UiDevice.openTab(label: String) {
    val tab = checkNotNull(awaitAppNode(UI_TIMEOUT_MS) { it.text?.toString() == label }) {
        "No \"$label\" tab on screen: ${screenText()}"
    }
    val bounds = Rect().also(tab::getBoundsInScreen)
    click(bounds.centerX(), bounds.centerY())
    waitForIdle()
}

/** The screen's scrolling list's bounds, once it has appeared. */
internal fun UiDevice.scrollingList(): Rect {
    val list = checkNotNull(awaitAppNode(UI_TIMEOUT_MS) { it.isScrollable }) {
        "No scrollable list on screen: ${screenText()}"
    }
    return Rect().also(list::getBoundsInScreen)
}

/**
 * Flings a list down and back up, the gesture the frame budget is written for.
 * A short, fast swipe is a fling to the list; it stays clear of the screen's
 * edges, which the system claims for back.
 */
internal fun Rect.flingDownAndUp(device: UiDevice, times: Int) {
    val x = centerX()
    val low = bottom - height() / EDGE_DIVISOR
    val high = top + height() / EDGE_DIVISOR
    repeat(times) {
        device.swipe(x, low, x, high, SWIPE_STEPS)
        device.waitForIdle()
    }
    repeat(times) {
        device.swipe(x, high, x, low, SWIPE_STEPS)
        device.waitForIdle()
    }
}

/** Polls the app's windows for a node matching [predicate]. */
internal fun awaitAppNode(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        appRoots().firstNotNullOfOrNull { find(it, predicate, 0) }?.let { return it }
        if (SystemClock.uptimeMillis() >= deadline) return null
        SystemClock.sleep(POLL_MS)
    }
}

private fun appRoots(): List<AccessibilityNodeInfo> {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    // Without this the walk reads a cached tree: on 2026-09-29 it reported
    // "Unlocking" for 30 s after the app had opened its vault and moved on
    // (SQLCipher keyed in < 0.5 s, per the app's own log). API 34+.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
    val fromWindows = automation.windows.mapNotNull { it.root }.filter { it.packageName == TARGET_PACKAGE }
    return fromWindows.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
}

private fun find(
    node: AccessibilityNodeInfo,
    predicate: (AccessibilityNodeInfo) -> Boolean,
    depth: Int,
): AccessibilityNodeInfo? {
    if (predicate(node)) return node
    if (depth >= MAX_WALK_DEPTH) return null
    return (0 until node.childCount).firstNotNullOfOrNull { i ->
        node.getChild(i)?.let { find(it, predicate, depth + 1) }
    }
}

/** Every piece of text the app shows, for a failure message. */
private fun screenText(): List<String> {
    val found = mutableListOf<String>()
    appRoots().forEach { root ->
        find(root, { node -> node.text?.let { found += it.toString() }; false }, 0)
    }
    return found
}
