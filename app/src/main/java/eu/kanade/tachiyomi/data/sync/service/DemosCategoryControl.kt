package eu.kanade.tachiyomi.data.sync.service

import android.app.Application
import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

// KMK -->
/** Separate opt-in, metadata-only snapshot. Never uploads backup or account settings. */
object DemosCategoryControl {
    val scopes = listOf(
        "read_library", "organize_library", "chapter_progress", "downloads_updates",
        "sources_extensions", "tracking", "app_preferences", "backups_restores", "diagnostics",
    )
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val client by lazy { OkHttpClient() }

    @Serializable
    data class CategoryRecord(val id: String, val name: String, val order: Long, val hidden: Boolean, val flags: String)

    @Serializable
    data class MangaRecord(val id: String, val title: String, val category_ids: List<String>)

    @Serializable
    data class State(val categories: List<CategoryRecord>, val manga: List<MangaRecord>)

    @Serializable
    data class Snapshot(val revision: String, val scopes: Map<String, Boolean>, val categories: List<CategoryRecord>, val manga: List<MangaRecord>)

    @Serializable
    data class Change(val manga_id: String, val title: String, val before: List<String>, val after: List<String>)

    @Serializable
    data class Proposal(val expected_revision: String, val changes: List<Change>, val category_order: List<String>? = null, val preview_id: String)

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun mangaId(source: Long, url: String) = hash("$source|$url").take(24)
    private fun revision(state: State) = hash(json.encodeToString(state))

    private suspend fun state(): State {
        val categories = Injekt.get<CategoryRepository>()
        val mangas = Injekt.get<MangaRepository>().getFavorites()
        val blockedSources = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
            .filter { it.isNsfw }.flatMap { it.sources }.map { it.id }.toSet()
        return State(
            categories.getAll().sortedBy { it.id }.map {
                CategoryRecord(it.id.toString(), it.name, it.order, it.hidden, it.flags.toString())
            },
            mangas.filter { m ->
                m.source !in blockedSources && m.genre.orEmpty().none {
                    it.lowercase() in setOf("adult", "mature", "explicit", "smut", "hentai", "ecchi", "nsfw", "erotica")
                }
            }.map { m ->
                MangaRecord(
                    mangaId(m.source, m.url),
                    m.title,
                    categories.getCategoriesByMangaId(m.id)
                        .filter { it.id > 0 }.map { it.id.toString() }.distinct().sortedBy { it.toLong() },
                )
            }.sortedBy { it.id },
        )
    }

    suspend fun report(prefs: SyncPreferences) {
        val host = prefs.clientHost().get().trimEnd('/')
        require(host == "https://manga.demos25.me")
        val enabled = scopes.associateWith { prefs.remoteScope(it).get() }
        val state = if (enabled["read_library"] == true) {
            Injekt.get<DatabaseHandler>().await(inTransaction = true) { state() }
        } else {
            State(emptyList(), emptyList())
        }
        val snapshot = Snapshot(revision(state), enabled, state.categories, state.manga)
        val body = json.encodeToString(snapshot).toRequestBody("application/json".toMediaType())
        val headers = Headers.Builder().add("X-API-Token", prefs.clientAPIKey().get()).build()
        client.newCall(POST("$host/api/device/category-snapshot", headers, body)).await().use {
            require(it.isSuccessful) { Injekt.get<Application>().stringResource(KMR.strings.demos_category_sync_failed, it.code) }
        }
    }

    /** Called ONLY by the existing foreground phone approval dialog. */
    suspend fun execute(context: Context, command: DemosRemoteCommands.Command): Boolean {
        val prefs = Injekt.get<SyncPreferences>()
        require(prefs.remoteScope("read_library").get() && prefs.remoteScope("organize_library").get()) {
            context.stringResource(KMR.strings.demos_category_scope_required)
        }
        require(command.expires > System.currentTimeMillis() / 1000) { context.stringResource(KMR.strings.demos_category_expired) }
        val proposal = json.decodeFromString<Proposal>(JsonObject(command.args).toString())
        require(proposal.changes.size <= 500)
        val handler = Injekt.get<DatabaseHandler>()
        val committed = handler.await {
            remote_category_commitsQueries.findCommit(command.command_id).executeAsOneOrNull() != null
        }
        if (committed) {
            runCatching { report(prefs) }
            return true
        }
        // Re-fetch the exact pending command before execution. Scope revocation cancels
        // old proposals on the server, so enabling the scope again cannot revive them.
        val current = DemosRemoteCommands.pending(prefs).singleOrNull { it.command_id == command.command_id }
        require(current != null && current.action == command.action && current.args == command.args) {
            context.stringResource(KMR.strings.demos_category_request_unavailable)
        }
        // Reads, conflict detection, all membership writes and the durable replay journal
        // share a single SQLDelight transaction. An exception rolls everything back.
        handler.await(inTransaction = true) {
            if (remote_category_commitsQueries.findCommit(command.command_id).executeAsOneOrNull() != null) {
                return@await
            }
            require(prefs.remoteScope("read_library").get() && prefs.remoteScope("organize_library").get())
            val before = state()
            require(revision(before) == proposal.expected_revision) { context.stringResource(KMR.strings.demos_category_revision_conflict) }
            val categoryIds = before.categories.filter { it.id != "0" }.map { it.id }.toSet()
            val books = before.manga.associateBy { it.id }
            require(proposal.changes.map { it.manga_id }.distinct().size == proposal.changes.size)
            val repository = Injekt.get<MangaRepository>()
            val localIds = repository.getFavorites().associate { mangaId(it.source, it.url) to it.id }
            proposal.changes.forEach { change ->
                require(books[change.manga_id]?.category_ids == change.before) { context.stringResource(KMR.strings.demos_category_revision_conflict) }
                require(change.after.distinct().size == change.after.size && categoryIds.containsAll(change.after))
                // Use repository directly: the legacy SetMangaCategories interactor swallows
                // errors, which would prevent reliable transaction rollback for bulk edits.
                repository.setMangaCategories(requireNotNull(localIds[change.manga_id]), change.after.map { it.toLong() })
            }
            proposal.category_order?.let { order ->
                require(order.toSet() == categoryIds && order.distinct().size == order.size)
                order.forEachIndexed { index, id ->
                    categoriesQueries.update(null, index.toLong(), null, null, id.toLong())
                }
            }
            require(prefs.remoteScope("organize_library").get()) { context.stringResource(KMR.strings.demos_category_scope_required) }
            val after = state()
            remote_category_commitsQueries.recordCommit(
                command.command_id,
                proposal.preview_id,
                json.encodeToString(before),
                json.encodeToString(after),
                System.currentTimeMillis(),
            )
        }
        // A retry after interrupted reporting finds the journal and cannot execute twice.
        runCatching { report(prefs) }
        return true
    }
}
// KMK <--
