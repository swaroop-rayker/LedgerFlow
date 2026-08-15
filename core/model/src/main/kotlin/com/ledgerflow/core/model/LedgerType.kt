package com.ledgerflow.core.model

/**
 * The partition key for the two disjoint ledgers (Law 2, ADR-0002).
 *
 * DEBIT and CREDIT are two separate books, not a sign on one column. Nothing in
 * the app may net, sum or offset them against each other -- there is
 * deliberately no `total()` here that takes both.
 */
public enum class LedgerType {
    DEBIT,
    CREDIT,
    ;

    public companion object {
        public fun fromStorage(value: String): LedgerType? =
            entries.firstOrNull { it.name == value }
    }
}

/** Where an entry came from. Kept for audit, never for branching logic. */
public enum class EntrySource {
    SMS,
    NOTIFICATION,
    OCR,
    MANUAL,
    IMPORT,
}

/** Instrument types a payment method can be (SPEC.md §5.5). */
public enum class PaymentMethodType {
    DEBIT_CARD,
    CREDIT_CARD,
    UPI,
    CASH,
    NETBANKING,
    WALLET,
    OTHER,
}

/** Line-item classification for receipt and manual multi-line entries. */
public enum class LineItemKind {
    ITEM,
    TAX,
    DISCOUNT,

    /**
     * The reconciliation delta when a bill does not balance (SPEC.md §5.3).
     * Stored explicitly so totals never silently drift.
     */
    UNALLOCATED,
}
