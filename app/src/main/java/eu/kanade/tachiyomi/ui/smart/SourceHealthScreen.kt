package eu.kanade.tachiyomi.ui.smart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorScreen
import eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.feature.migration.config.MigrationConfigScreen
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keep historical library failures visible and offer explicit, bounded homepage
 * probes for affected sources. A responding homepage does not certify chapters.
 */
object SourceHealthScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val probeResults = remember { mutableStateMapOf<Long, String>() }
        val probing = remember { mutableStateMapOf<Long, Boolean>() }
        val screenModel = rememberScreenModel { LibraryUpdateErrorScreenModel() }
        val state by screenModel.state.collectAsState()
        val groups = remember(state.items) {
            state.items.groupBy { it.error.mangaSource }
                .values
                .sortedWith(
                    compareByDescending<List<eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorItem>> {
                        it.size
                    }.thenBy { it.first().sourceName },
                )
        }
        val messages = state.messages.associateBy { it.id }

        Scaffold(topBar = { AppBar(title = "Source Repair Center", navigateUp = navigator::pop) }) { padding ->
            LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Source diagnostics", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (state.isLoading) {
                                "Loading recorded source failures…"
                            } else {
                                groups.size.toString() + " sources with recorded failures • " +
                                    state.items.size + " affected manga"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Historical failures from library updates. Check a source homepage manually to test its current response; this cannot verify individual chapters.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { navigator.push(LibraryUpdateErrorScreen()) }) {
                            Text("Open detailed update-error log")
                        }
                    }
                }
                if (!state.isLoading && groups.isEmpty()) {
                    item {
                        Text(
                            "No recorded update failures. This does not confirm all sources are online.",
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                items(groups, key = { it.first().error.mangaSource }) { group ->
                    val affected = group.map { it.error.mangaId }.distinct()
                    val examples = group.take(3)
                    val sourceId = group.first().error.mangaSource
                    val source = remember(sourceId) { sourceManager.get(sourceId) as? HttpSource }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text(group.first().sourceName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                affected.size.toString() + " affected manga",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                probeResults[sourceId] ?: "Website not checked this session",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                enabled = source != null && probing[sourceId] != true,
                                onClick = {
                                    val currentSource = source ?: return@OutlinedButton
                                    probing[sourceId] = true
                                    probeResults[sourceId] = "Checking homepage..."
                                    scope.launch {
                                        try {
                                            probeResults[sourceId] = withContext(Dispatchers.IO) {
                                                probeSourceHomepage(currentSource)
                                            }
                                        } finally {
                                            probing.remove(sourceId)
                                        }
                                    }
                                },
                            ) { Text(if (probing[sourceId] == true) "Checking..." else "Check website") }
                            if (source == null) {
                                Text("Extension missing or source unavailable; reinstall it before retrying.")
                            }
                            examples.forEach { item ->
                                val message = messages[item.error.messageId]?.message.orEmpty()
                                Text(
                                    classifySourceFailure(message) + ": " + item.error.mangaTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = { navigator.push(MangaScreen(item.error.mangaId)) }) {
                                    Text("Open manga")
                                }
                            }
                            if (affected.size > examples.size) {
                                Text(
                                    "And " + (affected.size - examples.size) + " more in the error log",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val started = LibraryUpdateJob.startNow(context, mangaIds = affected)
                                    context.toast(
                                        if (started) "Update check queued" else "Update already running or unavailable",
                                    )
                                }) { Text("Retry check") }
                                Button(onClick = { navigator.push(MigrationConfigScreen(affected)) }) {
                                    Text("Migration options")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Short diagnostic labels; the full original error remains in the update-error log. */
internal fun classifySourceFailure(message: String): String {
    val m = message.lowercase()
    return when {
        "discord" in m || "redirect" in m -> "Unexpected redirect"
        "cloudflare" in m || "captcha" in m || "403" in m -> "Access blocked"
        "timed out" in m || "timeout" in m || "unable to resolve" in m -> "Connection timeout"
        "not installed" in m || "missing source" in m -> "Extension missing"
        "404" in m || "not found" in m -> "Page not found"
        else -> "Update failed"
    }
}

/**
 * Manual, read-only source-homepage diagnostic. Never auto-poll, run parsers,
 * download chapters or follow redirects to unrelated hosts.
 */
internal fun probeSourceHomepage(source: HttpSource): String {
    val home = source.getHomeUrl().toHttpUrlOrNull()
        ?: return "Source homepage URL is invalid"
    if (home.scheme != "https" && home.scheme != "http") return "Unsupported source URL"
    val client = source.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun execute(head: Boolean): Pair<Int, String?> {
        val request = Request.Builder().url(home).apply {
            if (head) head() else get()
        }.build()
        return client.newCall(request).execute().use { response ->
            response.code to response.header("Location")
        }
    }

    return try {
        var result = execute(head = true)
        if (result.first == 405 || result.first == 501) {
            result = execute(head = false)
        }
        classifyHomepageProbe(result.first, result.second)
    } catch (e: IOException) {
        "Network check failed: " + (e.message?.take(100) ?: "Connection unavailable")
    } catch (e: IllegalArgumentException) {
        "Source URL could not be checked"
    }
}

internal fun classifyHomepageProbe(status: Int, location: String?): String = when {
    status in 300..399 && location.orEmpty().contains("discord", ignoreCase = true) ->
        "Redirects to Discord: site may be unavailable"
    status in 300..399 -> "Homepage redirects (HTTP $status); chapter access unverified"
    status in 200..299 -> "Homepage responds (HTTP $status); chapters not verified"
    status == 401 || status == 403 || status == 429 -> "Homepage access blocked (HTTP $status)"
    status == 404 -> "Homepage not found (HTTP 404)"
    else -> "Homepage HTTP $status; check original error log"
}
