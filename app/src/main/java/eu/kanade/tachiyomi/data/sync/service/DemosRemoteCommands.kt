package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import android.content.Intent
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.Constants
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/**
 * KMK: Strictly opt-in, phone-confirmed commands from the private Demos Manga cloud.
 *
 * Nothing is executed by background sync. The owner must open the settings
 * inbox and approve every command while the application is in the foreground.
 * Extension installs still go through Android's installer/OS approval flow.
 */
object DemosRemoteCommands {
    private const val TRUSTED_HOST = "https://manga.demos25.me"
    private val client by lazy { OkHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Command(
        val command_id: String,
        val action: String,
        val args: Map<String, JsonElement>,
        val expires: Long,
    ) {
        fun describe(): String {
            val name = args["package_name"]?.jsonPrimitive?.content ?: ""
            return when (action) {
                "mark_read" -> "Mark selected chapters as ${if (args["read"]?.jsonPrimitive?.boolean == true) "read" else "unread"}?"
                "library_set" -> "${if (args["favorite"]?.jsonPrimitive?.boolean == true) "Add this manga to" else "Remove this manga from"} your library?"
                "refresh_chapters" -> "Refresh chapter updates for the selected manga?"
                "set_setting" -> "Change app setting: ${args["name"]?.jsonPrimitive?.content}?"
                "download_chapter" -> "Download a selected chapter locally?"
                "open_reader" -> "Open a selected chapter on this phone?"
                "library_add" -> "Add an item from a source already installed on this phone?"
                "install_extension" -> "Install extension $name? Android confirmation will also be required."
                "uninstall_extension" -> "Uninstall extension $name? Android confirmation will also be required."
                "open_screen" -> "Open ${args["screen"]?.jsonPrimitive?.content} in Demos Manga?"
                else -> "Unsupported command"
            }
        }
    }

    @Serializable
    private data class ExtensionEntry(
        val package_name: String,
        val name: String,
        val installed: Boolean,
    )

    private suspend fun reportExtensions(host: String, headers: Headers) {
        // A best-effort snapshot obtained only when the owner opens the inbox.
        val manager = Injekt.get<ExtensionManager>()
        val restricted = listOf("nsfw", "adult", "hentai", "erotic", "smut", "porn")
        fun permitted(name: String, pkg: String, isNsfw: Boolean): Boolean {
            return !isNsfw && restricted.none {
                name.contains(it, ignoreCase = true) || pkg.contains(it, ignoreCase = true)
            }
        }
        val extensions = manager.installedExtensionsFlow.value
            .filter { permitted(it.name, it.pkgName, it.isNsfw) }
            .map { ExtensionEntry(it.pkgName, it.name.take(120), true) } +
            manager.availableExtensionsFlow.value
                .filter { permitted(it.name, it.pkgName, it.isNsfw) }
                .map { ExtensionEntry(it.pkgName, it.name.take(120), false) }
        val body = json.encodeToString(ListSerializer(ExtensionEntry.serializer()), extensions.take(400))
            .toRequestBody("application/json".toMediaType())
        client.newCall(
            POST(url = "$host/api/device/extensions", headers = headers, body = body),
        ).await().use { require(it.isSuccessful) { "Extension list sync failed" } }
    }

    private fun credentials(prefs: SyncPreferences): Pair<String, Headers> {
        require(prefs.clientHost().get().trimEnd('/') == TRUSTED_HOST) {
            "The Demos Manga companion requires the official private bridge URL"
        }
        val key = prefs.clientAPIKey().get()
        require(key.length >= 32) { "Pair your Demos Manga device first" }
        return TRUSTED_HOST to Headers.Builder().add("X-API-Token", key).build()
    }

    suspend fun pending(prefs: SyncPreferences): List<Command> {
        val (host, headers) = credentials(prefs)
        runCatching { reportExtensions(host, headers) }
        val request = GET(url = "$host/api/device/commands", headers = headers)
        return client.newCall(request).await().use {
            require(it.isSuccessful) { "Could not check paired cloud commands (HTTP ${it.code})" }
            json.decodeFromString<List<Command>>(it.body.string())
        }
    }

    suspend fun report(prefs: SyncPreferences, commandId: String, state: String) {
        require(state in setOf("completed", "rejected", "failed"))
        require(commandId.matches(Regex("[0-9a-f]{32}")))
        val (host, headers) = credentials(prefs)
        val body = """{"state":"$state"}""".toRequestBody("application/json".toMediaType())
        client.newCall(
            POST(url = "$host/api/device/commands/$commandId/result", headers = headers, body = body),
        ).await().use {
            require(it.isSuccessful) { "Could not update remote command status (HTTP ${it.code})" }
        }
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private suspend fun findManga(id: String): Manga {
        require(id.matches(Regex("[0-9a-f]{24}"))) { "Invalid manga reference" }
        val mangas = Injekt.get<MangaRepository>().getFavorites()
        val matches = mangas.filter { fingerprint("${it.source}|${it.url}") == id }
        require(matches.size == 1) { "Manga is missing or ambiguous in the local library" }
        val manga = matches.single()
        require(
            manga.genre.orEmpty().none {
                it.lowercase() in setOf("adult", "mature", "explicit", "smut", "hentai", "ecchi", "nsfw")
            },
        ) { "This manga is restricted in cloud actions" }
        val extension = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
            .firstOrNull { installed -> installed.sources.any { it.id == manga.source } }
        require(extension?.isNsfw != true) { "Restricted extensions are unavailable" }
        return manga
    }

    suspend fun execute(context: Context, command: Command): Boolean {
        require(command.expires > System.currentTimeMillis() / 1000) { "Command expired" }
        fun string(key: String) = requireNotNull(command.args[key]) { "Missing $key" }.jsonPrimitive.content
        fun boolean(key: String) = requireNotNull(command.args[key]) { "Missing $key" }.jsonPrimitive.boolean
        when (command.action) {
            "mark_read" -> {
                val manga = findManga(string("manga_id"))
                val chapters = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id)
                val targetId = command.args["chapter_id"]?.jsonPrimitive?.content
                val matching = if (targetId == null) {
                    chapters
                } else {
                    chapters.filter { fingerprint("${string("manga_id")}|${it.url}") == targetId }
                }
                require(matching.isNotEmpty()) { "Chapter not found" }
                val result = Injekt.get<SetReadStatus>().await(boolean("read"), *matching.toTypedArray(), manually = false)
                return result !is SetReadStatus.Result.InternalError
            }
            "library_set" -> {
                val manga = findManga(string("manga_id"))
                return Injekt.get<UpdateManga>().awaitUpdateFavorite(manga.id, boolean("favorite"))
            }
            "refresh_chapters" -> {
                val manga = findManga(string("manga_id"))
                return LibraryUpdateJob.startNow(context, mangaIds = listOf(manga.id))
            }
            "library_add" -> {
                // Re-add a title that the phone has already discovered locally.
                // A new source must first be reviewed in the phone's Browse tab.
                val sourceId = string("source_id").toLong()
                val local = Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(
                    string("source_url"),
                    sourceId,
                ) ?: error("First open this title from its installed source in the app")
                require(
                    local.genre.orEmpty().none {
                        it.lowercase() in setOf("adult", "mature", "explicit", "nsfw", "hentai", "ecchi")
                    },
                ) { "This manga is restricted" }
                return Injekt.get<UpdateManga>().awaitUpdateFavorite(local.id, true)
            }
            "download_chapter", "open_reader" -> {
                val mangaId = string("manga_id")
                val manga = findManga(mangaId)
                val chapterId = string("chapter_id")
                val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id)
                    .singleOrNull { fingerprint("$mangaId|${it.url}") == chapterId }
                    ?: error("Chapter was not found in the phone library")
                if (command.action == "download_chapter") {
                    Injekt.get<DownloadManager>().downloadChapters(manga, listOf(chapter))
                } else {
                    context.startActivity(
                        ReaderActivity.newIntent(context, manga.id, chapter.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return true
            }
            "open_screen" -> {
                require(string("screen") in setOf("library", "browse", "extensions", "settings"))
                val target = when (string("screen")) {
                    "browse" -> Constants.SHORTCUT_SOURCES
                    "extensions" -> Constants.SHORTCUT_EXTENSIONS
                    else -> Constants.SHORTCUT_LIBRARY
                }
                context.startActivity(Intent(context, MainActivity::class.java).setAction(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
            "set_setting" -> {
                when (string("name")) {
                    "dark_mode" -> Injekt.get<UiPreferences>().themeMode().set(
                        if (boolean("value")) ThemeMode.DARK else ThemeMode.LIGHT,
                    )
                    "wifi_only_downloads" -> Injekt.get<DownloadPreferences>().downloadOnlyOverWifi().set(boolean("value"))
                    else -> error("That setting is not remotely manageable")
                }
                return true
            }
            "install_extension" -> {
                val manager = Injekt.get<ExtensionManager>()
                val pkg = string("package_name")
                require(pkg.startsWith("eu.kanade.tachiyomi.extension.") && !pkg.contains("nsfw", ignoreCase = true))
                val extension = manager.availableExtensionsFlow.value
                    .filter { !it.isNsfw && it.pkgName == pkg }
                    .singleOrNull() ?: error("Extension unavailable or restricted")
                val installed = withTimeoutOrNull(120_000L) {
                    manager.installExtension(extension).first {
                        it == InstallStep.Installed || it == InstallStep.Error
                    }
                }
                return installed == InstallStep.Installed
            }
            "uninstall_extension" -> {
                val manager = Injekt.get<ExtensionManager>()
                val pkg = string("package_name")
                require(pkg.startsWith("eu.kanade.tachiyomi.extension.") && !pkg.contains("nsfw", ignoreCase = true))
                val extension = manager.installedExtensionsFlow.value
                    .filter { !it.isNsfw && it.pkgName == pkg }
                    .singleOrNull() ?: error("Extension unavailable or restricted")
                manager.uninstallExtension(extension)
                // Android's package uninstaller independently prompts the owner.
                return withTimeoutOrNull(120_000L) {
                    manager.installedExtensionsFlow.first { all -> all.none { it.pkgName == pkg } }
                    true
                } ?: false
            }
            else -> error("This action is not yet supported on the Android client")
        }
    }
}
