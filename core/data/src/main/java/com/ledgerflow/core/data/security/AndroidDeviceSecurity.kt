package com.ledgerflow.core.data.security

import android.app.KeyguardManager
import android.content.Context
import com.ledgerflow.core.domain.security.DeviceSecurity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Whether the phone has a PIN, pattern or password (`KeyguardManager.isDeviceSecure`). */
internal class AndroidDeviceSecurity @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceSecurity {

    override fun hasScreenLock(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

/** Settings > Security & privacy's one platform reader (V2-PLAN §3.5). */
@Module
@InstallIn(SingletonComponent::class)
internal interface SecurityModule {

    @Binds
    fun deviceSecurity(impl: AndroidDeviceSecurity): DeviceSecurity
}
