package com.ledgerflow.core.domain.ingest

/**
 * The fields a rule can fill (SPEC.md §5.1's extraction targets).
 *
 * An enum rather than free-form strings, because a rule's `fieldMap` is data
 * loaded from an asset a user can edit: a typo like `"merchantRow"` has to be
 * *rejected with a message* rather than silently mapping nothing, which is what
 * a `Map<String, String>` of arbitrary keys would do.
 */
public enum class ExtractionField {
    AMOUNT,
    CURRENCY,
    DIRECTION,
    MERCHANT_RAW,
    ACCOUNT_LAST4,
    INSTRUMENT_HINT,
    REFERENCE_NO,
    OCCURRED_AT,
    AVAILABLE_BALANCE,
    ;

    public companion object {
        /** Null for an unrecognised name, so the loader can report it rather than throw. */
        public fun fromWireName(name: String): ExtractionField? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }

    /** The name used in `parser_rules/v{N}.json`, which is camelCase like the spec's field list. */
    public val wireName: String
        get() = name.split('_').mapIndexed { index, part ->
            if (index == 0) part.lowercase() else part.lowercase().replaceFirstChar(Char::uppercase)
        }.joinToString("")
}

/**
 * One versioned extraction rule (SPEC.md §5.1).
 *
 * Rules ship in `assets/parser_rules/v{N}.json` and are loaded into the
 * `parser_rule` table on first run and on version bump. They live in a table as
 * well as an asset because §5.1 gives the user a rule editor: a rule the user
 * wrote has nowhere else to live, and a shipped rule they disabled has to
 * survive the next ruleset load.
 *
 * **One rule shape serves both sources.** [senderPattern] is matched against an
 * SMS's sender and against a notification's package name — §5.2 is explicit
 * that this is the *only* source-specific thing about the engine, which is why
 * it is a field on the rule rather than a branch in the code.
 *
 * @param priority lower runs first. Ties break on [id], so matching is
 *   deterministic across a table whose row order is not.
 * @param bodyPattern a regex with named groups. [fieldMap] says which group
 *   feeds which field, rather than the group names being the field names — so
 *   one carefully-tuned pattern can serve two rules that read it differently,
 *   and a group can be renamed without breaking the mapping.
 * @param direction set when the rule itself decides the book (a rule that only
 *   matches "debited" knows). Null hands the decision to [fieldMap]'s
 *   `direction` group, and failing that to
 *   [ExtractedDirection.UNKNOWN] — never to a guess.
 * @param instrumentHint set when the rule itself knows how the money moved. A
 *   rule that only matches GPay, PhonePe, Paytm and BHIM is describing a UPI
 *   payment whether or not the notification says the word — and many do not.
 *   Same precedence as [direction]: the rule wins, then [fieldMap]'s group,
 *   then a sniff of the body. Carried on the rule rather than inferred from the
 *   package, because inferring it would be the engine branching on source, and
 *   §5.2 allows exactly one of those.
 * @param confidenceBase where a match starts before per-field adjustment. Not
 *   money; Law 3's ban is on amounts.
 * @param isUserDefined true for a rule the user wrote. A ruleset load never
 *   touches one.
 */
public data class ParserRule(
    val id: String,
    val rulesetVersion: Int,
    val priority: Int,
    val senderPattern: String,
    val bodyPattern: String,
    val fieldMap: Map<ExtractionField, String>,
    val direction: ExtractedDirection? = null,
    val instrumentHint: InstrumentHint? = null,
    val confidenceBase: Double = DEFAULT_CONFIDENCE,
    val enabled: Boolean = true,
    val isUserDefined: Boolean = false,
) {
    public companion object {
        /**
         * What a rule is worth before the engine looks at what it filled in.
         *
         * Deliberately not 1.0. A regex matching is evidence, not proof — the
         * same message can match a rule written for a different bank whose
         * wording happens to overlap, and the review screen should show that as
         * "probably" rather than "certainly".
         */
        public const val DEFAULT_CONFIDENCE: Double = 0.75
    }
}
