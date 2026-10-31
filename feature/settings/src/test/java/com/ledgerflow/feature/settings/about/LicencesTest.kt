package com.ledgerflow.feature.settings.about

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * About > Open-source licences (V2-3, ADR-0032): the generated list read as
 * the plugin writes it, the two common licences shown in full, and nothing
 * the page names missing from the APK.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LicencesTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val texts = AboutTexts(context, Dispatchers.Unconfined)

    /** The shape AboutLibraries 15.3.0 writes, offline: names and URLs, no texts. */
    private val generated = """
        {"libraries":[
          {"uniqueId":"androidx.activity:activity","artifactVersion":"1.13.0","name":"Activity",
           "licenses":["Apache-2.0"]},
          {"uniqueId":"net.zetetic:sqlcipher-android","artifactVersion":"4.10.0","name":"SQLCipher for Android",
           "licenses":["45546b23"]},
          {"uniqueId":"androidx.datastore:datastore-preferences-external-protobuf","artifactVersion":"1.2.0",
           "name":"","licenses":["BSD-3-Clause"]}
        ],
        "licenses":{
          "Apache-2.0":{"name":"Apache-2.0","url":"https://github.com/opencv/opencv/blob/master/LICENSE"},
          "45546b23":{"name":"","url":"https://www.zetetic.net/sqlcipher/license/"},
          "BSD-3-Clause":{"name":"BSD-3-Clause","url":"https://opensource.org/licenses/BSD-3-Clause"}
        }}
    """.trimIndent()

    private val all = LicenceCatalogue.all(generated)

    @Test
    fun theList_isTheGeneratedOnePlusTheFontsAndIcons_byName() {
        assertThat(all.map { it.name }).containsExactly(
            "Activity",
            "datastore-preferences-external-protobuf",
            "IBM Plex Mono (font)",
            "Inter (font)",
            "Lucide (icons)",
            "SQLCipher for Android",
        ).inOrder()
    }

    /** Apache's own URL and bundled text, not the plugin's first-seen URL (it was OpenCV's). */
    @Test
    fun apache_isTheCanonicalLicence_withItsText() {
        val apache = all.single { it.name == "Activity" }.licences.single()

        assertThat(apache.name).isEqualTo("Apache License 2.0")
        assertThat(apache.url).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0")
        assertThat(apache.asset).isNotNull()
    }

    /** SQLCipher's licence arrives nameless; it is still shown, with where its terms are. */
    @Test
    fun aNamelessLicence_isStillALicence_withItsUrl() {
        val sqlcipher = all.single { it.name == "SQLCipher for Android" }.licences.single()

        assertThat(sqlcipher.name).isEqualTo("Licence")
        assertThat(sqlcipher.url).isEqualTo("https://www.zetetic.net/sqlcipher/license/")
        assertThat(sqlcipher.asset).isNull()
    }

    @Test
    fun noGeneratedList_stillShowsTheFontsAndIcons() {
        assertThat(LicenceCatalogue.all(null).map { it.id })
            .containsExactlyElementsIn(LicenceCatalogue.BUNDLED.map { it.id })
        assertThat(LicenceCatalogue.all("not json")).hasSize(LicenceCatalogue.BUNDLED.size)
    }

    /** Every text the page promises is in the APK and says something. */
    @Test
    fun everyBundledLicenceText_isThere() = runBlocking {
        val assets = all.flatMap { it.licences }.mapNotNull { it.asset }.distinct()

        assertThat(assets).hasSize(BUNDLED_TEXTS)
        assets.forEach { assertThat(texts.licence(it).size).isAtLeast(2) }
    }

    // ── Goldens ─────────────────────────────────────────────────────────────

    private fun capture(
        name: String,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { LfScaffold { content() } } }

    private fun apacheLicence(): LicenceUiState = runBlocking {
        val activity = all.single { it.name == "Activity" }
        val licence = activity.licences.single()
        LicenceUiState(activity, mapOf(licence.id to texts.licence(requireNotNull(licence.asset))))
    }

    @Test fun list_1x() =
        capture("about-licences-1x") { LicencesScreen(LicencesUiState(all), onOpen = {}, onBack = {}) }

    @Config(qualifiers = PHONE_2X)
    @Test fun list_2x() =
        capture("about-licences-2x", fontScale = 2f) { LicencesScreen(LicencesUiState(all), onOpen = {}, onBack = {}) }

    @Test fun list_1x_rtl() = capture("about-licences-1x-rtl", direction = LayoutDirection.Rtl) {
        LicencesScreen(LicencesUiState(all), onOpen = {}, onBack = {})
    }

    @Test fun licence_1x() = apacheLicence().let { capture("about-licence-1x") { LicenceScreen(it, onBack = {}) } }

    @Config(qualifiers = PHONE_2X)
    @Test fun licence_2x() =
        apacheLicence().let { capture("about-licence-2x", fontScale = 2f) { LicenceScreen(it, onBack = {}) } }

    private companion object {
        /** Apache 2.0, BSD 3-Clause, two OFL fonts and Lucide's ISC. */
        const val BUNDLED_TEXTS = 5
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicencesDarkTest : LicencesTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
