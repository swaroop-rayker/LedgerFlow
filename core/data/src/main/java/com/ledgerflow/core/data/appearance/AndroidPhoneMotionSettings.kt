package com.ledgerflow.core.data.appearance

import android.content.Context
import android.provider.Settings
import com.ledgerflow.core.domain.appearance.PhoneMotionSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * The phone's "Animator duration scale" (`Settings.Global`), read on demand.
 *
 * Callers read it on resume rather than observing it: the switch lives in the
 * system's Settings app, so returning to LedgerFlow is the moment it can have
 * changed. The platform keeps this table cached in-process, so a read is not a
 * disk access.
 */
internal class AndroidPhoneMotionSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PhoneMotionSettings {

    override fun animatorDurationScale(): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}

/** Settings > Appearance's one platform reader (`docs/V2-PLAN.md` §3.2). */
@Module
@InstallIn(SingletonComponent::class)
internal interface AppearanceModule {

    @Binds
    fun phoneMotionSettings(impl: AndroidPhoneMotionSettings): PhoneMotionSettings
}
