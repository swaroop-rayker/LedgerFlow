package com.ledgerflow.feature.settings.about

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.format.ProseBlock
import com.ledgerflow.core.designsystem.format.ProseMarkdown
import com.ledgerflow.core.designsystem.format.plainText
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.about.AppEdition
import com.ledgerflow.core.domain.about.AppInfo
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > About (V2-3, V2-PLAN §3.8): the bundled texts, the privacy policy
 * being the published one, and Report a bug sending exactly what it shows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class AboutTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val texts = AboutTexts(context, Dispatchers.Unconfined)

    private val info = AppInfo(
        versionName = "0.1.1",
        versionCode = 2,
        edition = AppEdition.PLAY,
        debuggable = true,
        androidRelease = "16",
        sdkInt = 36,
        device = "samsung SM-S721B",
        languageTag = "en-IN",
    )

    // ── The texts ───────────────────────────────────────────────────────────

    @Test
    fun everyDocument_isBundled_andSaysSomething() = runBlocking {
        AboutDocument.entries.forEach { document ->
            val blocks = texts.document(document)
            assertThat(blocks.size).isAtLeast(MIN_BLOCKS)
            // The page's title bar says the title; the text does not repeat it.
            assertThat((blocks.first() as? ProseBlock.Heading)?.level).isNotEqualTo(1)
        }
    }

    @Test
    fun help_isQuestionsEachWithAnAnswer() = runBlocking {
        val items = texts.help()

        assertThat(items.size).isAtLeast(MIN_QUESTIONS)
        items.forEach { assertThat(it.answer).isNotEmpty() }
    }

    /**
     * The policy on the phone is the policy the stores link to: the build
     * copies `docs/play/privacy-policy.md` in, and this fails if anything ever
     * ships a second copy instead.
     */
    @Test
    fun privacyPolicy_isTheDocsFile() {
        val bundled = context.assets.open("about/privacy-policy.md").bufferedReader().use { it.readText() }
        val docs = File("../../docs/play/privacy-policy.md").readText()

        assertThat(bundled).isEqualTo(docs)
    }

    @Test
    fun helpItems_groupEachQuestionWithTheBlocksUnderIt() {
        val items = helpItems(ProseMarkdown.parse("Intro.\n\n## One?\n\nA.\n\n- b\n\n## Two?\n\nC."))

        assertThat(items.map { it.question to it.answer.map(ProseBlock::plainText) }).containsExactly(
            "One?" to listOf("A.", "b"),
            "Two?" to listOf("C."),
        ).inOrder()
    }

    // ── Report a bug ────────────────────────────────────────────────────────

    @Test
    fun theReport_isTheDescription_thenTheDetailsShown() {
        val details = deviceDetails(info)

        assertThat(details).containsExactly(
            "App: LedgerFlow 0.1.1 (2), Google Play build, debug",
            "Android: 16 (API 36)",
            "Phone: samsung SM-S721B",
            "Language: en-IN",
        ).inOrder()
        assertThat(bugReportText("  The Inbox showed a payment twice.\n", details)).isEqualTo(
            "What happened:\nThe Inbox showed a payment twice.\n\n" + details.joinToString("\n"),
        )
    }

    /** Share carries exactly the report and a subject; the user picks the app every time. */
    @Test
    fun share_isAChooserOverExactlyTheReport() {
        val chooser = bugReportIntent("the report")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION") // getParcelableExtra(String, Class) is API 33+.
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("the report")
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo(REPORT_SUBJECT)
        assertThat(send.data).isNull()
    }

    /** What the user wrote survives the process dying while they are in the share sheet. */
    @Test
    fun theDescription_livesInSavedState() {
        val saved = SavedStateHandle()
        ReportBugViewModel({ info }, saved).describe("Draft")

        val again = ReportBugViewModel({ info }, saved)
        assertThat(again.state.value.description).isEqualTo("Draft")
        assertThat(again.state.value.canShare).isTrue()
    }

    @Test
    fun shareIsOff_untilSomethingIsWritten() {
        assertThat(ReportBugUiState(description = "  ").canShare).isFalse()
    }

    /** The screen shows every line the report carries. */
    @Test
    fun theScreen_showsTheDetailsThatAreSent() {
        val state = ReportBugUiState(description = "x", details = deviceDetails(info))
        rule.setContent { LfTheme { ReportBugScreen(state, onDescribe = {}, onShare = {}, onBack = {}) } }

        rule.onNodeWithText(state.details.joinToString("\n")).assertExists()
    }

    // ── Goldens ─────────────────────────────────────────────────────────────

    private fun capture(
        name: String,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { LfScaffold { content() } } }

    private fun help(): HelpUiState = runBlocking { HelpUiState(items = texts.help(), open = setOf(0)) }

    private fun policy(): AboutDocumentUiState = runBlocking {
        AboutDocumentUiState(AboutDocument.PrivacyPolicy, texts.document(AboutDocument.PrivacyPolicy))
    }

    private val report = ReportBugUiState(
        description = "Approving a payment from the notification did nothing.",
        details = deviceDetails(info.copy(edition = AppEdition.FULL, debuggable = false)),
    )

    @Test fun help_1x() = help().let { capture("about-help-1x") { HelpScreen(it, onToggle = {}, onBack = {}) } }

    @Config(qualifiers = PHONE_2X)
    @Test fun help_2x() =
        help().let { capture("about-help-2x", fontScale = 2f) { HelpScreen(it, onToggle = {}, onBack = {}) } }

    @Test fun help_1x_rtl() = help().let {
        capture("about-help-1x-rtl", direction = LayoutDirection.Rtl) { HelpScreen(it, onToggle = {}, onBack = {}) }
    }

    @Test fun policy_1x() = policy().let { capture("about-privacy-1x") { AboutDocumentScreen(it, onBack = {}) } }

    @Config(qualifiers = PHONE_2X)
    @Test fun policy_2x() =
        policy().let { capture("about-privacy-2x", fontScale = 2f) { AboutDocumentScreen(it, onBack = {}) } }

    @Test fun report_1x() =
        capture("about-report-bug-1x") { ReportBugScreen(report, onDescribe = {}, onShare = {}, onBack = {}) }

    @Config(qualifiers = PHONE_2X)
    @Test fun report_2x() = capture("about-report-bug-2x", fontScale = 2f) {
        ReportBugScreen(report, onDescribe = {}, onShare = {}, onBack = {})
    }

    private companion object {
        const val MIN_BLOCKS = 5
        const val MIN_QUESTIONS = 8
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutDarkTest : AboutTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}
