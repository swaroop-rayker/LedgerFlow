package com.ledgerflow.core.designsystem.pattern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfBottomSheet
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfTitleBlock
import com.ledgerflow.core.designsystem.theme.LfTheme

/*
 * Page templates (DESIGN_SYSTEM_PLAN §4.4, step D1).
 *
 * Templates, not screens, own the layout: the screen edge, the gap after a
 * list's last item and the pinned bar's inset are applied here, so they cannot
 * drift apart again (the audit found three edge margins and four list ends).
 * They know nothing about tabs, routes or the bottom bar, which stay in `:app`,
 * so a screen can move from a tab into Settings without changing its design.
 *
 * Every page has the same slots, top to bottom:
 * - `actions`: a band above the title (Back, Done, Add), on its own line so the
 *   title never competes with a control for one (BUG17);
 * - the title and an optional subtitle;
 * - `search`, then `filters`: a field and a band of chips (V2-PLAN §5.6:
 *   Search, Help, the merchant picker; Merchants' Uncategorised, Receipts'
 *   period);
 * - `notices`: banners about the page (backup overdue, capture stopped);
 * - the content, or `statusContent` in its place: an empty, failed or offline
 *   page keeps its header and shows the state where the list would be;
 * - a pinned `actionBar`, or `selectionBar` instead while rows are selected.
 *
 * D1 builds them with today's look; screens adopt them in step D2.
 */

/** A bottom-bar tab: Home, Ledger, Analytics. The shell supplies the scaffold and the bar. */
@Composable
@Suppress("LongParameterList") // A template is its slots; each is optional.
public fun LfTabPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    search: (@Composable () -> Unit)? = null,
    filters: (@Composable () -> Unit)? = null,
    notices: (@Composable ColumnScope.() -> Unit)? = null,
    statusContent: (@Composable ColumnScope.() -> Unit)? = null,
    spacing: LfPageSpacing = LfPageSpacing.Rows,
    pinHeader: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    PageList(
        header = PageHeader(title, subtitle, actions, search, filters, notices),
        statusContent = statusContent,
        spacing = spacing,
        pinHeader = pinHeader,
        listState = listState,
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

/** A screen pushed over the tabs: Inbox, Budgets, the bin, Diagnostics. */
@Composable
@Suppress("LongParameterList") // A template is its slots; each is optional.
public fun LfDetailPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    search: (@Composable () -> Unit)? = null,
    filters: (@Composable () -> Unit)? = null,
    notices: (@Composable ColumnScope.() -> Unit)? = null,
    statusContent: (@Composable ColumnScope.() -> Unit)? = null,
    actionBar: (@Composable () -> Unit)? = null,
    selectionBar: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    spacing: LfPageSpacing = LfPageSpacing.Rows,
    pinHeader: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    LfScaffold(
        modifier = modifier,
        bottomBar = { (selectionBar ?: actionBar)?.invoke() },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        PageList(
            header = PageHeader(title, subtitle, actions, search, filters, notices),
            statusContent = statusContent,
            spacing = spacing,
            pinHeader = pinHeader,
            listState = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            content = content,
        )
    }
}

/**
 * One page of Settings (V2-PLAN §3: every section is its own page). A detail
 * page whose rows sit in [LfPanel]s; it has no pinned bar, since a setting
 * applies when it is changed.
 */
@Composable
public fun LfSettingsPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    notices: (@Composable ColumnScope.() -> Unit)? = null,
    statusContent: (@Composable ColumnScope.() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    content: LazyListScope.() -> Unit,
) {
    LfDetailPage(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        actions = actions,
        notices = notices,
        statusContent = statusContent,
        snackbarHostState = snackbarHostState,
        spacing = LfPageSpacing.Sections,
        content = content,
    )
}

/**
 * A form: Add entry, Review, Export, Back up now. Scrolls as one column, so a
 * field error below the fold can be scrolled to, and the keyboard is paid for
 * once by `LfScaffold`.
 *
 * The only template whose [title] may be null: Add entry opens straight onto
 * the book and the amount, which are what the screen is for.
 */
@Composable
@Suppress("LongParameterList") // A template is its slots; each is optional.
public fun LfFormPage(
    title: String?,
    actionBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    notices: (@Composable ColumnScope.() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    spacing: LfPageSpacing = LfPageSpacing.Rows,
    content: @Composable ColumnScope.() -> Unit,
) {
    val space = LfTheme.space
    LfScaffold(modifier = modifier, bottomBar = actionBar, snackbarHostState = snackbarHostState) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = space.screenEdge),
            verticalArrangement = Arrangement.spacedBy(spacing.gap()),
        ) {
            // Nothing at all when there is no header: an empty one would still
            // take a gap above the first field.
            if (title != null || actions != null || notices != null) {
                PageHeader(title, subtitle, actions, search = null, filters = null, notices = notices).Draw()
            }
            content()
            Spacer(Modifier.height(space.listEnd))
        }
    }
}

/**
 * One step of a flow that does one thing at a time: onboarding, Recovery,
 * Restore, the lock screen (V2: PIN setup, sign-in). A large heading and a line
 * of explanation, then the step, with its one action pinned below.
 */
