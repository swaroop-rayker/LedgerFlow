package com.ledgerflow.core.testing.screenshot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two structural checks every golden runs (SPEC.md §9.6) — proved able to
 * fail, because a check that finds no nodes passes every screen, and proved not
 * to fail where Compose already does the right thing, because a check that
 * cries wolf gets suppressed (a withdrawn false finding on every Ledger row's
 * delete icon is why that half exists).
 */
@RunWith(AndroidJUnit4::class)
// NATIVE: without it text measures zero wide and the clip check could not fail.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AccessibilityChecksTest {

    @get:Rule
    val rule = createComposeRule()

    private fun Modifier.target(label: String) = semantics { contentDescription = label }.clickable { }

    /** Compose stretches a lone small target to 48 dp; nothing competes for it. */
    @Test
    fun aLoneSmallTarget_passes() {
        rule.setContent { Box(Modifier.size(8.dp).target("Tiny")) }

        rule.assertTouchTargetsAreAtLeast48dp()
    }

    /** Two small targets 4 dp apart: a tap between them could go to either. */
    @Test
    fun twoSmallTargetsCloserThan48dp_fail_andNameBoth() {
        rule.setContent {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(24.dp).target("Edit"))
                Box(Modifier.size(24.dp).target("Delete"))
            }
        }

        val error = assertThrows(IllegalStateException::class.java) { rule.assertTouchTargetsAreAtLeast48dp() }

        assertThat(error.message).contains("\"Edit\"")
        assertThat(error.message).contains("\"Delete\"")
    }

    /** One small target crowding a full-size one is still ambiguous. */
    @Test
    fun aSmallTargetBesideAFullSizeOne_fails() {
        rule.setContent {
            Row {
                Box(Modifier.size(48.dp).target("Save"))
                Box(Modifier.size(24.dp).target("More"))
            }
        }

        assertThrows(IllegalStateException::class.java) { rule.assertTouchTargetsAreAtLeast48dp() }
    }

    /** Full-size targets that merely touch do not share any area. */
    @Test
    fun fullSizeTargetsSideBySide_pass() {
        rule.setContent {
            Row {
                Box(Modifier.size(48.dp).target("Save"))
                Box(Modifier.size(48.dp).target("Share"))
            }
        }

        rule.assertTouchTargetsAreAtLeast48dp()
    }

    /** A small button inside a clickable row is the row's child, not its rival. */
    @Test
    fun aSmallButtonInsideAClickableRow_passes() {
        rule.setContent {
            Row(Modifier.width(300.dp).height(56.dp).target("Open entry"), horizontalArrangement = Arrangement.End) {
                Box(Modifier.size(40.dp).target("Delete"))
            }
        }

        rule.assertTouchTargetsAreAtLeast48dp()
    }

    /**
     * **BUG44: four control labels were clipped at font scale 2.0** — this one
     * onboarding's pinned button, cut to "I've written them do"; also Export's
     * "Opens in any spreadsheet" and Organise's "Add payment method". Recorded
     * into goldens without complaint, because nothing measured a label against
     * its slot; [assertNoUnwrappableTextIsClipped] now runs on every golden.
     */
    @Test
    fun Bug44_anUnwrappableLabelWiderThanItsSlot_fails() {
        rule.setContent {
            Box(Modifier.width(60.dp)) { BasicText("I've written them down", softWrap = false) }
        }

        val error = assertThrows(IllegalStateException::class.java) { rule.assertNoUnwrappableTextIsClipped() }

        assertThat(error.message).contains("I've written them down")
    }

    /** The same text allowed to wrap is fitting by wrapping, not clipped. */
    @Test
    fun wrappingText_passes_andFittingLabels_pass() {
        rule.setContent {
            Column {
                Box(Modifier.width(60.dp)) { BasicText("I've written them down") }
                Box(Modifier.width(300.dp)) { BasicText("Save", softWrap = false) }
            }
        }

        rule.assertNoUnwrappableTextIsClipped()
    }

    @Test
    fun anIconOnlyControlWithNoDescription_fails() {
        rule.setContent { Box(Modifier.size(48.dp).clickable { }) }

        assertThrows(IllegalStateException::class.java) { rule.assertEveryTappableNodeIsLabelled() }
    }

    /** Text inside the control labels it, as TalkBack reads the merged node. */
    @Test
    fun labelledControls_pass() {
        rule.setContent {
            Column {
                Box(Modifier.size(48.dp).clickable { }) { BasicText("Save") }
                Box(Modifier.size(48.dp).target("Delete"))
            }
        }

        rule.assertEveryTappableNodeIsLabelled()
    }
}
