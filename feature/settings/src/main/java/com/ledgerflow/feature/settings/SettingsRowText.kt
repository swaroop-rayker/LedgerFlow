package com.ledgerflow.feature.settings

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.bidiIsolated
import com.ledgerflow.core.domain.analytics.AlertChannelState
import com.ledgerflow.core.domain.appearance.AnimationPreference
import com.ledgerflow.core.domain.appearance.StartScreen
import com.ledgerflow.core.domain.appearance.ThemePreference
import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.model.CurrencyDisplay
import com.ledgerflow.core.model.CurrencyExponent
import com.ledgerflow.core.model.DateOrder
import com.ledgerflow.core.model.NumberGrouping
import com.ledgerflow.feature.settings.about.editionLabel
import com.ledgerflow.feature.settings.about.versionLabel
import java.time.LocalDate

/*
 * What V2-3's rows say and offer (`docs/V2-PLAN.md` §3.2-§3.5): each row's
 * sentence, and the switch or in-place choice it carries. Plain functions of
 * the state, kept apart from the composables in SettingsScreen.kt so the text
 * is tested as text.
 */

/** A switch row: whether it is on, and the event a flip sends. */
@Immutable
internal class SettingsToggle(val checked: Boolean, val eventFor: (Boolean) -> SettingsEvent)

/** The switch a Security & privacy row offers (V2-PLAN §3.5), or null for any other row. */
internal fun toggleFor(item: SettingsItem, state: SettingsUiState): SettingsToggle? = when (item) {
    SettingsItem.AppLock -> SettingsToggle(state.security.appLock) { SettingsEvent.AppLockToggled(it) }
    SettingsItem.BlockScreenshots ->
        SettingsToggle(state.security.blockScreenshots) { SettingsEvent.BlockScreenshotsToggled(it) }
    SettingsItem.HideAmounts -> SettingsToggle(state.hideAmounts) { SettingsEvent.HideAmountsToggled(it) }
    SettingsItem.DataPermissions,
    SettingsItem.NotificationCapture,
    SettingsItem.PaymentApps,
    SettingsItem.BudgetAlerts,
    SettingsItem.Categories,
    SettingsItem.Budgets,
    SettingsItem.BackUpNow,
    SettingsItem.Export,
    SettingsItem.DeletedEntries,
    SettingsItem.Receipts,
    SettingsItem.Theme,
    SettingsItem.Animations,
    SettingsItem.StartScreen,
    SettingsItem.BaseCurrency,
    SettingsItem.NumberGrouping,
    SettingsItem.DateOrder,
    SettingsItem.LockAfter,
    SettingsItem.Diagnostics,
    SettingsItem.AppVersion,
    SettingsItem.WhatsNew,
    SettingsItem.Help,
    SettingsItem.ReportBug,
    SettingsItem.PrivacyPolicy,
    SettingsItem.Terms,
    -> null
}

/**
 * The app lock's sentence (SPEC §7.6). Without a screen lock on the phone it
 * cannot be turned on -- the phone's prompt is what lifts it -- and the
 * sentence says what to do instead of leaving a switch that will not move.
 */
internal fun appLockSubtitle(state: SettingsUiState): String = when {
    state.security.appLock -> "On. LedgerFlow asks for your fingerprint, face or screen lock to open."
    !state.hasScreenLock -> "Set a screen lock on your phone first, then turn this on."
    else -> "Ask for your fingerprint, face or screen lock to open LedgerFlow."
}

internal fun lockAfterSubtitle(security: SecurityPreferences): String {
    val sentence = when (security.lockAfter) {
        LockTimeout.IMMEDIATELY -> "As soon as you leave LedgerFlow."
        LockTimeout.ONE_MINUTE -> "After 1 minute away."
        LockTimeout.FIVE_MINUTES -> "After 5 minutes away."
        LockTimeout.FIFTEEN_MINUTES -> "After 15 minutes away."
    }
    return if (security.appLock) sentence else "When app lock is on: ${sentence.replaceFirstChar(Char::lowercaseChar)}"
}

internal fun blockScreenshotsSubtitle(on: Boolean): String =
    if (on) {
        "On. No screenshots, recordings or Recents previews of LedgerFlow."
    } else {
        "Stop screenshots, recordings and Recents previews of LedgerFlow."
    }

/**
 * Hide amounts on Home (V2-PLAN §3.5). Says where it applies and how the
 * figures come back, since "hide" alone could read as hiding them everywhere
 * or for good.
 */
internal fun hideAmountsSubtitle(on: Boolean): String =
    if (on) {
        "On. Home shows dots until you tap one. Other screens show amounts."
    } else {
        "Show dots in place of amounts on Home until you tap them."
    }

