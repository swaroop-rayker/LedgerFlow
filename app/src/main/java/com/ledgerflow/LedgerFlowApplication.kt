package com.ledgerflow

import android.app.Application

/**
 * Application entry point.
 *
 * Deliberately minimal at Phase 0 Step 2. StrictMode (with `penaltyDeath` in
 * debug), Hilt, WorkManager configuration and the crash handler arrive with the
 * steps that introduce them -- this class exists now to prove the Kotlin
 * toolchain compiles, not to hold logic.
 */
public class LedgerFlowApplication : Application()
