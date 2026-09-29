package com.ledgerflow.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG38: receipt OCR failed in every release build, and no test could see it.
 *
 * R8 removed the no-argument constructor of ML Kit's
 * `CommonComponentRegistrar`, which component discovery instantiates
 * reflectively; the first recognition then threw a NullPointerException.
 * Every existing OCR test runs unshrunk code, where the constructor is always
 * there. This one runs `OcrProbeActivity` in `com.ledgerflow.bench` -- release
 * code with release's R8 rules -- and requires the app's own recognizer to
 * read a drawn line.
 *
 * Measured 2026-09-29 on SM-S721B: without the keep rule in
 * `app/proguard-rules.pro` the probe reports `OCR failed:
 * NullPointerException`; with it, the drawn text.
 *
 * Not a Macrobenchmark: it measures nothing, so it is not filtered out of a
 * `generateBaselineProfile` run and costs one launch in a benchmark run.
 */
@RunWith(AndroidJUnit4::class)
class Bug38_OcrWorksInAShrunkBuildTest {

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun theAppsRecognizerReadsTextInAShrunkBuild() {
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/com.ledgerflow.bench.OcrProbeActivity")
        val result = checkNotNull(
            awaitAppNode(PROBE_TIMEOUT_MS) { it.text?.startsWith("OCR ") == true && it.text?.startsWith("OCR probe") != true },
        ) { "The OCR probe reported nothing" }.text.toString()

        check(result.startsWith("OCR ok")) { "OCR in a shrunk build: $result" }
        check("245" in result) { "OCR read the wrong text: $result" }
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 30_000L
    }
}
