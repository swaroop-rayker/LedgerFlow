package com.ledgerflow.feature.tools.goals

import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.picker.LfPickerGroup
import com.ledgerflow.core.ui.picker.LfPickerOption

/** A category a goal can count, with the book it belongs to. */
internal data class CategoryChoice(val id: String, val label: String, val ledger: LedgerType)

/** "Expense" or "Income", as the entry form's book switch says it. */
internal fun bookName(ledger: LedgerType): String = when (ledger) {
    LedgerType.DEBIT -> "Expense"
    LedgerType.CREDIT -> "Income"
}

/** Every live category of one book, parents then their subcategories ("Savings / Deposits"). */
internal fun categoryChoices(ledger: LedgerType, trees: List<CategoryTree>): List<CategoryChoice> =
    trees.flatMap { tree ->
        listOf(CategoryChoice(tree.parent.id, tree.parent.name, ledger)) +
            tree.children.map { CategoryChoice(it.id, "${tree.parent.name} / ${it.name}", ledger) }
    }

/** The picker's two groups: each book under its own heading (Law 2: a goal reads one book). */
internal fun pickerGroups(choices: List<CategoryChoice>): List<LfPickerGroup> =
    LedgerType.entries.mapNotNull { ledger ->
        val options = choices.filter { it.ledger == ledger }.map { LfPickerOption(it.id, it.label) }
        if (options.isEmpty()) null else LfPickerGroup(bookName(ledger), colorArgb = null, options = options)
    }
