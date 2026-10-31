package com.ledgerflow.feature.settings.about

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * The open-source licences list (Settings > About, V2-3; ADR-0032).
 *
 * `:app`'s build writes `res/raw/aboutlibraries.json` (AboutLibraries' Gradle
 * plugin, build time only) and binds this to read it, so this module never
 * names `:app`'s resources.
 */
public fun interface LicenceListSource {
    /** The generated JSON, or null when the build did not produce one. */
    public fun json(): String?
}

/** One licence as About shows it: its name, where its terms live, and a bundled text when there is one. */
@Immutable
public data class LicenceInfo(val id: String, val name: String, val url: String?, val asset: String?)

/** Something LedgerFlow ships: a library, a font or the icons. */
@Immutable
public data class LicensedComponent(
    val id: String,
    val name: String,
    val version: String?,
    val licences: List<LicenceInfo>,
)

internal object LicenceCatalogue {

    /**
     * The two licences most of the list uses, with their texts bundled
     * (`assets/about/licences/`, from apache.org and SPDX): the build runs
     * offline (ADR-0032), so the plugin carries names and URLs, not texts.
     * Apache's URL is the canonical one; the plugin's is whichever library it
     * met first (it was OpenCV's).
     */
    private val KNOWN: Map<String, LicenceInfo> = mapOf(
        APACHE to LicenceInfo(
            id = APACHE,
            name = "Apache License 2.0",
            url = "https://www.apache.org/licenses/LICENSE-2.0",
            asset = "about/licences/Apache-2.0.txt",
        ),
        BSD3 to LicenceInfo(
            id = BSD3,
            name = "BSD 3-Clause License",
            url = "https://opensource.org/licenses/BSD-3-Clause",
            asset = "about/licences/BSD-3-Clause.txt",
        ),
    )

    /**
     * Not Gradle artifacts, so not in the generated list: the bundled fonts and
     * icons, whose licence files `:core:designsystem` already ships.
     */
    internal val BUNDLED: List<LicensedComponent> = listOf(
        bundled("font-inter", "Inter (font)", "SIL Open Font License 1.1", "licenses/Inter-OFL.txt"),
        bundled("font-plex-mono", "IBM Plex Mono (font)", "SIL Open Font License 1.1", "licenses/IBMPlexMono-OFL.txt"),
        bundled("icons-lucide", "Lucide (icons)", "ISC License", "licenses/Lucide-ISC.txt"),
    )

    /** The generated list plus [BUNDLED], by name. An unreadable or missing list is just [BUNDLED]. */
    fun all(json: String?): List<LicensedComponent> =
        (json?.let { runCatching { parse(it) }.getOrNull() }.orEmpty() + BUNDLED)
            .sortedBy { it.name.lowercase() }

    fun parse(json: String): List<LicensedComponent> {
        val root = JSONObject(json)
        val licences = root.optJSONObject("licenses") ?: JSONObject()
        val libraries = root.optJSONArray("libraries") ?: return emptyList()
        return (0 until libraries.length()).map { i ->
            val library = libraries.getJSONObject(i)
            val ids = library.optJSONArray("licenses")
            LicensedComponent(
                id = library.getString("uniqueId"),
                // A nameless artifact by its artifact id: the group is noise
                // to a reader, and the whole coordinate breaks across lines.
                name = library.optString("name").ifBlank { library.getString("uniqueId").substringAfter(':') },
                version = library.optString("artifactVersion").ifBlank { null },
                licences = (0 until (ids?.length() ?: 0)).map { j ->
                    val id = requireNotNull(ids).getString(j)
                    KNOWN[id] ?: licences.optJSONObject(id).toInfo(id)
                },
            )
        }
    }

    private fun JSONObject?.toInfo(id: String) = LicenceInfo(
        id = id,
        // SQLCipher's arrives with no name; it is still a licence, and its URL says whose.
        name = this?.optString("name").orEmpty().ifBlank { "Licence" },
        url = this?.optString("url").orEmpty().ifBlank { null },
        asset = null,
    )

    private fun bundled(id: String, name: String, licence: String, asset: String) = LicensedComponent(
        id = id,
        name = name,
        version = null,
        licences = listOf(LicenceInfo(id = "$id-licence", name = licence, url = null, asset = asset)),
    )

    private const val APACHE = "Apache-2.0"
    private const val BSD3 = "BSD-3-Clause"
}

/** "1.13.0 · Apache License 2.0": the row's second line. */
internal fun LicensedComponent.summary(): String =
    listOfNotNull(version, licences.joinToString(", ") { it.name }.ifBlank { null })
        .joinToString(" " + Char(MIDDLE_DOT) + " ")

private const val MIDDLE_DOT = 0x00B7