/** The Version row (About): "0.1.1 (2) · Google Play build"; empty until read. */
internal fun appVersionSubtitle(state: SettingsUiState): String =
    state.appInfo?.let { versionLabel(it) + " " + Char(MIDDLE_DOT) + " " + editionLabel(it) } ?: "LedgerFlow"

private const val MIDDLE_DOT = 0x00B7

/** The picker's options for [LockTimeout]. */
internal val LockTimeout.label: String
    get() = when (this) {
        LockTimeout.IMMEDIATELY -> "Immediately"
        LockTimeout.ONE_MINUTE -> "1 minute"
        LockTimeout.FIVE_MINUTES -> "5 minutes"
        LockTimeout.FIFTEEN_MINUTES -> "15 minutes"
    }

/**
 * What the payment-apps row says (V2-PLAN §3.4): how many apps are read, so the
 * guarantee's reach is visible from Settings. Null until the list is read.
 */
internal fun paymentAppsSubtitle(enabledCount: Int?): String = when (enabledCount) {
    null -> "The apps whose notifications LedgerFlow reads"
    0 -> "None. No app's notifications are read."
    1 -> "Reads notifications from 1 app. Tap to change."
    else -> "Reads notifications from $enabledCount apps. Tap to change."
}

/**
 * What the budget-alerts row says (V2-PLAN §3.4): the channel's state, and
 * what turning it back on takes. Null before the first read on resume.
 */
internal fun budgetAlertsSubtitle(state: AlertChannelState?): String = when (state) {
    null -> "When a budget nears its limit, or is on course to go over."
    AlertChannelState.ON -> "On. When a budget nears its limit, or is on course to go over."
    AlertChannelState.CHANNEL_OFF -> "Off in Android settings. Tap to turn them on."
    AlertChannelState.APP_OFF -> "LedgerFlow's notifications are off. Tap to turn them on."
}

/**
 * D4: shown, never changed. Every amount in the vault is minor units of this
 * currency, so changing it would relabel every figure without converting any.
 * The sentence says so, rather than leaving a row that looks broken.
 */
internal fun baseCurrencySubtitle(state: SettingsUiState): String {
    val code = state.baseCurrency ?: return "Chosen when LedgerFlow was set up."
    // Isolated, so the dirham's right-to-left symbol cannot turn the line
    // around (BUG61).
    val symbol = bidiIsolated(CurrencyDisplay.symbolOf(code))
    return "$symbol ${CurrencyDisplay.nameOf(code)} ($code). Every amount is kept in it, so it can't change."
}

/** A long amount in the base currency, as the chosen grouping writes it. */
internal fun groupingSubtitle(state: SettingsUiState): String {
    val code = state.baseCurrency ?: DEFAULT_CURRENCY
    val sample = MoneyFormat.symbolised(
        GROUPING_SAMPLE_MAJOR * POWERS_OF_TEN[CurrencyExponent.of(code)],
        code,
        grouping = state.region.grouping,
    )
    return when (state.region.grouping) {
        NumberGrouping.BY_CURRENCY -> "$sample, as ${CurrencyDisplay.spokenUnitOf(code)} are written."
        NumberGrouping.LAKH -> "$sample, in lakh and crore."
        NumberGrouping.WESTERN -> "$sample, in thousands and millions."
    }
}

/** One date, in each order: a day past the 12th, so the order cannot be misread. */
internal fun dateOrderSubtitle(order: DateOrder): String {
    val sample = DisplayDates.medium(DATE_SAMPLE, order = order)
    return when (order) {
        DateOrder.AUTO -> "Your region's style: $sample"
        DateOrder.DAY_MONTH_YEAR -> "Day first: $sample"
        DateOrder.MONTH_DAY_YEAR -> "Month first: $sample"
        DateOrder.YEAR_MONTH_DAY -> "Year first: $sample"
    }
}

/** The picker's options: each order named by how it writes the sample date. */
internal fun dateOrderOptions(): List<String> = DateOrder.entries.map { order ->
    when (order) {
        DateOrder.AUTO -> "Region style"
        DateOrder.DAY_MONTH_YEAR, DateOrder.MONTH_DAY_YEAR, DateOrder.YEAR_MONTH_DAY ->
            DisplayDates.medium(DATE_SAMPLE, order = order)
    }
}

private val DATE_SAMPLE: LocalDate = LocalDate.of(2026, 10, 24)
private const val GROUPING_SAMPLE_MAJOR = 1_234_567L
private const val DEFAULT_CURRENCY = "INR"

