package tachiyomi.domain.manga.interactor

import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

class FetchInterval(
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun toMangaUpdate(
        manga: Manga,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): MangaUpdate {
        // Infer release cadence from ALL chapters, even when the owner
        // currently filters out read/bookmarked/downloaded chapters.
        val chapters = if (manga.fetchInterval < 0) {
            emptyList()
        } else {
            getChaptersByMangaId.await(manga.id, applyFilter = false)
        }
        val interval = manga.fetchInterval.takeIf { it < 0 } ?: calculateInterval(
            chapters = chapters,
            zone = dateTime.zone,
        )
        val currentWindow = if (window.first == 0L && window.second == 0L) {
            getWindow(ZonedDateTime.now())
        } else {
            window
        }
        val nextUpdate = calculateNextUpdate(manga, interval, dateTime, currentWindow, chapters)

        return MangaUpdate(id = manga.id, nextUpdate = nextUpdate, fetchInterval = interval)
    }

    fun getWindow(dateTime: ZonedDateTime): Pair<Long, Long> {
        val today = dateTime.toLocalDate().atStartOfDay(dateTime.zone)
        val lowerBound = today.minusDays(GRACE_PERIOD)
        val upperBound = today.plusDays(GRACE_PERIOD)
        return Pair(lowerBound.toEpochSecond() * 1000, upperBound.toEpochSecond() * 1000 - 1)
    }

    internal fun calculateInterval(chapters: List<Chapter>, zone: ZoneId): Int {
        val chapterWindow = if (chapters.size <= 8) 3 else 10

        val nowMillis = System.currentTimeMillis()
        val uploadDates = chapters.asSequence()
            .filter { it.dateUpload > 0L && it.dateUpload <= nowMillis }
            .sortedByDescending { it.dateUpload }
            .map {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.dateUpload), zone)
                    .toLocalDate()
                    .atStartOfDay()
            }
            .distinct()
            .take(chapterWindow)
            .toList()

        // dateFetch records when the user's app saw a chapter, not when a
        // publisher released it. Using it as upload cadence invents dates.
        val interval = if (uploadDates.size >= 3) {
            val gaps = uploadDates.windowed(2)
                .map { x -> x[1].until(x[0], ChronoUnit.DAYS) }
                .sorted()
            gaps[(gaps.size - 1) / 2].toInt()
        } else {
            // Unknown cadence: conservative source check, not a release date.
            7
        }

        return interval.coerceIn(1, MAX_INTERVAL)
    }

    private fun calculateNextUpdate(
        manga: Manga,
        interval: Int,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
        chapters: List<Chapter>,
    ): Long {
        if (manga.nextUpdate in window.first.rangeTo(window.second + 1)) {
            return manga.nextUpdate
        }

        // For a reliable cadence, anchor the next check to the source's
        // actual chapter upload day. Fetch time is not the release day.
        if (interval > 0) {
            expectedNextCheckFromUploads(chapters, interval, dateTime)?.let { return it }
        }

        val latestDate = ZonedDateTime.ofInstant(
            if (manga.lastUpdate > 0) Instant.ofEpochMilli(manga.lastUpdate) else dateTime.toInstant(),
            dateTime.zone,
        )
            .toLocalDate()
            .atStartOfDay()
        val timeSinceLatest = ChronoUnit.DAYS.between(latestDate, dateTime).toInt()
        val cycle = timeSinceLatest.floorDiv(
            interval.absoluteValue.takeIf { interval < 0 }
                ?: increaseInterval(interval, timeSinceLatest, increaseWhenOver = 10),
        )
        return latestDate.plusDays((cycle + 1) * interval.absoluteValue.toLong()).toEpochSecond(dateTime.offset) * 1000
    }

    /**
     * Uses source-provided upload days, not the date the client fetched/read
     * a chapter. Three upload days establish a minimum repeatable pattern.
     * If upload dates are stale/missing/future-dated, let normal fallback
     * check scheduling run instead of inventing a publisher date.
     */
    internal fun expectedNextCheckFromUploads(
        chapters: List<Chapter>,
        intervalDays: Int,
        now: ZonedDateTime,
    ): Long? {
        if (intervalDays <= 0) return null
        val today = now.toLocalDate()
        val releaseDays = chapters.asSequence()
            .map { it.dateUpload }
            .filter { it > 0L && it <= now.toInstant().toEpochMilli() }
            .map { Instant.ofEpochMilli(it).atZone(now.zone).toLocalDate() }
            .filter { !it.isAfter(today) }
            .distinct()
            .sortedDescending()
            .take(10)
            .toList()
        if (releaseDays.size < 3) return null
        val latest = releaseDays.first()
        val elapsed = ChronoUnit.DAYS.between(latest, today)
        if (elapsed < 0L || elapsed > 90L) return null
        val next = latest.plusDays((elapsed / intervalDays + 1L) * intervalDays)
        return next.atStartOfDay(now.zone).toInstant().toEpochMilli()
    }

    private fun increaseInterval(delta: Int, timeSinceLatest: Int, increaseWhenOver: Int): Int {
        if (delta >= MAX_INTERVAL) return MAX_INTERVAL

        // double delta again if missed more than 9 check in new delta
        val cycle = timeSinceLatest.floorDiv(delta) + 1
        return if (cycle > increaseWhenOver) {
            increaseInterval(delta * 2, timeSinceLatest, increaseWhenOver)
        } else {
            delta
        }
    }

    companion object {
        const val MAX_INTERVAL = 28

        private const val GRACE_PERIOD = 1L

        // KMK -->
        const val MANUAL_DISABLE = 99999 // 274 years in future
        // KMK <--
    }
}
