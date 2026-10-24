package com.ledgerflow.feature.settings

import com.ledgerflow.core.domain.appearance.AppearanceStore
import com.ledgerflow.core.domain.appearance.PhoneMotionSettings
import com.ledgerflow.core.domain.region.RegionStore
import com.ledgerflow.core.domain.security.DeviceSecurity
import com.ledgerflow.core.domain.security.SecurityStore
import javax.inject.Inject

/**
 * The device-local preference stores V2-3's Settings rows read and write
 * (ADR-0030), and the two platform readers beside them, as one injection.
 *
 * One parameter on [SettingsViewModel] rather than five: they are one concern
 * -- how this phone shows and guards LedgerFlow, outside the vault -- and the
 * ViewModel's other dependencies are the vault-backed rows.
 */
public class DevicePreferences @Inject constructor(
    public val appearance: AppearanceStore,
    public val region: RegionStore,
    public val security: SecurityStore,
    public val phoneMotion: PhoneMotionSettings,
    public val deviceSecurity: DeviceSecurity,
)
