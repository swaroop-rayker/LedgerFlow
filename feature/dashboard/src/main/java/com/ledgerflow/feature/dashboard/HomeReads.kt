package com.ledgerflow.feature.dashboard

import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.security.HideAmountsSetting
import com.ledgerflow.core.domain.usecase.GetHomeSummaryUseCase
import com.ledgerflow.core.domain.usecase.ObservePendingCountUseCase
import javax.inject.Inject

/**
 * What Home v1 reads (V2-PLAN BUG-C), injected as one.
 *
 * Home is still one screen with one ViewModel, as `DashboardUiState` has always
 * said it would be; this only keeps the v1 reads together and apart from the
 * banners', which were there first and are wired one by one.
 */
public class HomeReads @Inject constructor(
    internal val observePendingCount: ObservePendingCountUseCase,
    internal val getHomeSummary: GetHomeSummaryUseCase,
    internal val ledger: LedgerRepository,
    internal val clock: Clock,
    /** Hide amounts on Home (V2-PLAN §3.5), kept in the vault. */
    internal val hideAmounts: HideAmountsSetting,
)
