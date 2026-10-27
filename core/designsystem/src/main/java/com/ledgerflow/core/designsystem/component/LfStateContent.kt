package com.ledgerflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.ledgerflow.core.designsystem.theme.LfTheme
import kotlinx.coroutines.delay

/**
 * What a screen's content area is showing (DESIGN_SYSTEM_PLAN §5.1, step E1).
 * Exactly one at a time; [LfStateContent] draws each the same way everywhere.
 *
 * There is no offline state. Nothing in the app uses the network (Law 6), so
 * local data is never "offline"; V2-4's Drive sync adds one for its own
 * surfaces when it needs it.
 */
public sealed interface LfContentState {

    /** Data not ready yet. Nothing for 300 ms, then skeletons, then at 10 s a line saying so. */
    public data object Loading : LfContentState

    /** The screen itself. */
    public data object Ready : LfContentState

    /**
     * Nothing to show: a heading, one sentence, at most one action. The header
     * and the screen's own actions stay. [kind] decides the words, and a
     * [LfEmptyKind.NoMatches] must offer the way back (usually "Clear filters").
     */
    public data class Empty(
        val kind: LfEmptyKind,
        val title: String,
        val body: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    ) : LfContentState {
        init {
            require(kind != LfEmptyKind.NoMatches || (actionLabel != null && onAction != null)) {
                "An empty result of filters has to offer the way out of them (DESIGN_SYSTEM_PLAN §5.1)"
            }
        }
    }

    /**
     * Something failed. [onRetry] is required: no error state without an action
     * (§5.1). Where retrying cannot help, the action is the way out instead, with
     * its own [actionLabel].
     */
    public data class Error(
        val title: String,
        val onRetry: () -> Unit,
        val body: String? = null,
        val actionLabel: String = "Retry",
    ) : LfContentState

    /**
     * The app cannot do this right now, through no fault of the data: a
     * permission missing, capture stopped, a storage folder lost. The fix is the
     * action, and it is required.
     */
    public data class Unavailable(
        val title: String,
        val fixLabel: String,
        val onFix: () -> Unit,
        val body: String? = null,
    ) : LfContentState
}

/**
 * Draws [state] in the content area; [content] when it is [LfContentState.Ready].
 *
 * **Loading never spins.** For the first 300 ms it draws nothing, because most
 * loads finish sooner and a placeholder that flashes is worse than a pause. Then
 * [skeleton] -- rows in the layout's own shape, so nothing jumps when the data
 * arrives. At 10 s [slowMessage] joins it, saying what is going on. The
 * skeletons are still (owner, 2026-10-27): nothing to switch off when
 * Animations is Off, and nothing to draw every frame.
 */
@Composable
public fun LfStateContent(
    state: LfContentState,
    modifier: Modifier = Modifier,
    slowMessage: String = "Still loading. Nothing is lost; this is taking longer than usual.",
    skeleton: @Composable () -> Unit = { LfSkeletonList() },
    content: @Composable () -> Unit,
) {
    StateContent(state, modifier, slowMessage, skeleton, LoadingPhase.Waiting, content)
}

/** Where a load has got to. Internal so a golden can start at a phase without waiting. */
internal enum class LoadingPhase { Waiting, Skeleton, Slow }

@Composable
internal fun StateContent(
    state: LfContentState,
    modifier: Modifier,
    slowMessage: String,
    skeleton: @Composable () -> Unit,
    startPhase: LoadingPhase,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (state) {
            LfContentState.Loading -> Loading(startPhase, slowMessage, skeleton)
            LfContentState.Ready -> content()
            is LfContentState.Empty -> LfEmptyState(
                title = state.title,
                body = state.body,
                actionLabel = state.actionLabel,
                onAction = state.onAction,
            )
            is LfContentState.Error -> LfErrorNotice(
                title = state.title,
                body = state.body,
                actionLabel = state.actionLabel,
                onAction = state.onRetry,
            )
            is LfContentState.Unavailable -> LfNotice(
                tone = LfNoticeTone.Warning,
                title = state.title,
                body = state.body,
                actionLabel = state.fixLabel,
                onAction = state.onFix,
            )
        }
    }
}

@Composable
private fun Loading(startPhase: LoadingPhase, slowMessage: String, skeleton: @Composable () -> Unit) {
    var phase by remember { mutableStateOf(startPhase) }
    LaunchedEffect(Unit) {
        if (phase == LoadingPhase.Waiting) {
            delay(SKELETON_AFTER_MS)
            phase = LoadingPhase.Skeleton
        }
        if (phase == LoadingPhase.Skeleton) {
            delay(SLOW_AFTER_MS - SKELETON_AFTER_MS)
            phase = LoadingPhase.Slow
        }
    }
    when (phase) {
        LoadingPhase.Waiting -> Unit
        LoadingPhase.Skeleton -> skeleton()
        LoadingPhase.Slow -> Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap)) {
            Text(
                text = slowMessage,
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            skeleton()
        }
    }
}

/**
 * The four kinds of nothing (§5.1), each with different words: **first use**
 * ("No budgets yet"), **no matches** ("Nothing matched these filters", with
 * Clear filters), **outside the window** ("Nothing in the last 30 days") and
 * **all done** ("Inbox clear").
 */
public enum class LfEmptyKind { FirstUse, NoMatches, OutsideWindow, AllDone }

private const val SKELETON_AFTER_MS = 300L
private const val SLOW_AFTER_MS = 10_000L
