package com.ledgerflow.feature.settings.receipts

import com.ledgerflow.core.domain.receipts.AgeRemovalRecord
import com.ledgerflow.feature.settings.formatSize

/**
 * Removal by age's words (ADR-0035 §6), apart from [ReceiptsText] so each stays
 * a readable size; pinned by `ReceiptsTextTest`.
 */
internal object ReceiptsAgeText {


    const val AGE_TITLE: String = "Remove from phone after"

    fun ageValue(days: Int?): String = days?.let { "$it days" } ?: "Off"

    /** The picker's choices: off, then the owner's three ages. */
    fun ageOptions(choices: List<Int>): List<String> = listOf(ageValue(null)) + choices.map(::ageValue)

    const val AGE_PICKER_BODY: String =
        "Receipts filed in your books, older than this, with a checked Google Drive copy."

    /**
     * The row's sentence. While Drive is off it says why the row does nothing.
     * The last run is named only when it removed something: a run that found
     * nothing old enough is not news.
     */
    fun ageDetail(driveOn: Boolean, lastDay: String?, last: AgeRemovalRecord?): String = when {
        !driveOn -> "Needs Google Drive backup. Turn it on in Data, Google Drive."
        last != null && lastDay != null ->
            "Only receipts with a checked Google Drive copy. " +
                "Last removed on $lastDay: ${ReceiptsText.receipts(last.removed)} (${formatSize(last.bytes)})."
        else -> "Only receipts with a checked Google Drive copy."
    }

    fun ageConfirmTitle(days: Int): String = "Remove receipts after $days days?"

    /** The same plain words as a manual removal: Drive keeps a copy, and seeing it again takes the 24 words. */
    fun ageConfirmBody(days: Int): String =
        "After each Google Drive copy, receipts older than $days days leave this phone once Drive holds " +
            "a checked copy. To see one again you'll need to restore a backup with your 24 words. " +
            "Receipts still in the Inbox, and any without a checked copy, stay."
}
