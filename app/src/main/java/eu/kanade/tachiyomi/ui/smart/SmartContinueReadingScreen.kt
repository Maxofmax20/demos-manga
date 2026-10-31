package eu.kanade.tachiyomi.ui.smart

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.SourceIconBadge
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
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.plus
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The same cover grid as Library, ordered by the last genuine reading event.
 * Cover taps open details, while the play button resumes the next unread chapter.
 * Reader history, chapter read flags, and existing library settings are unchanged.
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
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val library by libraryFlow.collectAsState(initial = emptyList<LibraryManga>())
        var showFinished by remember { mutableStateOf(false) }

        val recent = remember(library, showFinished) {
            library.filter { entry ->
                shouldShowInContinueReading(entry.lastRead, entry.readCount, entry.unreadCount, showFinished)
            }.sortedWith(compareByDescending<LibraryManga> { it.lastRead }.thenBy { it.manga.title })
        }

        val openDetails: (Long) -> Unit = { mangaId -> navigator.push(MangaScreen(mangaId)) }
        val resume: (Long) -> Unit = { mangaId ->
            scope.launch {
                val next = withContext(Dispatchers.IO) {
                    val recentChapterId = getHistory.await(mangaId)
                        .filter { it.readAt != null }
                        .maxByOrNull { it.readAt?.time ?: 0L }
                        ?.chapterId
                    val chapters = getNextChapters.await(mangaId, onlyUnread = false)
                    findNextChapterToRead(chapters, recentChapterId)
                }
                if (next != null) {
                    context.startActivity(ReaderActivity.newIntent(context, mangaId, next.id))
                } else {
                    context.toast("No unread chapter found")
                }
            }
        }

        Scaffold(topBar = { AppBar(title = "Continue Reading", navigateUp = navigator::pop) }) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Recently read", style = MaterialTheme.typography.titleMedium)
                            Text("${recent.size} manga · newest first", style = MaterialTheme.typography.labelMedium)
                        }
                        Column {
                            Text("Show completed", style = MaterialTheme.typography.labelSmall)
                            Switch(checked = showFinished, onCheckedChange = { showFinished = it })
                        }
                    }
                }
                if (recent.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "No recently read manga found. Start reading a chapter to see its cover here.",
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else {
                    items(recent, key = { it.id }) { entry ->
                        val manga = entry.manga
                        val source = remember(manga.source) { sourceManager.getOrStub(manga.source) }
                        Column {
                            MangaCompactGridItem(
                                title = manga.title,
                                coverData = MangaCover(
                                    mangaId = manga.id,
                                    sourceId = manga.source,
                                    isMangaFavorite = manga.favorite,
                                    ogUrl = manga.thumbnailUrl,
                                    lastModified = manga.coverLastModified,
                                ),
                                coverBadgeStart = {
                                    if (entry.unreadCount > 0L) {
                                        Badge(text = entry.unreadCount.toString())
                                    }
                                },
                                coverBadgeEnd = {
                                    LanguageBadge(isLocal = source.isLocal(), sourceLanguage = source.lang)
                                    SourceIconBadge(source = source)
                                },
                                onClick = { openDetails(entry.id) },
                                onLongClick = { openDetails(entry.id) },
                                onClickContinueReading = if (entry.unreadCount > 0L) {
                                    { resume(entry.id) }
                                } else {
                                    null
                                },
                            )
                            Text(
                                text = "${entry.readCount}/${entry.totalChapters} · " +
                                    DateUtils.getRelativeTimeSpanString(
                                        entry.lastRead,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Only real reading activity qualifies; completed titles are optional. */
internal fun shouldShowInContinueReading(lastRead: Long, readCount: Long, unreadCount: Long, showFinished: Boolean) =
    lastRead > 0L && readCount > 0L && (showFinished || unreadCount > 0L)

/** Resume at the latest chapter if unfinished, otherwise the next unread one. */
internal fun findNextChapterToRead(chapters: List<Chapter>, lastChapterId: Long?): Chapter? {
    val index = chapters.indexOfFirst { it.id == lastChapterId }
    if (index >= 0) {
        chapters.drop(index).firstOrNull { !it.read }?.let { return it }
    }
    return chapters.firstOrNull { !it.read }
}
