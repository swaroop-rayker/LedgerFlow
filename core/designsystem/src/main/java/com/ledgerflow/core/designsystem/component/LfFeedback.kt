package com.ledgerflow.core.designsystem.component

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Feedback for actions (DESIGN_SYSTEM_PLAN §5.2, step E1): the one snackbar
 * every screen can raise -- "Moved to bin · Undo", "Saved to Expenses".
 *
 * **One per window, shown by the innermost scaffold.** Every full-screen
 * destination is an [LfScaffold] inside the shell's, so a host per scaffold
 * would either show a message twice or lose it when the screen that raised it
 * closes. Instead the outermost [LfScaffold] (or an [LfFeedbackHost]) holds one
 * [LfFeedback], every scaffold beneath shares it, and only the most recently
 * composed one draws it. That is also what makes "Saved to Expenses" work: the
 * entry screen raises it and closes, and the Ledger's scaffold, the innermost
 * one left, shows it.
 *
 * Announced to screen readers as a polite live region (Material's snackbar
 * host does that), styled by [LfSnackbarHost]. Never a `Toast` (`CLAUDE.md` §9).
 */
@Stable
public class LfFeedback {
    internal val hostState: SnackbarHostState = SnackbarHostState()

    /** The scaffolds sharing this feedback, outermost first; the last one draws it. */
    internal val claims = mutableStateListOf<Any>()

    /** The window's scope, set by whoever made this feedback: it outlives every screen. */
    internal var scope: CoroutineScope? = null

    /**
     * Shows [message] and returns at once; [onAction] runs if [actionLabel] is
     * tapped. **The one to use when the screen is about to close** ("Saved to
     * Expenses"): it runs in the window's scope, so the message outlives the
     * screen that raised it, where [show] would be cancelled with it.
     */
    public fun post(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        val windowScope = checkNotNull(scope) { "LfFeedback.post needs an LfScaffold or an LfFeedbackHost" }
        windowScope.launch { if (show(message, actionLabel)) onAction() }
    }

    /**
     * Shows [message], with [actionLabel] if given, and suspends until it goes.
     * True when the action was tapped. A message with an action stays longer,
     * since reading it and deciding to undo takes more than a glance. Cancelled,
     * and the message with it, if the caller's scope ends first: see [post].
     */
    public suspend fun show(message: String, actionLabel: String? = null): Boolean =
        hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        ) == SnackbarResult.ActionPerformed

    public companion object {
        /**
         * The window's feedback. Read it inside an [LfScaffold]'s content or under
         * an [LfFeedbackHost]: a message raised anywhere else would be shown
         * nowhere, which §5.2 forbids, so that is an error rather than a no-op.
         */
        public val current: LfFeedback
            @Composable get() = checkNotNull(LocalLfFeedback.current) {
                "LfFeedback.current needs an LfScaffold or an LfFeedbackHost above it"
            }
    }
}

/**
 * Provides one [LfFeedback] to [content], for a window whose screens should
 * share it across their own scaffolds (and for tests of a single screen).
 */
@Composable
public fun LfFeedbackHost(
    feedback: LfFeedback = remember { LfFeedback() },
    content: @Composable () -> Unit,
) {
    feedback.ownScope()
    CompositionLocalProvider(LocalLfFeedback provides feedback, content = content)
}

/** Gives this feedback the calling composable's scope, which [LfFeedback.post] runs in. */
@Composable
internal fun LfFeedback.ownScope() {
    val windowScope = rememberCoroutineScope()
    SideEffect { scope = windowScope }
}

internal val LocalLfFeedback: ProvidableCompositionLocal<LfFeedback?> = staticCompositionLocalOf { null }
