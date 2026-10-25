package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * Themed wrappers for Material's date pickers, bottom sheet and snackbar
 * (DESIGN_SYSTEM_PLAN §6.4, step C2).
 *
 * Material's versions already inherit LedgerFlow's colours and corners (step
 * A2 mapped every role), so these draw exactly what the screens drew before.
 * What they add is one place: a picker's container, a sheet's surface and a
 * snackbar's colours are component tokens here, and a visual direction
 * changes them once instead of at every call site. `designTokenCheck` rejects
 * the raw Material widgets anywhere outside the design system.
 *
 * Buttons stay slots: a dialog's actions are `LfButton`s chosen by the
 * screen, with the label and emphasis that screen needs.
 */

/**
 * A date picker drawn inline, for a dialog that is not a date dialog of its
 * own (Review's date step sits inside an `LfDialog`).
 *
 * @param showHeadline false drops Material's title and headline, which repeat
 *   what the surrounding dialog already says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LfDatePicker(
    state: DatePickerState,
    modifier: Modifier = Modifier,
    showHeadline: Boolean = true,
) {
    val colors = DatePickerDefaults.colors(containerColor = LfDatePickerTokens.container)
    if (showHeadline) {
        DatePicker(state = state, modifier = modifier, colors = colors)
    } else {
        DatePicker(state = state, modifier = modifier, title = null, headline = null, colors = colors)
    }
}

/** Material's date dialog: one date, with the screen's own confirm and dismiss buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LfDatePickerDialog(
    state: DatePickerState,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val colors = DatePickerDefaults.colors(containerColor = LfDatePickerTokens.container)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        colors = colors,
    ) {
        DatePicker(state = state, colors = colors)
    }
}

/**
 * A range of dates in one gesture.
 *
 * **Material's default header breaks mid-phrase on a real device.** Seen at the
 * owner's font scale: "Start date - End / date" wrapped across two lines beside
 * the mode-toggle pencil, which is BUG17's shape exactly -- a heading competing
 * with a control for one line. So the title is dropped and the screen supplies
 * a short [headline] that says what has been picked rather than labelling two
 * empty slots.
 *
 * The pencil is dropped too: it switches to typed-date entry, a second input
 * path with its own parsing and failure modes, for a range the user is already
 * picking by tapping. Without it the headline has the whole line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LfDateRangePickerDialog(
    state: DateRangePickerState,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    headline: @Composable () -> Unit,
) {
    val tokens = LfDatePickerTokens
    val colors = DatePickerDefaults.colors(
        containerColor = tokens.container,
        // BUG64: without these the days between the two ends had no band.
        dayInSelectionRangeContainerColor = tokens.rangeFill,
        dayInSelectionRangeContentColor = tokens.rangeDay,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        colors = colors,
    ) {
        DateRangePicker(
            state = state,
            title = null,
            headline = headline,
            showModeToggle = false,
            colors = colors,
        )
    }
}

/** A modal sheet from the bottom edge (the Add sheet). Its corners are the `dialog` shape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LfBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = LfSheetTokens.container,
        content = content,
    )
}

/** Where a screen's snackbars appear. `LfScaffold` places one when given a host state. */
@Composable
public fun LfSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val tokens = LfSnackbarTokens
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = tokens.shape,
            containerColor = tokens.container,
            contentColor = tokens.content,
            actionColor = tokens.action,
            actionContentColor = tokens.action,
            dismissActionContentColor = tokens.content,
        )
    }
}
