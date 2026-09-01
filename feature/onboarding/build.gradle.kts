import org.gradle.api.tasks.PathSensitivity

plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.onboarding"
}

dependencies {
    // Onboarding generates the recovery phrase and wraps the DEK, so it needs
    // the crypto core directly rather than through a repository.
    implementation(project(":core:crypto"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // OnboardingCtaReachabilityTest measures a real composition: whether a
    // control is inside the visible viewport at font scale 2.0 is a layout
    // fact, and a JVM stub would assert nothing about it. The Compose test
    // rule and the debug test manifest already come from the compose
    // convention plugin.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Bug17_ScreenTitleNeverBreaksMidWordTest asserts on a real TextLayoutResult
    // and needs to say *where* a line broke when it fails -- a bare assertion
    // here would report `false != true` about a layout nobody can see.
    androidTestImplementation(libs.truth)
}

/**
 * `PrivacyRuleIsVerbatimTest` reads `SPEC.md` as a plain file at run time, and
 * Gradle cannot see that.
 *
 * Without this, editing §5.2's privacy rule leaves the task UP-TO-DATE and the
 * guard silently does not run -- so the screen would go on quoting the old
 * sentence with a green build over it. That failure shape has now bitten this
 * repository four times (§16 Q13), which is why it is declared rather than
 * assumed.
 */
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.file("SPEC.md"))
        .withPropertyName("specForVerbatimPrivacyRule")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
