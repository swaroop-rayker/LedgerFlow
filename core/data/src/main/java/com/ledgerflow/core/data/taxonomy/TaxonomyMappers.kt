package com.ledgerflow.core.data.taxonomy

import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod

/**
 * Room row <-> domain model.
 *
 * The domain models deliberately drop `deleted_at` and `parent_key`. Both are
 * storage mechanics: soft-deleted rows never leave the repository, and
 * `parent_key` is the `COALESCE(parent_id, '')` sentinel that makes the
 * uniqueness index enforceable (§6.1.1). Exposing either would invite a caller
 * to set them, and `parent_key` drifting out of step with `parent_id` silently
 * disables the constraint.
 */
internal fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    parentId = parentId,
    ledger = ledgerScope,
    name = name,
    icon = icon,
    colorArgb = colorArgb,
    sortOrder = sortOrder,
    isSystem = isSystem,
)

internal fun MerchantEntity.toDomain(): Merchant = Merchant(
    id = id,
    canonicalName = canonicalName,
    normalizedKey = normalizedKey,
    defaultCategoryId = defaultCategoryId,
    logoRef = logoRef,
)

internal fun PaymentMethodEntity.toDomain(): PaymentMethod = PaymentMethod(
    id = id,
    type = type,
    label = label,
    issuer = issuer,
    last4 = last4,
    colorArgb = colorArgb,
    isDefault = isDefault,
)

/** `COALESCE(parent_id, '')`, in one place so it cannot be spelled two ways. */
internal fun parentKeyOf(parentId: String?): String = parentId ?: ""
