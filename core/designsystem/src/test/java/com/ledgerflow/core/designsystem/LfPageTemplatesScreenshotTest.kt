package com.ledgerflow.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfChip
import com.ledgerflow.core.designsystem.component.LfChipStyle
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfFlowStep
import com.ledgerflow.core.designsystem.pattern.LfFormPage
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.pattern.LfSelectionBar
import com.ledgerflow.core.designsystem.pattern.LfSettingsPage
import com.ledgerflow.core.designsystem.pattern.LfSheet
import com.ledgerflow.core.designsystem.pattern.LfTabPage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.testing.screenshot.DarkGoldensRule
import com.ledgerflow.core.testing.screenshot.GOLDEN_DIR
import com.ledgerflow.core.testing.screenshot.GOLDEN_SDK
import com.ledgerflow.core.testing.screenshot.GoldenTheme
import com.ledgerflow.core.testing.screenshot.LfScreenshotOptions
import com.ledgerflow.core.testing.screenshot.PHONE_1X
import com.ledgerflow.core.testing.screenshot.PHONE_2X
import com.ledgerflow.core.testing.screenshot.captureScreenGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page templates (DESIGN_SYSTEM_PLAN §4.4, step D1), each with stand-in
 * content, at font scale 1.0 and 2.0 and once in RTL.
 *
 * These are the shape every screen takes in step D2: one screen edge, one gap
 * after a list's last item, the action band on its own line above the title
 * (BUG17), and a pinned bar inset once. Captured through `captureScreenGolden`,
 * so each also passes its touch-target, label and clipping checks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [GOLDEN_SDK], qualifiers = PHONE_1X)
