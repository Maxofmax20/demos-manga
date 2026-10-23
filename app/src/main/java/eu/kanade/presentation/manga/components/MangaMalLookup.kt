package eu.kanade.presentation.manga.components

// KMK -->
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Best-effort public MyAnimeList reference via Jikan.
 * Never modifies the user's own title, chapters or read/completed state.
 * Only exact titles/approved aliases may override the displayed *publication*
 * label or supply a missing source cover. Missing and uncertain matches are ignored.
 */
internal object MangaMalLookup {
    data class Entry(val title: String, val status: String, val coverUrl: String?)
    private data class Cached(val time: Long, val entry: Entry?)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    internal fun normalizedTitle(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")
            .trim()

    internal fun matchResponse(requested: String, json: JSONObject): Entry? {
        val query = normalizedTitle(requested)
        if (query.length < 3) return null
        val matches = json.optJSONArray("data") ?: return null
        for (index in 0 until matches.length()) {
            val item = matches.optJSONObject(index) ?: continue
            val aliases = mutableListOf(
                item.optString("title"),
                item.optString("title_english"),
                item.optString("title_japanese"),
            )
            val otherNames = item.optJSONArray("titles")
            if (otherNames != null) {
                for (n in 0 until otherNames.length()) {
                    aliases += otherNames.optJSONObject(n)?.optString("title").orEmpty()
                }
            }
            if (aliases.none { normalizedTitle(it) == query }) continue
            val status = item.optString("status")
            if (status !in setOf("Publishing", "Finished", "On Hiatus", "Discontinued", "Not yet published")) {
                continue
            }
            val jpg = item.optJSONObject("images")?.optJSONObject("jpg")
            val webp = item.optJSONObject("images")?.optJSONObject("webp")
            val potential = jpg?.optString("large_image_url").orEmpty()
                .ifBlank { jpg?.optString("image_url").orEmpty() }
                .ifBlank { webp?.optString("image_url").orEmpty() }
            val cover = potential.takeIf {
                runCatching {
                    val url = URL(it)
                    url.protocol == "https" && url.host == "cdn.myanimelist.net"
                }.getOrDefault(false)
            }
            return Entry(item.optString("title"), status, cover)
        }
        return null
    }

    suspend fun lookup(title: String): Entry? = withContext(Dispatchers.IO) {
        val key = normalizedTitle(title)
        if (key.length < 3) return@withContext null
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.time < CACHE_MS }?.let { return@withContext it.entry }
        val result = runCatching {
            val query = URLEncoder.encode(title, "UTF-8")
            val connection = (
                URL("https://api.jikan.moe/v4/manga?q=$query&limit=5&sfw=true")
                    .openConnection() as HttpURLConnection
                ).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode != 200) {
                    null
                } else {
                    connection.inputStream.bufferedReader().use {
                        matchResponse(title, JSONObject(it.readText()))
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        cache[key] = Cached(now, result)
        result
    }
}
// KMK <--
