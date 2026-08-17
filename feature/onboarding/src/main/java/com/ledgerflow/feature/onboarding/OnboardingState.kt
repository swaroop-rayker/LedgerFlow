package com.ledgerflow.feature.onboarding

import androidx.compose.runtime.Immutable

/**
 * The onboarding gate (SPEC.md §7.4).
 *
 * The user cannot reach the app until every step is satisfied. The steps are an
 * ordered enum rather than free navigation precisely so "skip" has nowhere to
 * live.
 */
public enum class OnboardingStep {
    /** Chosen once, cannot be changed later in v1 (§5.8). */
    BaseCurrency,

    /** The 24 words, shown for transcription. */
    PhraseDisplay,

    /** Three positions re-entered. No skip button (§7.4). */
    WordChallenge,

    /** Save the Recovery Kit, or dismiss after an explicit warning. */
    RecoveryKit,

    /** Grant a SAF tree for automatic backups, or decline with a warning. */
    BackupLocation,

    Complete,
    ;

    public fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { Complete }
}

/** A currency the user can pick at onboarding. */
@Immutable
public data class CurrencyOption(
    val code: String,
    val displayName: String,
    val symbol: String,
)

/**
 * The shortlist offered at onboarding. Deliberately short and INR-first: this
 * is an India-first product (§3.1), and a 180-entry picker as the very first
 * screen is a worse experience than a short list plus search later.
 */
public val SupportedCurrencies: List<CurrencyOption> = listOf(
    CurrencyOption("INR", "Indian Rupee", "₹"),
    CurrencyOption("USD", "US Dollar", "$"),
    CurrencyOption("EUR", "Euro", "€"),
    CurrencyOption("GBP", "British Pound", "£"),
    CurrencyOption("AED", "UAE Dirham", "د.إ"),
    CurrencyOption("SGD", "Singapore Dollar", "S$"),
    CurrencyOption("AUD", "Australian Dollar", "A$"),
    CurrencyOption("JPY", "Japanese Yen", "¥"),
)

@Immutable
public data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.BaseCurrency,
    val selectedCurrency: String = "INR",
    val mnemonic: List<String> = emptyList(),
    val challengePositions: List<Int> = emptyList(),
    val challengeAnswers: List<String> = List(WordChallenge.CHALLENGE_COUNT) { "" },
    val challengeError: Boolean = false,
    val phraseRevealed: Boolean = false,
    val recoveryKitSaved: Boolean = false,
    val backupLocationGranted: Boolean = false,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Drives the challenge's continue button. All three, or none. */
    public val challengeSatisfied: Boolean
        get() = challengeAnswers.size == WordChallenge.CHALLENGE_COUNT &&
            challengeAnswers.all { it.isNotBlank() }
}

/** Everything the UI can ask for, as one type (CLAUDE.md §5). */
public sealed interface OnboardingEvent {
    public data class CurrencySelected(val code: String) : OnboardingEvent
    public data object PhraseRevealed : OnboardingEvent
    public data object PhraseAcknowledged : OnboardingEvent
    public data class ChallengeAnswerChanged(val index: Int, val answer: String) : OnboardingEvent
    public data object ChallengeSubmitted : OnboardingEvent
    public data class RecoveryKitSaved(val uri: String) : OnboardingEvent
    public data object RecoveryKitDismissed : OnboardingEvent
    public data class BackupLocationGranted(val uri: String) : OnboardingEvent
    public data object BackupLocationDeclined : OnboardingEvent
    public data object ErrorDismissed : OnboardingEvent
}
