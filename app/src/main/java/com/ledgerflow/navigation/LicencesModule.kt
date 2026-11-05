package com.ledgerflow.navigation

import android.content.Context
import com.ledgerflow.R
import com.ledgerflow.feature.settings.about.LicenceListSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * The open-source licences list (Settings > About, ADR-0032).
 *
 * AboutLibraries' Gradle plugin writes `res/raw/aboutlibraries.json` into this
 * module at build time; only `:app` can name that resource, so the feature
 * reads it through [LicenceListSource] bound here. Read off the main thread by
 * the caller (`AboutTexts.licences`).
 */
@Module
@InstallIn(SingletonComponent::class)
public object LicencesModule {

    @Provides
    public fun licenceListSource(@ApplicationContext context: Context): LicenceListSource = LicenceListSource {
        runCatching {
            context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}
