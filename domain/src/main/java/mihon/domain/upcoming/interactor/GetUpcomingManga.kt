package mihon.domain.upcoming.interactor

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.library.model.LibraryManga
import java.time.ZonedDateTime
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZoneId

class GetUpcomingManga(
    private val mangaRepository: MangaRepository,
) {
    // KMK -->
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    // KMK <--

    private val includedStatuses = setOf(
        SManga.ONGOING.toLong(),
        SManga.PUBLISHING_FINISHED.toLong(),
    )

    suspend fun subscribe(): Flow<List<Manga>> {
        return mangaRepository.getUpcomingManga(includedStatuses)
    }

    // KMK -->
    /**
     * The eligible-to-update list follows source progress/category changes.
     * The former one-off snapshot went stale after background updates.
     */
    fun subscribeUpdatingMangas(): Flow<List<Manga>> =
        getLibraryManga.subscribe().map(::filterUpdatingMangas)

    suspend fun updatingMangas(): List<Manga> =
        filterUpdatingMangas(getLibraryManga.await())

    private fun filterUpdatingMangas(libraryManga: List<LibraryManga>): List<Manga> {
        val includedCategories = libraryPreferences.updateCategories().get().map { it.toLong() }.toSet()
        val excludedCategories = libraryPreferences.updateCategoriesExclude().get().map { it.toLong() }.toSet()
        val restrictions = libraryPreferences.autoUpdateMangaRestrictions().get()
        val upperBound = ZonedDateTime.now().toLocalDate()
            .plusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

        return libraryManga.asSequence()
            .filter {
                (includedCategories.isEmpty() || it.categories.any(includedCategories::contains)) &&
                    it.categories.none(excludedCategories::contains)
            }
            .distinctBy { it.manga.id }
            .filter {
                when {
                    it.manga.updateStrategy != UpdateStrategy.ALWAYS_UPDATE -> false
                    MANGA_NON_COMPLETED in restrictions && it.manga.status.toInt() == SManga.COMPLETED -> false
                    MANGA_HAS_UNREAD in restrictions && it.unreadCount != 0L -> false
                    MANGA_NON_READ in restrictions && it.totalChapters > 0L && !it.hasStarted -> false
                    // Match LibraryUpdateJob: an overdue title stays eligible.
                    MANGA_OUTSIDE_RELEASE_PERIOD in restrictions &&
                        !UpcomingSchedulePolicy.isEligibleWithinWindow(it.manga.nextUpdate, upperBound) -> false
                    else -> true
                }
            }
            .map { it.manga }
            .sortedWith(compareBy<Manga> { it.nextUpdate > upperBound }.thenBy { it.nextUpdate })
            .toList()
    }
    // KMK <--

}
