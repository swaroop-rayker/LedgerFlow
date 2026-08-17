package com.ledgerflow.core.data.taxonomy

import com.ledgerflow.core.model.LedgerType

/**
 * The starter category set (SPEC.md §5.5).
 *
 * India-first, matching §3.1's framing of the product. Every row is written with
 * `is_system = 1`, which protects it from *deletion* but not from editing — a
 * user who never buys fuel should be able to rename that category rather than
 * work around it.
 *
 * Deliberately not exhaustive. A 60-category starter set looks thorough and is
 * unusable: the picker becomes a search problem on the very first entry, and
 * every unused row is noise in analytics forever. Adding a category is one tap;
 * pruning thirty is not.
 *
 * The two trees are disjoint (Law 2). "Refunds" is a credit category and has no
 * debit counterpart, which is the point.
 */
internal object DefaultTaxonomy {

    internal data class Group(val name: String, val icon: String, val children: List<String>)

    internal val categories: Map<LedgerType, List<Group>> = mapOf(
        LedgerType.DEBIT to listOf(
            Group("Food & Dining", "food", listOf("Groceries", "Restaurants", "Food delivery", "Tea & coffee")),
            Group("Transport", "transport", listOf("Fuel", "Cabs & autos", "Public transport", "Parking & tolls")),
            Group("Bills & Utilities", "bills", listOf("Electricity", "Water", "Internet", "Mobile", "Gas")),
            Group("Home", "home", listOf("Rent", "Maintenance", "Household supplies", "Help & services")),
            Group("Shopping", "shopping", listOf("Clothing", "Electronics", "Home & furniture")),
            Group("Health", "health", listOf("Pharmacy", "Doctor & hospital", "Fitness", "Insurance")),
            Group("Entertainment", "entertainment", listOf("Subscriptions", "Movies & events", "Games")),
            Group("Travel", "travel", listOf("Flights & trains", "Stays", "Holiday spending")),
            Group("Education", "education", listOf("Fees", "Books & courses")),
            Group("Personal care", "personal", listOf("Salon & grooming", "Cosmetics")),
            Group("Family", "family", listOf("Childcare", "Pets", "Gifts")),
            Group("Money out", "money", listOf("Bank charges", "Taxes", "Loan & EMI", "Investments")),
            // Deliberately last and deliberately present: uncategorised spend
            // needs somewhere to live that is not an empty field.
            Group("Other", "other", emptyList()),
        ),
        LedgerType.CREDIT to listOf(
            Group("Salary", "salary", listOf("Take-home pay", "Bonus", "Reimbursements")),
            Group("Business", "business", listOf("Client payments", "Sales")),
            Group("Investments", "investments", listOf("Interest", "Dividends", "Capital gains")),
            Group("Refunds", "refunds", listOf("Purchase refunds", "Cashback")),
            Group("Rent received", "rent", emptyList()),
            Group("Gifts received", "gifts", emptyList()),
            Group("Other income", "other", emptyList()),
        ),
    )

    /**
     * The one payment method every install starts with.
     *
     * Cash and nothing else. Cards, UPI handles and wallets carry an issuer and
     * a last-4 that only the user knows, and inventing "Credit Card" as a
     * placeholder produces an instrument that matches no SMS at P2 and quietly
     * mis-attributes spend in the meantime.
     */
    internal const val DEFAULT_PAYMENT_METHOD_LABEL: String = "Cash"
}
