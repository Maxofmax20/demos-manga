package eu.kanade.tachiyomi.ui.smart

import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.preference.asState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Manage per-title automatic downloads using the phone's actual library and
 * the same persistent preference / native download queue used by the reader.
 */
object SmartDownloadsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        val downloadManager = remember { Injekt.get<DownloadManager>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val libraryFlow = remember { Injekt.get<GetLibraryManga>().subscribe() }

        val library by libraryFlow.collectAsState(initial = emptyList<LibraryManga>())
        val selectedIds by remember(preferences, scope) { preferences.autoDownloadMangaIds().asState(scope) }
        val wifiOnly by remember(preferences, scope) { preferences.downloadOnlyOverWifi().asState(scope) }
        val limit by remember(preferences, scope) { preferences.autoDownloadChapterLimit().asState(scope) }
        val queue by downloadManager.queueState.collectAsState()
        val isRunning by downloadManager.isDownloaderRunning.collectAsState(initial = false)

        var showAll by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var clearConfirmation by remember { mutableStateOf(false) }

        val visible = remember(library, selectedIds, showAll, search, sourceManager) {
            library.asSequence()
                .filter { showAll || it.id.toString() in selectedIds }
                .filter { it.id.toString() in selectedIds || !sourceManager.getOrStub(it.manga.source).isLocalOrStub() }
                .filter { it.manga.title.contains(search, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<LibraryManga> { it.id.toString() in selectedIds }
                        .thenBy { it.manga.title.lowercase() },
                )
                .toList()
        }
        val configured = library.count { it.id.toString() in selectedIds }
        val pending = queue.size
        val freeStorageBytes = remember {
            runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
        }

        Scaffold(
            topBar = { AppBar(title = "Smart Downloads", navigateUp = navigator::pop) },
        ) { contentPadding ->
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Auto-download favorites", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "$configured selected titles • $pending queued chapters",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Only newly discovered chapters are queued. Existing downloads and reading progress stay untouched.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (freeStorageBytes != null) {
                            Text(
                                "Free device storage: " + (freeStorageBytes / (1024L * 1024L)) + " MB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (freeStorageBytes < 1024L * 1024L * 1024L) {
                                Text(
                                    "Storage is running low. Review downloads before starting a large update.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { navigator.push(DownloadQueueScreen) }) {
                            Text(if (isRunning) "View active queue" else "View download queue")
                        }
                        OutlinedButton(
                            onClick = {
                                val ids = library
                                    .filter { it.id.toString() in selectedIds && !sourceManager.getOrStub(it.manga.source).isLocalOrStub() }
                                    .map { it.id }
                                if (ids.isNotEmpty() && LibraryUpdateJob.startNow(context, mangaIds = ids)) {
                                    context.toast("Checking selected manga for new chapters")
                                } else {
                                    context.toast("No selected manga, or an update is already running")
                                }
                            },
                            enabled = configured > 0,
                        ) { Text("Check now") }
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("Download only over Wi-Fi") },
                        supportingContent = { Text("Applies to the existing download queue") },
                        trailingContent = {
                            Switch(
                                checked = wifiOnly,
                                onCheckedChange = { preferences.downloadOnlyOverWifi().set(it) },
                            )
                        },
                    )
                }
                item {
                    val choices = listOf(0, 1, 3, 5, 10)
                    ListItem(
                        headlineContent = { Text("Maximum new chapters per title") },
                        supportingContent = {
                            Text("Limit each update batch, without downloading older chapters")
                        },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    val next = choices[(choices.indexOf(limit).coerceAtLeast(0) + 1) % choices.size]
                                    preferences.autoDownloadChapterLimit().set(next)
                                },
                            ) { Text(if (limit == 0) "Unlimited" else "$limit chapters") }
                        },
                    )
                }
                item {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = { showAll = !showAll }) {
                            Text(if (showAll) "Selected only" else "Add more manga")
                        }
                        TextButton(
                            onClick = { clearConfirmation = true },
                            enabled = selectedIds.isNotEmpty(),
                        ) { Text("Clear selections") }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search your library") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            if (library.isEmpty()) {
                                "Your library is empty or still loading."
                            } else if (showAll) {
                                "No matching titles."
                            } else {
                                "No manga selected. Tap Add more manga to choose titles."
                            },
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    items(visible, key = { it.id }) { entry ->
                        val checked = entry.id.toString() in selectedIds
                        val available = !sourceManager.getOrStub(entry.manga.source).isLocalOrStub()
                        ListItem(
                            headlineContent = {
                                Text(entry.manga.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    if (!available) {
                                        "Source not installed or local: auto-download unavailable"
                                    } else if (checked) {
                                        "Auto-download enabled • " + entry.unreadCount.coerceAtLeast(0) + " unread"
                                    } else {
                                        "Not selected"
                                    },
                                )
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { setSelected(preferences, entry.id, it) },
                                    enabled = available,
                                )
                            },
                            modifier = Modifier.clickable { navigator.push(MangaScreen(entry.id)) },
                            trailingContent = {
                                TextButton(
                                    enabled = available,
                                    onClick = { setSelected(preferences, entry.id, !checked) },
                                ) {
                                    Text(if (checked) "Remove" else "Add")
                                }
                            },
                        )
                    }
                }
            }
        }

        if (clearConfirmation) {
            AlertDialog(
                onDismissRequest = { clearConfirmation = false },
                title = { Text("Stop auto-downloading these manga?") },
                text = {
                    Text("This clears your auto-download selections. It does not delete chapters or cancel the current queue.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        preferences.autoDownloadMangaIds().set(emptySet())
                        clearConfirmation = false
                    }) { Text("Clear selections") }
                },
                dismissButton = {
                    TextButton(onClick = { clearConfirmation = false }) { Text("Cancel") }
                },
            )
        }
    }
}

private fun setSelected(preferences: DownloadPreferences, mangaId: Long, enabled: Boolean) {
    val id = mangaId.toString()
    preferences.autoDownloadMangaIds().getAndSet { existing ->
        if (enabled) existing + id else existing - id
    }
}
