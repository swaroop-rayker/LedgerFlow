package com.ledgerflow.core.rates

/**
 * The converter's rates (ADR-0036): the last good table, and a fetch that runs
 * **only when the user taps for it** (§3). Nothing here is the user's data,
 * and nothing here reaches an entry (§6).
 */
public interface RatesSource {

    /** The last good table, or `null` when none was ever fetched. */
    public suspend fun cached(): RateTable?

    /** One request to [EcbRates.ADDRESS]; on success the table is also cached. */
    public suspend fun fetch(): RatesFetch
}

public sealed interface RatesFetch {
    public data class Fetched(val table: RateTable) : RatesFetch

    /** The cached table, if any, stays in use. */
    public data class Failed(val reason: FetchFailure) : RatesFetch
}

public enum class FetchFailure {
    /** No connection, a timeout, a TLS failure. */
    OFFLINE,

    /** Any answer but 200, a redirect included: a redirect is not followed (§2). */
    REFUSED,

    /** More than [EcbRatesSource.MAX_BYTES]; the table is about 2 KiB. */
    TOO_LARGE,

    /** Not exactly the ECB's format ([EcbRates.parse]). */
    MALFORMED,
}
