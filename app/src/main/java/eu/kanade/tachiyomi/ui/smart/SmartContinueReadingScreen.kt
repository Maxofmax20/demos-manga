package eu.kanade.tachiyomi.ui.smart

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A device-local Continue Reading view, ordered by actual last read timestamp.
 * Unlike random library sorting it excludes untouched and fully read titles
 * by default. It never overwrites read flags or history timestamps.
 */
object SmartContinueReadingScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryFlow = remember { Injekt.get<GetLibraryManga>().subscribe() }
        val getNextChapters = remember { Injekt.get<GetNextChapters>() }
        val getHistory = remember { Injekt.get<GetHistory>() }
        val library by libraryFlow.collectAsState(initial = emptyList<LibraryManga>())
        var showFinished by remember { mutableStateOf(false) }

        val recent = remember(library, showFinished) {
            library.filter { entry ->
                entry.lastRead > 0L &&
                    entry.readCount > 0L &&
                    (showFinished || entry.unreadCount > 0L)
            }.sortedWith(compareByDescending<LibraryManga> { it.lastRead }.thenBy { it.manga.title })
        }
        Scaffold(topBar = { AppBar(title = "Continue Reading", navigateUp = navigator::pop) }) { padding ->
            LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pick up where you left off", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Sorted by your actual recent reading, not random library order. Unread chapters come first.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("Include fully read titles") },
                        supportingContent = { Text("Hidden by default to keep Continue Reading useful") },
                        trailingContent = {
                            Switch(checked = showFinished, onCheckedChange = { showFinished = it })
                        },
                    )
                    HorizontalDivider()
                }
                if (recent.isEmpty()) {
                    item {
                        Text(
                            "No recent unfinished manga found. Your reading activity will appear here after you read a chapter.",
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    items(recent, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(entry.manga.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        entry.readCount.toString() + " / " + entry.totalChapters +
                                            " chapters read • " + entry.unreadCount.coerceAtLeast(0) + " unread",
                                    )
                                    Text(
                                        DateUtils.getRelativeTimeSpanString(
                                            entry.lastRead,
                                            System.currentTimeMillis(),
                                            DateUtils.MINUTE_IN_MILLIS,
                                        ).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            trailingContent = {
                                Column {
                                    Button(
                                        enabled = entry.unreadCount > 0L,
                                        onClick = {
                                            scope.launch {
                                                val next = withContext(Dispatchers.IO) {
                                                    val recentChapterId = getHistory.await(entry.id)
                                                        .filter { it.readAt != null }
                                                        .maxByOrNull { it.readAt?.time ?: 0L }
                                                        ?.chapterId
                                                    val chapters = getNextChapters.await(entry.id, onlyUnread = false)
                                                    findNextChapterToRead(chapters, recentChapterId)
                                                }
                                                if (next != null) {
                                                    context.startActivity(
                                                        ReaderActivity.newIntent(context, entry.id, next.id),
                                                    )
                                                } else {
                                                    context.toast("No unread chapter found")
                                                }
                                            }
                                        },
                                    ) { Text("Read") }
                                    TextButton(onClick = { navigator.push(MangaScreen(entry.id)) }) {
                                        Text("Details")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Resume at the latest chapter if unfinished, otherwise the next unread one. */
internal fun findNextChapterToRead(chapters: List<Chapter>, lastChapterId: Long?): Chapter? {
    val index = chapters.indexOfFirst { it.id == lastChapterId }
    if (index >= 0) {
        chapters.drop(index).firstOrNull { !it.read }?.let { return it }
    }
    return chapters.firstOrNull { !it.read }
}