open class LfPageTemplatesScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) = rule.captureScreenGolden(name, fontScale, direction) { LfTheme { content() } }

    @Test fun tab_1x() = capture("template-tab-1x") { TabPage() }

    @Config(qualifiers = PHONE_2X)
    @Test fun tab_2x() = capture("template-tab-2x", fontScale = 2f) { TabPage() }

    @Test fun detail_1x() = capture("template-detail-1x") { DetailPage(selecting = false) }

    @Config(qualifiers = PHONE_2X)
    @Test fun detail_2x() = capture("template-detail-2x", fontScale = 2f) { DetailPage(selecting = false) }

    @Test fun detail_1x_rtl() = capture("template-detail-1x-rtl", direction = LayoutDirection.Rtl) {
        DetailPage(selecting = false)
    }

    @Test fun detailSelecting_1x() = capture("template-detail-selecting-1x") { DetailPage(selecting = true) }

    /** The offline slot: the header stays, the state takes the list's place. */
    @Test fun detailStatus_1x() = capture("template-detail-status-1x") {
        LfDetailPage(
            title = "Drive backup",
            actions = { LfButton(text = "Back", onClick = {}, style = LfButtonStyle.Text) },
            statusContent = {
                LfEmptyState(title = "No connection", body = "Drive status shows when this phone is online.")
            },
        ) {}
    }

    @Test fun settings_1x() = capture("template-settings-1x") { SettingsPage() }

    @Test fun form_1x() = capture("template-form-1x") { FormPage() }

    @Config(qualifiers = PHONE_2X)
    @Test fun form_2x() = capture("template-form-2x", fontScale = 2f) { FormPage() }

    @Test fun flow_1x() = capture("template-flow-1x") { FlowStep() }

    @Config(qualifiers = PHONE_2X)
    @Test fun flow_2x() = capture("template-flow-2x", fontScale = 2f) { FlowStep() }

    /** A sheet is a window of its own, so the whole screen is captured. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun sheet_1x() {
        rule.setContent {
            GoldenTheme.Provide {
                LfTheme {
                    LfSheet(onDismiss = {}, title = "Add") {
                        LfButton(text = "Manual entry", onClick = {}, modifier = Modifier.fillMaxWidth())
                        LfButton(
                            text = "Inbox (3)",
                            onClick = {},
                            style = LfButtonStyle.Tonal,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(
            "$GOLDEN_DIR/${GoldenTheme.fileName("template-sheet-1x")}.png",
            roborazziOptions = LfScreenshotOptions,
        )
    }
}

/** The same goldens with the phone in dark mode, saved with a `-dark` suffix (DESIGN_SYSTEM_PLAN §6.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LfPageTemplatesScreenshotDarkTest : LfPageTemplatesScreenshotTest() {
    @get:Rule
    val darkGoldens: DarkGoldensRule = DarkGoldensRule()
}

@Composable
private fun TabPage() {
    LfTabPage(
        title = "Ledger",
        subtitle = "October",
        filters = {
            Row(horizontalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap)) {
                LfChip(label = "Expenses", style = LfChipStyle.Selected, onClick = {})
                LfChip(label = "Income", onClick = {})
            }
        },
    ) {
        sampleRows(count = 4)
    }
}

@Composable
private fun DetailPage(selecting: Boolean) {
    LfDetailPage(
        title = "Deleted entries",
        subtitle = "4 entries, kept until you erase them",
        actions = { LfButton(text = "Done", onClick = {}, style = LfButtonStyle.Text) },
        actionBar = {
            LfActionBar { LfButton(text = "Erase all", onClick = {}, modifier = Modifier.fillMaxWidth()) }
        },
        selectionBar = if (selecting) {
            {
                LfSelectionBar(label = "2 selected") {
                    LfButton(text = "None", onClick = {}, style = LfButtonStyle.Inline)
                    LfButton(text = "Restore", onClick = {}, style = LfButtonStyle.Outlined)
                    LfButton(text = "Erase", onClick = {})
                }
            }
        } else {
            null
        },
    ) {
        sampleRows(count = 4)
    }
}

@Composable
private fun SettingsPage() {
    LfSettingsPage(
        title = "Appearance",
        actions = { LfButton(text = "Back", onClick = {}, style = LfButtonStyle.Text) },
    ) {
        item(key = "panel", contentType = "panel") {
            LfPanel {
                val rows = listOf("Theme" to "Dark", "Animations" to "Full", "Start screen" to "Home")
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) HorizontalDivider(color = LfTheme.colors.border.subtle)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LfTheme.space.rowInset, vertical = LfTheme.space.sectionGap),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = k, style = LfTheme.type.body, color = LfTheme.colors.text.primary)
                        Text(text = v, style = LfTheme.type.bodySmall, color = LfTheme.colors.text.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun FormPage() {
    LfFormPage(
        title = "Export",
        actions = { LfButton(text = "Done", onClick = {}, style = LfButtonStyle.Text) },
        actionBar = {
            LfActionBar { LfButton(text = "Export Excel", onClick = {}, modifier = Modifier.fillMaxWidth()) }
        },
    ) {
        LfTextField(value = "ledgerflow-2026-10.xlsx", onValueChange = {}, label = "File name")
        LfCard {
            Text(
                text = "Both books, every entry, one sheet each.",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
            )
        }
    }
}

@Composable
private fun FlowStep() {
    LfFlowStep(
        title = "Where should backups go?",
        body = "Pick a folder LedgerFlow can write to every night.",
        actionBar = {
            LfActionBar { LfButton(text = "Choose a folder", onClick = {}, modifier = Modifier.fillMaxWidth()) }
        },
    ) {
        LfCard {
            Text(
                text = "Backups are encrypted with your recovery phrase.",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sampleRows(count: Int) {
    items(count = count, key = { "row-$it" }, contentType = { "row" }) { i ->
        LfCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Entry ${i + 1}", style = LfTheme.type.body, color = LfTheme.colors.text.primary)
                Text(
                    text = "−${(i + 1) * 120}.00",
                    style = LfTheme.type.amountRow,
                    color = LfTheme.colors.money.outgoing,
                )
            }
        }
    }
}
