package com.ledgerflow.core.domain.taxonomy

import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.MerchantCategory
import kotlinx.coroutines.flow.Flow

/**
 * Merchant categories, read and nothing else (V2-6 PR 3).
 *
 * The entry form and the Inbox review screen show merchants grouped by kind in
 * their picker, so they read the categories; they must never file a merchant
 * (V2-PLAN §5.2). They are given this view rather than
 * [MerchantCategoryRepository], so the writing methods are not there to call,
 * and `MerchantCategorySingleWriterTest` still refuses the full repository in
 * those modules.
 */
public fun interface MerchantCategoryReader {

    /** Live categories in display order. */
    public fun observeAll(): Flow<List<MerchantCategory>>
}

/**
 * One merchant category's merchants: [category] is null for Uncategorised.
 */
public data class MerchantGroup(val category: MerchantCategory?, val merchants: List<Merchant>)

/** What no merchant category is called (V2-PLAN §5): a bucket, shown last, never a category of its own. */
public const val UNCATEGORISED_MERCHANTS: String = "Uncategorised"

/** A group's heading: its category's name, or [UNCATEGORISED_MERCHANTS]. */
public val MerchantGroup.title: String get() = category?.name ?: UNCATEGORISED_MERCHANTS

/**
 * [merchants] under their merchant categories, the one rule Organise and the
 * merchant picker share (owner, 2026-11-01): the categories in their own order,
 * each only if it holds a merchant, then Uncategorised last, as Analytics
 * orders it. A merchant whose category is not among [categories] (hidden a
 * moment ago, before its merchants moved) counts as Uncategorised, which is
 * what Analytics will call it. Merchants keep their order within a group.
 */
public fun groupByMerchantCategory(
    merchants: List<Merchant>,
    categories: List<MerchantCategory>,
): List<MerchantGroup> {
    val live = categories.mapTo(HashSet()) { it.id }
    val byCategory = merchants.groupBy { merchant -> merchant.merchantCategoryId?.takeIf { it in live } }
    return categories.mapNotNull { category -> byCategory[category.id]?.let { MerchantGroup(category, it) } } +
        listOfNotNull(byCategory[null]?.let { MerchantGroup(null, it) })
}

/**
 * What the merchant picker groups (V2-6 PR 3), shared by the entry form and the
 * Inbox review screen so the two cannot drift: the groups while [query] is
 * blank, and nothing (a flat list) once the user types, since a search already
 * knows the name. Also nothing while no merchant is filed: one Uncategorised
 * heading over every merchant would say nothing.
 */
public fun merchantPickerGroups(
    merchants: List<Merchant>,
    categories: List<MerchantCategory>,
    query: String,
): List<MerchantGroup> {
    if (query.isNotBlank()) return emptyList()
    val groups = groupByMerchantCategory(merchants, categories)
    return if (groups.all { it.category == null }) emptyList() else groups
}
