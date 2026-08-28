package com.ledgerflow.core.designsystem.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.ledgerflow.core.common.time.OccurredAt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When something happened, as a person reads it (SPEC.md §6.2).
 *
 * Moved out of `:feature:ledger` when the Inbox needed the same stamp — the
 * same move `LfPickerDialog` made into `:core:ui`, and for the same reason: two
 * screens formatting a timestamp separately drift on the first change to
 * either, and the drift reads as the two screens disagreeing about when a
 * payment happened. It sits beside [MoneyFormat] because it is the same kind of
 * thing: a value the layers below hold as a number, rendered once at the edge.
 *
 * **12- or 24-hour follows the device**, not the locale and not a constant.
 * `DateFormat.is24HourFormat` is the user's own setting, and it is the only
 * thing that makes a clock look native on someone else's phone.
 */
public object TimeStamp {

    /** `4:12 pm`, or `d MMM, 4:12 pm` with [withDate]. */
    @Composable
    public fun of(occurredAt: Long, withDate: Boolean): String {
        val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        return remember(occurredAt, locale, is24Hour, withDate) {
            format(occurredAt, locale, is24Hour, withDate)
        }
    }

    /**
     * The same stamp for a message that **stated a date but no clock**.
     *
     * **This is not a corner case; it is every bank SMS we have.** All twenty
     * fixtures in `testdata/sms/` — including the four real ones off the
     * owner's phone — carry a date and no time, so `DateText` resolves them
     * through `LocalDate.atStartOfDay` and `occurred_at` lands on midnight.
     * Rendered literally, every SMS-derived row in the app would read
     * `12:00 am` (§16, the `occurred_at` note).
     *
     * The rule itself is [OccurredAt.effective], which lives in `:core:common`
     * rather than here because the Ledger's "Unsaved" section **sorts** by it
     * as well as drawing it — and a display that blended while the sort used
     * the raw value produced a list whose visible times ran 2:49 pm, 4:24 pm,
     * 2:47 pm. One definition, both callers.
     */
    @Composable
    public fun ofCapture(occurredAt: Long, capturedAt: Long, withDate: Boolean): String {
        val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        return remember(occurredAt, capturedAt, locale, is24Hour, withDate) {
            format(
                OccurredAt.effective(occurredAt, capturedAt),
                locale,
                is24Hour,
                withDate,
            )
        }
    }

    private fun format(
        millis: Long,
        locale: Locale,
        is24Hour: Boolean,
        withDate: Boolean,
    ): String {
        val time = if (is24Hour) TIME_24_HOUR else TIME_12_HOUR
        val pattern = if (withDate) DATE_PREFIX + time else time
        return DateTimeFormatter.ofPattern(pattern, locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
    }

    private const val TIME_12_HOUR = "h:mm a"
    private const val TIME_24_HOUR = "HH:mm"
    private const val DATE_PREFIX = "d MMM, "
}
