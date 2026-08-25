package com.ledgerflow.feature.ingest.adapters

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.domain.ingest.IngestSourceStatus
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.TransactionIngestSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The SMS source — **`smsFull` flavour only** (SPEC.md §3.1, D-04).
 *
 * `playSafe` compiles a class with this exact fully-qualified name that reports
 * [IngestSourceStatus.UNSUPPORTED_IN_BUILD] and holds nothing. That is what lets
 * the shared Hilt module in `src/main` bind "the SMS source" once, for both
 * flavours, with no `if` anywhere: the flavour source set decides which body
 * that name resolves to, and the compiler picks exactly one.
 *
 * The capture itself is [SmsIngestReceiver]. This object only answers whether
 * that receiver is going to hear anything.
 */
@Singleton
public class SmsAdapter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TransactionIngestSource {

    override val sourceType: IngestSourceType = IngestSourceType.SMS

    // FEATURE_TELEPHONY, not the granular FEATURE_TELEPHONY_MESSAGING: the
    // latter only exists from API 31, and on a 26-30 phone `hasSystemFeature`
    // would answer false for it and declare a device with a working SIM
    // unavailable. Deprecated since 35 and still reported by every device that
    // has a radio, which is the property that matters at minSdk 26.
    @Suppress("DEPRECATION")
    override suspend fun status(): IngestSourceStatus = withContext(ioDispatcher) {
        statusFor(
            hasTelephony = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    internal companion object {

        /**
         * The decision, separated from the platform lookups so it is testable
         * off-device — there is no Robolectric in this build and adding one for
         * two `if`s would be a dependency proposal (CLAUDE.md §10) for very
         * little.
         *
         * Hardware is checked **before** the permission because the two answers
         * are not equally useful: on a Wi-Fi tablet, `RECEIVE_SMS` can be
         * granted and still never deliver a message, and a screen that reported
         * `PERMISSION_REQUIRED` there would send the user to a prompt that
         * changes nothing.
         */
        internal fun statusFor(hasTelephony: Boolean, permissionGranted: Boolean): IngestSourceStatus =
            when {
                !hasTelephony -> IngestSourceStatus.UNAVAILABLE_ON_DEVICE
                !permissionGranted -> IngestSourceStatus.PERMISSION_REQUIRED
                else -> IngestSourceStatus.READY
            }
    }
}
