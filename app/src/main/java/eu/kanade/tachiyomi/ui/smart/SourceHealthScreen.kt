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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorScreen
import eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * Present last-known library update failures by source. No live reachability
 * assertions: a source with zero recorded failures is not necessarily online.
 */
object SourceHealthScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
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
                            "Based on your last library updates. This is not a live website check; retry only when you are ready.",
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
