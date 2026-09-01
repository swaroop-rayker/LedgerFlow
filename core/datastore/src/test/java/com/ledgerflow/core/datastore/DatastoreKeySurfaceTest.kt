package com.ledgerflow.core.datastore

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ADR-0020's line, enforced mechanically.
 *
 * > `:core:datastore` holds operational metadata about the app's own machinery.
 * > It never holds financial data, message content, or anything derived from
 * > either.
 *
 * This is the only persistent store in the app that is **not encrypted**, so
 * "nothing sensitive lives here" is a promise with no cryptography behind it. A
 * promise like that decays the moment it is only a comment — and it decays
 * quietly, because a key added to a preferences file breaks nothing and shows up
 * in no diff a reviewer is looking hard at.
 *
 * **Source scanning rather than reflection, and rather than reading a list the
 * production code exposes.** A guard whose only reference is the thing it guards
 * is a restatement (§16 Q13 records the same lesson about
 * `ExportCoversEveryTableTest`): if this compared against a `keys` list declared
 * beside the keys, someone adding a key would add it to that list in the same
 * keystroke and the check would never fire. The permitted names are written out
 * *here*, by hand, so adding a key means coming to this file — and to the ADR it
 * names.
 */
class DatastoreKeySurfaceTest {

    /**
     * Every preference key this module is permitted to declare, and the ADR
     * section that permits it.
     *
     * Adding a name here is not a formality. ADR-0020's "What would make us
     * revisit this" makes user-meaningful data a reason to **reopen** that
     * decision rather than to widen this list, and the fourth entry below has a
     * paragraph in the ADR explaining why it is not one.
     */
    private val permittedKeys = setOf(
        "listener_last_connected_at",
        "listener_last_disconnected_at",
        "listener_grant_observed_at",
        "notification_setup_seen",
    )

    private val moduleSources: List<File> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")

        File(root, "core/datastore/src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    /**
     * Guards the guard.
     *
     * A path that stops resolving — a module move, a source-set rename — would
     * make every assertion below vacuously true over an empty list, which is the
     * failure mode that makes a guard worse than none.
     */
    @Test
    fun moduleSources_areDiscoverable() {
        assertThat(moduleSources).isNotEmpty()
        assertThat(moduleSources.map { it.name }).contains("NotificationDataStore.kt")
    }

    @Test
    fun everyDeclaredKey_isPermittedByAdr0020() {
        assertThat(declaredKeys()).containsExactlyElementsIn(permittedKeys)
    }

    /**
     * The other direction: a key on the permitted list that no longer exists.
     *
     * Not pedantry. A stale entry is a slot a future key can be dropped into
     * without anyone revisiting the ADR — the list would already permit a name
     * nobody is currently thinking about.
     */
    @Test
    fun everyPermittedKey_isActuallyDeclared() {
        assertThat(permittedKeys).containsExactlyElementsIn(declaredKeys())
    }

    /**
     * Every `*PreferencesKey("...")` declaration in the module, by its wire name.
     *
     * The regex matches the whole `androidx.datastore.preferences.core` family —
     * `stringPreferencesKey`, `intPreferencesKey`, `byteArrayPreferencesKey` and
     * the rest — rather than only the two types currently in use, because the
     * interesting event is a key of *any* type appearing. A guard scoped to the
     * types already present would be silent on exactly the change most likely to
     * carry something sensitive: a `stringPreferencesKey`.
     */
    private fun declaredKeys(): Set<String> {
        val declaration = Regex("""\b\w+PreferencesKey\s*\(\s*"([^"]+)"\s*\)""")
        return moduleSources
            .flatMap { file -> declaration.findAll(file.readText()).toList() }
            .map { it.groupValues[1] }
            .toSet()
    }
}
