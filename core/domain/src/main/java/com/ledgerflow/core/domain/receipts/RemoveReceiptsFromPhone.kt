package com.ledgerflow.core.domain.receipts

import com.ledgerflow.core.domain.drive.DriveAccess
import com.ledgerflow.core.domain.drive.DriveCall
import com.ledgerflow.core.domain.drive.DriveFiles
import com.ledgerflow.core.domain.drive.DriveLayout
import com.ledgerflow.core.domain.drive.DriveStatusStore
import com.ledgerflow.core.domain.drive.DriveToken
import com.ledgerflow.core.domain.drive.LocalBackupFiles
import java.security.MessageDigest
import javax.inject.Inject

/** What one "Remove from phone" came to (ADR-0035). */
public sealed interface RemovalOutcome {

    /**
     * Google Drive was checked. [removed] receipts (of [bytes]) left the phone;
     * the rest stayed, each for the reason counted.
     */
    public data class Checked(
        val removed: Int,
        val bytes: Long,
        /** No copy in the backup folder sealed to this install, or none on Drive with the same MD5. */
        val notInDriveYet: Int,
        /** Still waiting in the Inbox: its image is what the user reviews. */
        val inInbox: Int,
    ) : RemovalOutcome

    /** Google Drive backup is off: nothing is checked and nothing removed. */
    public data object DriveOff : RemovalOutcome

    /** Drive needs the user to sign in again. Nothing removed. */
    public data object NeedsSignIn : RemovalOutcome

    /** Drive could not be reached (offline, Play services, a refusal). Nothing removed. */
    public data object Unreachable : RemovalOutcome
}

/**
 * Removes receipts from the phone **only where Google Drive holds a checked
 * copy**, checked in this call and never from a stored claim (ADR-0035 §1):
 *
 * 1. Google Drive backup is on and a token is granted for its account;
 * 2. the backup folder holds `attachments/<id>.lfba` sealed to this install's
 *    key ([ReceiptBackupCopies]);
 * 3. Drive's "LedgerFlow backups/attachments" lists a file of that name whose
 *    MD5 equals that file's.
 *
 * A receipt that passes is removed by [ReceiptsRepository.removeFromPhone]:
 * the row stays, the file goes. One that fails stays on the phone, counted.
 * Folders on Drive are only **found**, never made: a check must not create
 * anything.
 */
public class RemoveReceiptsFromPhoneUseCase @Inject constructor(
    private val status: DriveStatusStore,
    private val access: DriveAccess,
    private val drive: DriveFiles,
    private val copies: ReceiptBackupCopies,
    private val receipts: ReceiptsRepository,
) {

    public suspend operator fun invoke(ids: Set<String>): RemovalOutcome {
        if (!status.enabled()) return RemovalOutcome.DriveOff
        return when (val listing = listedWithOneRetry(status.account())) {
            is Listing.Found -> removeChecked(ids, listing.md5ByName)
            Listing.Refused -> RemovalOutcome.NeedsSignIn
            Listing.Failed -> RemovalOutcome.Unreachable
        }
    }

    /**
     * Drive's copies, with one more try on a fresh token, as the copy pass does:
     * Play services may hand back one Drive has already expired.
     */
    private suspend fun listedWithOneRetry(account: String?): Listing =
        when (val first = access.token(account)) {
            is DriveToken.Granted -> when (val listed = driveCopies(first.accessToken)) {
                Listing.Refused -> {
                    access.forget(first.accessToken)
                    when (val again = access.token(account)) {
                        is DriveToken.Granted -> driveCopies(again.accessToken)
                        DriveToken.NeedsSignIn -> Listing.Refused
                        is DriveToken.Unavailable -> Listing.Failed
                    }
                }
                else -> listed
            }
            DriveToken.NeedsSignIn -> Listing.Refused
            is DriveToken.Unavailable -> Listing.Failed
        }

    private suspend fun removeChecked(ids: Set<String>, remote: Map<String, String>): RemovalOutcome.Checked {
        var removed = 0
        var bytes = 0L
        var notInDrive = 0
        var inInbox = 0
        for (id in ids) {
            val receipt = receipts.receipt(id) ?: continue
            when {
                receipt.removedFromPhoneAt != null -> Unit
                receipt.filing is ReceiptFiling.InInbox -> inInbox++
                isCheckedOnDrive(id, remote) && receipts.removeFromPhone(id) -> {
                    removed++
                    bytes += receipt.bytes
                }
                else -> notInDrive++
            }
        }
        return RemovalOutcome.Checked(removed = removed, bytes = bytes, notInDriveYet = notInDrive, inInbox = inInbox)
    }

    private suspend fun isCheckedOnDrive(id: String, remote: Map<String, String>): Boolean {
        val expected = remote[fileNameFor(id)] ?: return false
        val local = copies.sealedCopy(id) ?: return false
        return md5Of(local) == expected.lowercase()
    }

    private sealed interface Listing {
        data class Found(val md5ByName: Map<String, String>) : Listing
        data object Refused : Listing
        data object Failed : Listing
    }

    /** Drive's receipt copies by name, with their MD5. No folder on Drive is no copies, not a failure. */
    private suspend fun driveCopies(token: String): Listing {
        val root = when (val call = drive.findFolder(token, DriveLayout.FOLDER, null)) {
            is DriveCall.Ok -> call.value ?: return Listing.Found(emptyMap())
            DriveCall.Unauthorized -> return Listing.Refused
            is DriveCall.Failed -> return Listing.Failed
        }
        val folder = when (val call = drive.findFolder(token, LocalBackupFiles.ATTACHMENTS, root)) {
            is DriveCall.Ok -> call.value ?: return Listing.Found(emptyMap())
            DriveCall.Unauthorized -> return Listing.Refused
            is DriveCall.Failed -> return Listing.Failed
        }
        return when (val call = drive.list(token, folder)) {
            is DriveCall.Ok -> Listing.Found(
                call.value.mapNotNull { file -> file.md5?.let { file.name to it.lowercase() } }.toMap(),
            )
            DriveCall.Unauthorized -> Listing.Refused
            is DriveCall.Failed -> Listing.Failed
        }
    }

    private fun md5Of(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    public companion object {
        /** A receipt's sidecar name, the one the backup folder and Drive both use (ADR-0023). */
        public fun fileNameFor(id: String): String = "$id.lfba"
    }
}
