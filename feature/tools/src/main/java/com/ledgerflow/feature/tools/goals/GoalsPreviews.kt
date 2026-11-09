package com.ledgerflow.feature.tools.goals

import com.ledgerflow.core.ui.picker.LfPickerGroup
import com.ledgerflow.core.ui.picker.LfPickerOption

/**
 * The owner's mockups of 2026-11-09 as states, for the previews and the
 * goldens. Figures as the app formats them; no formatting runs here, so a
 * golden shows layout and type, not the formatter.
 */
internal object GoalsPreviews {

    val list = GoalsUiState(
        loading = false,
        active = listOf(
            GoalRowUi(
                "g-fund", "Emergency fund", "₹62,000.00", 0.62f,
                "62% of ₹1,00,000.00 · on course for 14 Feb 2027", false,
            ),
            GoalRowUi(
                "g-goa", "Goa trip", "₹18,500.00", 0.46f,
                "46% of ₹40,000.00 · at this pace 3 Jun 2027, about 2 months late", true,
            ),
            GoalRowUi(
                "g-laptop", "New laptop", "₹8,000.00", 0.09f,
                "9% of ₹90,000.00 · about 14 months at this pace", false,
            ),
        ),
        reached = listOf(GoalRowUi("g-bike", "Bike service", "₹6,000.00", 1f, "100% of ₹6,000.00 · reached", false)),
    )

    val empty = GoalsUiState(loading = false)

    val goal = GoalUiState(
        loading = false,
        name = "Emergency fund",
        saved = "₹62,000.00",
        ofTarget = "of ₹1,00,000.00 · ₹38,000.00 to go",
        fraction = 0.62f,
        facts = listOf(
            GoalLineUi("Target 31 Mar 2027", warning = false),
            GoalLineUi("Pace ₹6,450.00 a month, last 90 days", warning = false),
            GoalLineUi("On course for 14 Feb 2027", warning = false),
        ),
        linked = LinkedCategoryUi(
            heading = "From your Savings category · Expense book",
            since = "Since 1 Aug 2026",
            amount = "₹40,000.00",
        ),
        contributions = listOf(
            ContributionRowUi("c-1", "2 Nov", "Diwali bonus", "₹15,000.00", takenOut = false),
            ContributionRowUi("c-2", "5 Sep", "FD matured", "₹9,000.00", takenOut = false),
            ContributionRowUi("c-3", "20 Aug", "Car repair", "−₹2,000.00", takenOut = true),
        ),
    )

    /** A goal with no category, nothing added by hand, and too little history. */
    val newGoal = GoalUiState(
        loading = false,
        name = "New laptop",
        saved = "₹0.00",
        ofTarget = "of ₹90,000.00 · ₹90,000.00 to go",
        fraction = 0f,
        facts = listOf(
            GoalLineUi("No target date", warning = false),
            GoalLineUi("Too early to project a pace", warning = false),
        ),
    )

    val editor = GoalEditorUiState(
        loading = false,
        name = "Emergency fund",
        targetText = "1,00,000",
        targetDate = DAY_31_MAR_2027,
        categoryId = "cat-savings",
        categoryLabel = "Savings · Expense",
        countFrom = DAY_1_AUG_2026,
        categoryGroups = listOf(
            LfPickerGroup(
                "Expense", null,
                listOf(LfPickerOption("cat-savings", "Savings"), LfPickerOption("cat-sav-fd", "Savings / Deposits")),
            ),
            LfPickerGroup("Income", null, listOf(LfPickerOption("cat-interest", "Interest"))),
        ),
    )

    val newEditor = GoalEditorUiState(loading = false, countFrom = DAY_1_AUG_2026)

    private const val DAY_1_AUG_2026 = 20666
    private const val DAY_31_MAR_2027 = 20908
}
