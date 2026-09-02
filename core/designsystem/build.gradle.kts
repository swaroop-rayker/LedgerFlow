plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.compose")
    // §12's screenshot gate. Roborazzi runs on Robolectric, so this is a JVM
    // unit test rather than a device one -- which is what lets CI run it at all
    // (the `screenshot` job is `ubuntu-latest` with no emulator).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ledgerflow.core.designsystem"

    testOptions.unitTests {
        // Robolectric needs the merged resources and the manifest; without this
        // every composition fails at inflate time rather than at an assertion,
        // which reads like a broken test rather than a missing setting.
        isIncludeAndroidResources = true

        // Robolectric downloads its platform jar with its OWN http client, in
        // the forked test JVM, rather than through Gradle -- so a machine whose
        // TLS is intercepted (this dev box runs Norton) fails here with
        // `SunCertPathBuilderException: unable to find valid certification
        // path` while Gradle resolves from the very same host without trouble.
        // The daemon already has the right truststore from a systemProp in
        // ~/.gradle/gradle.properties; the fork does not inherit it.
        //
        // Forwarded rather than hardcoded: the path is machine-specific and must
        // never be committed, and on a runner with a stock truststore these
        // properties are absent and this loop does nothing.
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
    // `api`: LfIcons exposes ImageVector values built from this artifact, so
    // consumers need it to name them. Core only -- material-icons-extended is a
    // very large artifact and §11's budget has ML Kit still to fit into it.
    api(libs.androidx.compose.material.icons.core)

    // The category palette lives in :core:model (it is data, not a Compose
    // type); the WCAG assertions over it live here, next to the contrast maths.
    implementation(project(":core:model"))

    // OccurredAt -- the "a date-only message's time is its capture time" rule
    // (SPEC.md §16). It lives in :core:common rather than in TimeStamp because
    // the "Unsaved" section SORTS by it as well as displaying it, and the two
    // reading different values is what produced an out-of-order list on device.
    implementation(project(":core:common"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)

    // BUG9's regression test measures a real TextLayoutResult, which needs a
    // composition on a device rather than a JVM stub.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}

/**
 * §12's screenshot gate, and BUG5/BUG9's only automated check.
 *
 * Roborazzi renders through **Robolectric**, so these are JVM unit tests rather
 * than device ones. That is what makes the gate runnable at all: CI's
 * `screenshot` job is `ubuntu-latest` with no emulator, and a device-only
 * screenshot suite would have to be either skipped there or moved to the
 * self-hosted runner, which is the phone.
 *
 * `ui-test-junit4` and `test-ext-junit` are already on the *androidTest*
 * classpath from the compose convention; screenshot tests need them on the
 * **unit test** classpath instead, which is the one thing about this setup that
 * looks redundant and is not.
 */
dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
