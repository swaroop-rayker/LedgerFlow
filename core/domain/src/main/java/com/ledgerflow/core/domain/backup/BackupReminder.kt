package com.ledgerflow.core.domain.backup

import com.ledgerflow.core.common.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Whether Home should ask for a backup (SPEC.md §8 BUG4(c) as amended by
 * ADR-0025; the owner's decision of 2026-09-19).
 *
 * Backups are manual — a scheduled job cannot seal a phrase-derived `.lfbk`
 * (ADR-0025) — so nothing backs up while the user is not looking, and the More
 * row's date is only read by someone who goes to More. This is the reminder
 * that goes to them instead: shown when there is **no backup at all**, or the
 * last *verified* one is **more than seven days old**, the number BUG4(c)
 * originally named. It is not dismissible while it is true; the way to clear it
 * is a backup.
 */
public sealed interface BackupReminder {

    /** No verified backup exists. The data is on this phone and nowhere else. */
    public data object NeverBackedUp : BackupReminder

    /** The last verified backup is over the threshold; [daysAgo] is whole days. */
    public data class Stale(val daysAgo: Long) : BackupReminder

    public companion object {
        public const val THRESHOLD_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
        private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

        /**
         * The rule itself. Null means "no reminder". A backup dated in the future
         * (a clock set back since) is recent by any honest reading, not stale.
         */
        public fun of(lastBackupAt: Long?, now: Long): BackupReminder? = when {
            lastBackupAt == null -> NeverBackedUp
            now - lastBackupAt > THRESHOLD_MILLIS -> Stale((now - lastBackupAt) / DAY_MILLIS)
            else -> null
        }
    }
}

/**
 * The last verified backup's time, and the clock to judge it by. Two inputs,
 * not a finished answer: time passes while Home is open, so the screen asks
 * [BackupReminder.of] again on each resume rather than trusting an old verdict.
 */
public class ObserveLastBackupUseCase @Inject constructor(
    private val repository: BackupRepository,
    private val clock: Clock,
) {
    public operator fun invoke(): Flow<Long?> = repository.lastBackupAt()

    public fun now(): Long = clock.nowMillis()
}