@Composable
public fun LfFlowStep(
    title: String,
    actionBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val space = LfTheme.space
    LfScaffold(modifier = modifier, bottomBar = actionBar) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(space.screenEdge)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(space.blockGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(space.itemGap)) {
                Text(text = title, style = LfTheme.type.display, color = LfTheme.colors.text.primary)
                body?.let { Text(text = it, style = LfTheme.type.body, color = LfTheme.colors.text.secondary) }
            }
            content()
        }
    }
}

/**
 * A modal sheet with an optional heading: the Add sheet's layout, for every
 * sheet (V2: filters, options, confirmations).
 */
@Composable
public fun LfSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val space = LfTheme.space
    LfBottomSheet(onDismiss = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = space.cardInset, end = space.cardInset, bottom = space.cardInset),
            verticalArrangement = Arrangement.spacedBy(space.itemGap),
        ) {
            title?.let { Text(text = it, style = LfTheme.type.heading, color = LfTheme.colors.text.primary) }
            content()
        }
    }
}

/**
 * Rows grouped in one container with hairlines between them: the samples'
 * settings and profile lists, and the compactness brief's "one panel, not a
 * card per row" (`CLAUDE.md` §5). The rows pad themselves.
 */
@Composable
public fun LfPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = LfTheme.shapes.container
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LfTheme.colors.surface.raised, shape)
            .border(LfTheme.borders.hairline, LfTheme.colors.border.subtle, shape),
        content = content,
    )
}

/** The header slots, drawn the same way by every template. */
@Immutable
private class PageHeader(
    val title: String?,
    val subtitle: String?,
    val actions: (@Composable () -> Unit)?,
    val search: (@Composable () -> Unit)?,
    val filters: (@Composable () -> Unit)?,
    val notices: (@Composable ColumnScope.() -> Unit)?,
) {
    @Composable
    fun Draw() {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap)) {
            actions?.let { LfActionRow(alignment = LfActionAlignment.Start) { it() } }
            title?.let { LfTitleBlock(title = it, subtitle = subtitle) }
            search?.invoke()
            filters?.invoke()
            notices?.let { Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.itemGap)) { it() } }
        }
    }
}

/**
 * How far apart a page's items sit: `Dense` for a long list of transactions
 * (the Ledger), `Rows` for most lists, `Sections` for a page of cards or
 * groups (Home, Settings). One choice per page, so the gaps cannot drift
 * between screens that show the same kind of thing.
 */
public enum class LfPageSpacing { Dense, Rows, Sections }

@Composable
internal fun LfPageSpacing.gap(): Dp = when (this) {
    LfPageSpacing.Dense -> LfTheme.space.inlineGap
    LfPageSpacing.Rows -> LfTheme.space.itemGap
    LfPageSpacing.Sections -> LfTheme.space.sectionGap
}

/**
 * Lets one item of a template's list reach past the screen edge to the side of
 * the screen: a row that scrolls sideways (Analytics' ranges, the entry form's
 * suggestions) and pads its own content back in, so its first chip starts on
 * the edge and the rest scroll under the margin rather than stopping at it.
 */
@Composable
public fun Modifier.lfFullBleed(): Modifier {
    val edge = with(LocalDensity.current) { LfTheme.space.screenEdge.roundToPx() }
    return layout { measurable, constraints ->
        val wide = constraints.copy(
            minWidth = constraints.minWidth + edge * 2,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + edge * 2 else constraints.maxWidth,
        )
        val placeable = measurable.measure(wide)
        layout(placeable.width - edge * 2, placeable.height) { placeable.place(-edge, 0) }
    }
}

/**
 * The scrolling part of a list page: header, then the content or the state
 * that replaces it, then [LfTheme.space]`.listEnd` so the last row clears the
 * bar below. Every item sits inside the screen edge.
 *
 * With [pinHeader] the header stays put above the list, as the Ledger's,
 * Inbox's and bin's always have: their filters are what the list is read
 * through. Otherwise it scrolls away with the list (Home, Analytics, Settings).
 */
@Composable
private fun PageList(
    header: PageHeader,
    statusContent: (@Composable ColumnScope.() -> Unit)?,
    spacing: LfPageSpacing,
    pinHeader: Boolean,
    listState: LazyListState,
    modifier: Modifier,
    content: LazyListScope.() -> Unit,
) {
    val space = LfTheme.space
    val gap = spacing.gap()
    val padding = PaddingValues(start = space.screenEdge, end = space.screenEdge, bottom = space.listEnd)
    val body: LazyListScope.() -> Unit = {
        if (statusContent != null) {
            item(key = STATUS_KEY, contentType = STATUS_KEY) {
                Column(verticalArrangement = Arrangement.spacedBy(space.itemGap)) { statusContent() }
            }
        } else {
            content()
        }
    }
    if (pinHeader) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(space.itemGap)) {
            Box(Modifier.padding(horizontal = space.screenEdge)) { header.Draw() }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(gap),
                content = body,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            item(key = HEADER_KEY, contentType = HEADER_KEY) { header.Draw() }
            body()
        }
    }
}

private const val HEADER_KEY = "lf-page-header"
private const val STATUS_KEY = "lf-page-status"
