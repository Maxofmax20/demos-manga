package eu.kanade.tachiyomi.ui.deeplink

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.sync.service.CinemaLinkIdentity
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Explicit, navigation-only receiver for the user's Cinema Library app.
 *
 * Accepts opaque 24-hex library IDs only. IDs are matched to an existing
 * local manga and chapter on this phone. No external URL, path, read-state
 * mutation, download or hidden backup data is ever consumed.
 */
class CinemaLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KMK -->
        val id = intent?.getStringExtra("manga_id").orEmpty()
        val chapter = intent?.getStringExtra("chapter_id").orEmpty()
        if (!CinemaLinkIdentity.validReference(id) ||
            (chapter.isNotEmpty() && !CinemaLinkIdentity.validReference(chapter))
        ) {
            finish()
            return
        }
        lifecycleScope.launch {
            val found = runCatching {
                withContext(Dispatchers.IO) {
                    val matches = Injekt.get<MangaRepository>().getFavorites()
                        .filter { CinemaLinkIdentity.manga(it.source, it.url) == id }
                    val manga = matches.singleOrNull() ?: return@withContext null
                    val blocked = setOf("adult", "mature", "explicit", "smut", "erotica", "hentai", "ecchi", "nsfw")
                    if (manga.genre.orEmpty().any { it.lowercase() in blocked }) return@withContext null
                    val sources = Injekt.get<ExtensionManager>().installedExtensionsFlow.value
                        .filter { it.isNsfw }.flatMap { it.sources }.map { it.id }.toSet()
                    if (manga.source in sources) return@withContext null
                    val localChapter = if (chapter.isEmpty()) {
                        null
                    } else {
                        Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id)
                            .singleOrNull { CinemaLinkIdentity.chapter(id, it.url) == chapter }
                    }
                    if (chapter.isNotEmpty() && localChapter == null) return@withContext null
                    manga.id to localChapter?.id
                }
            }.getOrNull()
            if (found == null) {
                Toast.makeText(
                    this@CinemaLinkActivity,
                    applicationContext.stringResource(KMR.strings.cinema_exact_manga_missing),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }
            if (chapter.isNotEmpty()) {
                startActivity(ReaderActivity.newIntent(this@CinemaLinkActivity, found.first, found.second))
            } else {
                startActivity(
                    Intent(this@CinemaLinkActivity, MainActivity::class.java).apply {
                        action = Constants.SHORTCUT_MANGA
                        putExtra(Constants.MANGA_EXTRA, found.first)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
            }
            finish()
        }
        // KMK <--
    }
}
