package com.ledgerflow.core.designsystem.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * The locale dates and month names are formatted in on screen
 * (owner, 2026-10-21; BUG61).
 *
 * **English, in the phone's region**, for as long as the app's own text is
 * English only. A date formatted for the phone's *language* lands inside an
 * English sentence: under Arabic, "21/10/2026" arrives with right-to-left marks
 * that reorder it ("Last backup 212026/10/."), Arabic-Indic digits, and month
 * names in Arabic script. Keeping the region keeps what the user expects of a
 * date's order: an Indian phone still gets "21 Oct 2026", a US one "Oct 21,
 * 2026".
 *
 * Not for anything written to a file or parsed from a file: those name
 * `Locale.ROOT` or a fixed pattern already. **Amounts too, since V2-3** (SPEC
 * §16 Q26): `MoneyFormat` and `QuantityFormat` display *and* parse with this
 * locale, moved together in one change so the field reads the separators the
 * screen shows. Dates go through `DisplayDates`, which also applies Settings >
 * Currency & region's date order. Revisit when a translation ships: the
 * answer then is the app's language, which this becomes.
 */
public object DisplayLocale {

    /** For [device] -- unchanged when it is already English. */
    public fun of(device: Locale): Locale {
        if (device.language == APP_LANGUAGE) return device
        return try {
            Locale.Builder().setLanguage(APP_LANGUAGE).setRegion(device.country).build()
        } catch (_: IllformedLocaleException) {
            Locale.ENGLISH
        }
    }

    /** For the JVM's current default, outside composition. */
    public fun current(): Locale = of(Locale.getDefault())

    /** In composition, from the composition's locale, so a language change recomposes. */
    public val composed: Locale
        @Composable get() = of(LocalLocale.current.platformLocale)

    private const val APP_LANGUAGE = "en"
}
