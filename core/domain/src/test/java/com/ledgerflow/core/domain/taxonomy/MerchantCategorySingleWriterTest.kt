package com.ledgerflow.core.domain.taxonomy

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * **Categorising a merchant happens in one place** (V2-PLAN §5.2, owner
 * 2026-10-01): the Merchants section in Organise. Capture, review and the entry
 * form fill the merchant and stop there; nothing infers what kind of place it is.
 *
 * Held structurally, by where the writes can come from:
 *
 * - the two statements that write `merchant.merchant_category_id` are called
 *   only by `DefaultMerchantCategoryRepository`;
 * - the repository's writing methods are called only from `:feature:categories`
 *   (the Organise screen) and the repository's own module.
 *
 * A capture path that wanted to "help" by filing a merchant would have to break
 * one of these, and this test names it.
 */
class MerchantCategorySingleWriterTest {

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    private fun productionSources(): List<File> =
        listOf("core", "feature", "app")
            .map { File(repositoryRoot, it) }
            .filter { it.isDirectory }
            .flatMap { area ->
                area.walkTopDown()
                    .onEnter { it.name != "build" }
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { it.path.replace(File.separatorChar, '/').contains("/src/main/") }
                    .toList()
            }

    private fun File.unixPath() = path.replace(File.separatorChar, '/')

    @Test
    fun productionSources_areDiscoverable() {
        assertThat(productionSources().size).isGreaterThan(MINIMUM_EXPECTED_SOURCES)
    }

    @Test
    fun onlyTheMerchantCategoryRepository_writesAMerchantsCategory() {
        val permitted = setOf("Daos.kt", "DefaultMerchantCategoryRepository.kt")
        val write = Regex("""\b(setMerchantCategory|moveMerchantCategory)\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { write.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /** No SQL outside `:core:database` names the column; the CSV export only titles a column with it. */
    @Test
    fun noOtherModuleNamesTheColumn() {
        val offenders = productionSources()
            .filterNot { it.unixPath().contains("/core/database/") }
            .filterNot { it.name == "CsvTables.kt" }
            .filter { "merchant_category_id" in withoutComments(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /**
     * Capture, review, the entry form and OCR never reach the repository that
     * writes merchant categories, nor a use case over it. The merchant picker's
     * browse by kind (PR 3) reads them through [MerchantCategoryReader], which
     * [theReader_onlyReads] keeps free of writes.
     */
    @Test
    fun captureReviewAndEntry_neverTouchMerchantCategories() {
        val modules = listOf("/feature/ingest/", "/feature/inbox/", "/feature/entry/", "/feature/ocr/")
        val names = Regex("""\b(MerchantCategoryRepository|SetMerchantCategory\w*UseCase)\b""")

        val offenders = productionSources()
            .filter { file -> modules.any { it in file.unixPath() } }
            .filter { names.containsMatchIn(withoutComments(it.readText())) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /**
     * The view the entry form and review screen are given reads and nothing
     * else. A write added to it would reach those screens without any of the
     * name checks above noticing.
     */
    @Test
    fun theReader_onlyReads() {
        val methods = MerchantCategoryReader::class.java.declaredMethods.map { it.name }
        assertThat(methods).containsExactly("observeAll")
    }

    private fun withoutComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//.*"), "")

    private companion object {
        const val MINIMUM_EXPECTED_SOURCES = 100
    }
}
