plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.compose")
    // §12's screenshot gate. `LfLineItemEditor` is the component §5.3's receipt
    // review is built on and it had no golden at any font scale -- the harness
    // lived only in :core:designsystem, which this module depends on and which
    // therefore cannot see it. A composite that renders a whole editable row is
    // exactly the shape BUG5 and BUG9 break, so it is the wrong thing to have
    // outside the gate.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ledgerflow.core.ui"

    testOptions.unitTests {
        // Robolectric needs the merged resources and the manifest; without this
        // every composition fails at inflate time rather than at an assertion,
        // which reads like a broken test rather than a missing setting.
        isIncludeAndroidResources = true

        // Forwarded, not hardcoded -- see the identical block in
        // :core:designsystem for why. Robolectric's fork does not inherit the
        // daemon's truststore, and on this dev box an intercepting proxy makes
        // its platform-jar download fail where Gradle's own resolution
        // succeeds. Absent on a stock runner, where this loop does nothing.
        all { test ->
            listOf(
                "javax.net.ssl.trustStore",
                "javax.net.ssl.trustStorePassword",
            ).forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
}

dependencies {
    // Shared composites are built out of the design system's atoms and use its
    // tokens for every colour, dimension and type size (CLAUDE.md §5).
    //
    // Nothing here depends on :core:model or :core:domain, and that is the
    // property worth keeping: a composite in this module renders values a host
    // has already resolved -- names, formatted amounts -- so it stays usable
    // from any feature without dragging a domain vocabulary along with it.
    implementation(project(":core:designsystem"))

    // Reading a Recovery Kit's QR code instead of typing 24 words (ADR-0028).
    // The camera stack is already in the APK for receipt capture and needs no
    // new permission; ZXing decodes the frame in memory. The shared phrase
    // entry lives here, so the scanner does too -- one implementation, and the
    // three phrase screens cannot drift apart on it.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    // The camera permission request; the scanner asks for it itself rather
    // than making every host screen carry a launcher.
    implementation(libs.androidx.activity.compose)
    implementation(libs.zxing.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
