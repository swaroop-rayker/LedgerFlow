package com.ledgerflow.core.model

/**
 * A spending or income category (SPEC.md §5.5).
 *
 * The tree is exactly two levels: a category has a parent or is one. There is no
 * third level and the schema does not prevent one, so [parentId] pointing at a
 * row that itself has a parent is a bug `CategoryRepository` refuses rather than
 * a shape the model can express.
 *
 * `ledger` is not a filter -- the two ledgers have **disjoint** category trees
 * (Law 2). "Salary" is not a debit category that happens to be unused.
 */
public data class Category(
    val id: String,
    val parentId: String?,
    val ledger: LedgerType,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val isSystem: Boolean,
) {
    public val isSubcategory: Boolean get() = parentId != null
}

/** A top-level category with its subcategories, ready for a list screen. */
public data class CategoryTree(
    val parent: Category,
    val children: List<Category>,
)

/**
 * A canonical merchant (SPEC.md §5.5).
 *
 * [normalizedKey] is `UNIQUE` in the schema and is what makes "SWIGGY*ORDER
 * 4821" and "Swiggy" the same merchant. It is derived, never user-entered.
 */
public data class Merchant(
    val id: String,
    val canonicalName: String,
    val normalizedKey: String,
    val defaultCategoryId: String?,
    val logoRef: String?,
)

/** A user-defined instrument (SPEC.md §5.5). */
public data class PaymentMethod(
    val id: String,
    val type: PaymentMethodType,
    val label: String,
    val issuer: String?,
    /** Auto-selects this instrument from a parsed SMS at P2. */
    val last4: String?,
    val colorArgb: Int?,
    val isDefault: Boolean,
)

/**
 * A hidden taxonomy row, projected for the list that offers to bring it back
 * (ADR-0016).
 *
 * One shape for all three types rather than three, because the list that shows
 * them is one list repeated three times: a name, when it went, and a second
 * line of whatever distinguishes that type. Carrying [Category], [Merchant] and
 * [PaymentMethod] instead would mean three near-identical composables differing
 * only in which field they read — and the section is not a picker, so nothing
 * downstream needs the rest of the row.
 *
 * [detail] is that second line, and it is doing real work rather than
 * decoration: for a hidden category it says whether the row is a branch that
 * took subcategories out with it, which is what makes restoring it predictable.
 */
public data class HiddenTaxonomy(
    val id: String,
    val name: String,
    /** When it was hidden, from `deleted_at`. The list is ordered by it. */
    val hiddenAt: Long,
    /** A subcategory's parent, a branch's child count, a card's type and last 4. */
    val detail: String? = null,
)

/**
 * The 16 category swatches (SPEC.md §9.1).
 *
 * Curated rather than free choice: an arbitrary colour picker lets a user
 * choose something that fails contrast against one of the two surfaces, and
 * §9.6 makes contrast a gate rather than a preference.
 *
 * **Every swatch sits in a narrow luminance band, and that is not an aesthetic
 * choice.** Two constraints squeeze from opposite sides: the white initial in
 * `LfCategoryDot` needs 4.5:1 against the swatch, which caps relative luminance
 * at 0.183; and the swatch needs 3:1 against the darkest surface it is drawn on
 * (`surfaceRaised` at #1D2027), which floors it at 0.144. Every value here was
 * tuned to land near 0.163 -- the middle of that band -- so the hues differ and
 * the brightness deliberately does not. `CategoryPaletteContrastTest` fails the
 * build if an edit drifts outside it.
 *
 * Plain `Int` ARGB rather than a Compose `Color` so `:core:model` stays free of
 * Android (CLAUDE.md §3).
 */
public object CategoryPalette {

    public val swatches: List<Int> = listOf(
        0xFF3E6AD6.toInt(), // indigo
        0xFF2C75AE.toInt(), // ocean
        0xFF207E6E.toInt(), // teal
        0xFF2F7E50.toInt(), // forest
        0xFF5C7A1E.toInt(), // olive
        0xFF8C6C12.toInt(), // amber
        0xFFAB5C15.toInt(), // ochre
        0xFFB85220.toInt(), // rust
        0xFFC44545.toInt(), // brick
        0xFFC14271.toInt(), // raspberry
        0xFFA44DAE.toInt(), // plum
        0xFF7E59D4.toInt(), // violet
        0xFF63718A.toInt(), // slate
        0xFF886A57.toInt(), // walnut
        0xFF337A8A.toInt(), // lagoon
        0xFFB8458A.toInt(), // mulberry
    )

    /** Stable per category, so the same name keeps its colour across installs. */
    public fun forIndex(index: Int): Int = swatches[Math.floorMod(index, swatches.size)]
}
