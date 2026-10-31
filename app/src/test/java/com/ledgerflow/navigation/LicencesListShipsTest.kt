package com.ledgerflow.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The licences list About shows is generated into this build (ADR-0032), in
 * whichever flavour this run builds, and covers what LedgerFlow is made of.
 *
 * Read through the real binding, so a renamed resource, a plugin left off
 * `:app`, or resource shrinking removing the file all fail here rather than as
 * an About page that lists only the fonts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LicencesListShipsTest {

    private val json = requireNotNull(
        LicencesModule.licenceListSource(ApplicationProvider.getApplicationContext()).json(),
    ) { "res/raw/aboutlibraries.json is missing from this build" }

    private val libraries = JSONObject(json).getJSONArray("libraries").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun theListNamesTheLibrariesTheAppIsBuiltOn() {
        val ids = libraries.map { it.getString("uniqueId") }

        assertThat(libraries.size).isAtLeast(MIN_LIBRARIES)
        assertThat(ids).containsAtLeast(
            "androidx.compose.ui:ui",
            "net.zetetic:sqlcipher-android",
            "com.google.dagger:hilt-android",
        )
    }

    @Test
    fun everyLibrary_hasALicence() {
        val without = libraries.filter { (it.optJSONArray("licenses")?.length() ?: 0) == 0 }
            .map { it.getString("uniqueId") }

        assertThat(without).isEmpty()
    }

    /** ADR-0032: names, versions and licences only. */
    @Test
    fun nothingButWhatThePageShows_isKept() {
        val keys = libraries.flatMap { it.keys().asSequence().toList() }.toSet()

        assertThat(keys).containsNoneOf("developers", "funding", "organization", "scm")
    }

    private companion object {
        const val MIN_LIBRARIES = 100
    }
}
