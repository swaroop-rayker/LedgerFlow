package com.ledgerflow.core.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.AppMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hide amounts on Home in the vault (V2-PLAN §3.5; owner 2026-10-31): off is
 * the key's absence, so a vault or backup from before the switch reads as off,
 * and on is a row in `app_meta`, so a backup carries it.
 */
@RunWith(AndroidJUnit4::class)
class VaultHideAmountsSettingTest {

    private val vault = LedgerTestVault("lf_hide_amounts_test")
    private lateinit var setting: VaultHideAmountsSetting

    @Before
    fun setUp() = runBlocking {
        vault.open()
        setting = VaultHideAmountsSetting(vault.session, Dispatchers.IO)
    }

    @After
    fun tearDown() = vault.close()

    @Test
    fun aVaultThatNeverTouchedIt_isOff() = runBlocking {
        assertThat(setting.enabled.first()).isFalse()
        assertThat(vault.database.appMetaDao().value(AppMetaEntity.KEY_HIDE_AMOUNTS_ON_HOME)).isNull()
    }

    @Test
    fun on_isARowInAppMeta_andOff_removesIt() = runBlocking {
        setting.set(true)
        assertThat(setting.enabled.first()).isTrue()
        assertThat(vault.database.appMetaDao().all().map { it.key })
            .contains(AppMetaEntity.KEY_HIDE_AMOUNTS_ON_HOME)

        setting.set(false)
        assertThat(setting.enabled.first()).isFalse()
        assertThat(vault.database.appMetaDao().value(AppMetaEntity.KEY_HIDE_AMOUNTS_ON_HOME)).isNull()
    }
}
