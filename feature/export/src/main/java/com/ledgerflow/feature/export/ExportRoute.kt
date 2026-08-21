package com.ledgerflow.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half: hoists the ViewModel and hands the screen state and a
 * lambda.
 *
 * `hiltViewModel()` from `androidx.hilt.lifecycle.viewmodel.compose` — the
 * `navigation.compose` one is deprecated.
 *
 * `collectAsStateWithLifecycle`, never bare `collectAsState` (CLAUDE.md §5):
 * the export can be running while the user backgrounds the app, and a plain
 * collector would keep the screen subscribed with nothing watching.
 */
@Composable
public fun ExportRoute(onBack: () -> Unit) {
    val viewModel: ExportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Read once per composition rather than per frame: the name is derived from
    // the clock, and re-deriving it on every recomposition would let the
    // suggested filename change under the user at midnight while the picker is
    // open.
    val suggestedFileName = remember { viewModel.suggestedFileName }

    ExportScreen(
        state = state,
        suggestedFileName = suggestedFileName,
        onEvent = viewModel::onEvent,
        onBack = onBack,
    )
}