/** Indexed by ISO-4217 exponent (0, 2 and 3 occur), as `MoneyFormat` keeps it. */
private val POWERS_OF_TEN = longArrayOf(1L, 10L, 100L, 1_000L)

/**
 * One in-place choice: its options, which is selected, and the event a pick
 * sends. Null for a row that navigates.
 */
@Immutable
internal class SettingsChoice(
    val options: List<String>,
    val selectedIndex: Int,
    val eventFor: (Int) -> SettingsEvent,
)

/** The choice an Appearance row offers (V2-PLAN §3.2), or null for any other row. */
internal fun choiceFor(item: SettingsItem, state: SettingsUiState): SettingsChoice? = when (item) {
    SettingsItem.Theme -> SettingsChoice(
        options = ThemePreference.entries.map { it.label },
        selectedIndex = state.appearance.theme.ordinal,
        eventFor = { SettingsEvent.ThemePicked(ThemePreference.entries[it]) },
    )

    SettingsItem.Animations -> SettingsChoice(
        options = AnimationPreference.entries.map { it.label },
        selectedIndex = state.appearance.animations.ordinal,
        eventFor = { SettingsEvent.AnimationsPicked(AnimationPreference.entries[it]) },
    )

    SettingsItem.StartScreen -> SettingsChoice(
        options = StartScreen.entries.map { it.label },
        selectedIndex = state.appearance.startScreen.ordinal,
        eventFor = { SettingsEvent.StartScreenPicked(StartScreen.entries[it]) },
    )

    SettingsItem.NumberGrouping -> SettingsChoice(
        options = NumberGrouping.entries.map { it.label },
        selectedIndex = state.region.grouping.ordinal,
        eventFor = { SettingsEvent.GroupingPicked(NumberGrouping.entries[it]) },
    )

    SettingsItem.BaseCurrency,
    SettingsItem.DateOrder,
    SettingsItem.AppLock,
    SettingsItem.LockAfter,
    SettingsItem.BlockScreenshots,
    SettingsItem.HideAmounts,
    SettingsItem.DataPermissions,
    SettingsItem.NotificationCapture,
    SettingsItem.PaymentApps,
    SettingsItem.BudgetAlerts,
    SettingsItem.Categories,
    SettingsItem.Budgets,
    SettingsItem.BackUpNow,
    SettingsItem.Export,
    SettingsItem.DeletedEntries,
    SettingsItem.Receipts,
    SettingsItem.Diagnostics,
    SettingsItem.AppVersion,
    SettingsItem.WhatsNew,
    SettingsItem.Help,
    SettingsItem.ReportBug,
    SettingsItem.PrivacyPolicy,
    SettingsItem.Terms,
    -> null
}

internal val ThemePreference.label: String
    get() = when (this) {
        ThemePreference.SYSTEM -> "System"
        ThemePreference.LIGHT -> "Light"
        ThemePreference.DARK -> "Dark"
    }

private val AnimationPreference.label: String
    get() = when (this) {
        AnimationPreference.FULL -> "Full"
        AnimationPreference.REDUCED -> "Reduced"
        AnimationPreference.OFF -> "Off"
    }

private val NumberGrouping.label: String
    get() = when (this) {
        NumberGrouping.BY_CURRENCY -> "Currency"
        NumberGrouping.LAKH -> "Lakh"
        NumberGrouping.WESTERN -> "Western"
    }

/** "Home", as the bottom bar names it -- not "Dashboard". */
private val StartScreen.label: String
    get() = when (this) {
        StartScreen.HOME -> "Home"
        StartScreen.LEDGER -> "Ledger"
        StartScreen.ANALYTICS -> "Analytics"
    }

/** One line each at 1.0: the row is a choice to scan, not a paragraph. */
internal fun themeSubtitle(theme: ThemePreference): String = when (theme) {
    ThemePreference.SYSTEM -> "Light or dark, as your phone is set."
    ThemePreference.LIGHT -> "Always light."
    ThemePreference.DARK -> "Always dark."
}

/**
 * What the chosen motion means, and -- the case that would otherwise look like
 * a broken setting -- why nothing moves on a phone that has removed animations,
 * whichever option is picked (SPEC §9.5: the phone's switch always wins).
 */
internal fun animationsSubtitle(state: SettingsUiState): String = when {
    !state.phoneAnimationsOn -> "Your phone has animations removed, so nothing here moves either."
    else -> when (state.appearance.animations) {
        AnimationPreference.FULL -> "Screens slide and fade."
        AnimationPreference.REDUCED -> "Screens fade. Nothing slides."
        AnimationPreference.OFF -> "Nothing moves."
    }
}
