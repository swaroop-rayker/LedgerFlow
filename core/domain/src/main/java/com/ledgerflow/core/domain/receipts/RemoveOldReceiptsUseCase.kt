package com.ledgerflow.core.domain.receipts

import com.ledgerflow.core.common.time.Clock
import javax.inject.Inject

/**
 * Removal by age (ADR-0035 §6): receipts filed in a book and older than the
 * chosen age leave the phone, **each only through the same check "Remove from
 * phone" makes** ([RemoveReceiptsFromPhoneUseCase]): a sealed copy in the
 * backup folder and the same bytes on Drive.
 *
 * Called by the Drive worker after a pass that copied everything
 * (`DriveOutcome.Copied`), never after a failed, partial or signed-out one, so
 * it always runs against a Drive that has just been brought up to date. Off by
 * default: it does nothing until the user picks an age.
 *
 * @return what it removed, also recorded; null when off, when nothing was old
 *   enough, or when nothing passed the check.
 */
public class RemoveOldReceiptsUseCase @Inject constructor(
    private val setting: ReceiptAgeRemoval,
    private val receipts: ReceiptsRepository,
    private val removeFromPhone: RemoveReceiptsFromPhoneUseCase,
    private val clock: Clock,
) {

    public suspend operator fun invoke(): AgeRemovalRecord? {
        val days = setting.afterDays() ?: return null
        val now = clock.nowMillis()
        val ids = receipts.filedBefore(now - days * DAY_MILLIS)
        if (ids.isEmpty()) return null
        val removed = (removeFromPhone(ids.toSet()) as? RemovalOutcome.Checked)?.takeIf { it.removed > 0 }
            ?: return null
        return AgeRemovalRecord(at = now, removed = removed.removed, bytes = removed.bytes)
            .also { setting.record(it) }
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
