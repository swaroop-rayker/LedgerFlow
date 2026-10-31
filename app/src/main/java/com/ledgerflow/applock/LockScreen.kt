package com.ledgerflow.applock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.theme.LfTheme

/**
 * What the app shows while locked (SPEC §7.6): its name, one sentence, and
 * Unlock. Opaque, and it takes every touch, so nothing underneath is seen or
 * reached; the activity also hides the content from accessibility services
 * while this is up.
 *
 * The phone's prompt opens by itself when the lock appears; Unlock asks again
 * after a cancel. Back leaves the app rather than reaching the screen behind.
 */
@Composable
internal fun LockScreen(onUnlock: () -> Unit, onLeave: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    BackHandler(onBack = onLeave)
    LfScaffold(
        modifier = Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(LfTheme.space.screenEdge),
            verticalArrangement = Arrangement.spacedBy(LfTheme.space.sectionGap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "LedgerFlow is locked",
                style = LfTheme.type.heading,
                color = LfTheme.colors.text.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Unlock with your phone's fingerprint, face or screen lock.",
                style = LfTheme.type.bodySmall,
                color = LfTheme.colors.text.secondary,
                // Centred in both directions; Center is not the Start/End pair
                // BUG61 routes through LfTextAlign.
                textAlign = TextAlign.Center,
            )
            LfButton(text = "Unlock", onClick = onUnlock)
        }
    }
}
