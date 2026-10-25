package com.ledgerflow.core.data.ingest

import android.content.Context
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.domain.ingest.AllowlistedApp
import com.ledgerflow.core.domain.ingest.PackageAllowlistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The allowlist editor's view of `package_allowlist` (D-10, V2-PLAN §3.4).
 *
 * **No schema change.** The table already has `enabled` (D-10 made the list
 * user-editable from the start; the seeder inserts only what is missing, so a
 * disabled package stays disabled across updates). "Built in" is not stored:
 * it is membership of the curated seed file this build ships, so it cannot
 * drift from what the seeder writes.
 *
 * The gate itself (`PackageAllowlistDao.isAllowed`, read before any body) is
 * untouched: it already answers from `enabled`.
 */
@Singleton
public class DefaultPackageAllowlistRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: VaultSession,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PackageAllowlistRepository {

    private val builtIns: Set<String> by lazy {
        runCatching {
            context.assets.open(PACKAGE_SEED).use { stream ->
                JSON.decodeFromString<SeedFile>(stream.readBytes().decodeToString())
                    .packages.map { it.packageName }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<List<AllowlistedApp>> =
        session.whenUnlocked().flatMapLatest { database ->
            val dao = database?.packageAllowlistDao() ?: return@flatMapLatest flowOf(emptyList())
            dao.observeAll().map { rows -> rows.map { it.toApp() }.sortedForEditor() }
        }

    override suspend fun setEnabled(packageName: String, enabled: Boolean): Unit = withContext(io) {
        val dao = session.openForBackgroundWork()?.packageAllowlistDao() ?: return@withContext
        val row = dao.all().firstOrNull { it.packageName == packageName } ?: return@withContext
        dao.upsert(listOf(row.copy(enabled = enabled)))
    }

    override suspend fun add(packageName: String, label: String): Unit = withContext(io) {
        val dao = session.openForBackgroundWork()?.packageAllowlistDao() ?: return@withContext
        // IGNORE on conflict: re-adding a listed app changes nothing, and in
        // particular does not switch back on a built-in the user turned off.
        dao.insertMissing(listOf(PackageAllowlistEntity(packageName, label, enabled = true)))
    }

    override suspend fun remove(packageName: String): Boolean = withContext(io) {
        if (packageName in builtIns) return@withContext false
        val dao = session.openForBackgroundWork()?.packageAllowlistDao() ?: return@withContext false
        dao.delete(packageName) > 0
    }

    private fun PackageAllowlistEntity.toApp() = AllowlistedApp(
        packageName = packageName,
        label = label,
        enabled = enabled,
        builtIn = packageName in builtIns,
    )

    @Serializable
    private data class SeedFile(val version: Int, val packages: List<Seed>)

    @Serializable
    private data class Seed(val packageName: String, val label: String? = null)

    private companion object {
        const val PACKAGE_SEED = "ingest/package_allowlist.json"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Built-ins first, then by name (an unnamed one by its package). */
internal fun List<AllowlistedApp>.sortedForEditor(): List<AllowlistedApp> =
    sortedWith(compareBy<AllowlistedApp>({ !it.builtIn }, { (it.label ?: it.packageName).lowercase() }))
