package com.ledgerflow

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Hilt's object-graph root.
 *
 * Still deliberately thin. StrictMode (with `penaltyDeath` in debug),
 * WorkManager configuration and the crash handler arrive with the steps that
 * introduce them; what this class holds now is the `@HiltAndroidApp` trigger,
 * without which no `@AndroidEntryPoint` in the app can be injected.
 */
@HiltAndroidApp
public class LedgerFlowApplication : Application()
