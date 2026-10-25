package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.time.toJavaDuration

@Execution(ExecutionMode.CONCURRENT)
class FetchIntervalTest {

    private val testTime = ZonedDateTime.parse("2020-01-01T00:00:00Z")
    private val testZoneId = ZoneOffset.UTC
    private var chapter = Chapter.create().copy(
        dateFetch = testTime.toEpochSecond() * 1000,
        dateUpload = testTime.toEpochSecond() * 1000,
    )

    private val fetchInterval = FetchInterval(mockk())

    @Test
    fun `returns default interval of 7 days when not enough distinct days`() {
        val chaptersWithUploadDate = (1..50).map {
            chapterWithTime(chapter, 1.days)
        }
        fetchInterval.calculateInterval(chaptersWithUploadDate, testZoneId) shouldBe 7

        val chaptersWithoutUploadDate = chaptersWithUploadDate.map {
            it.copy(dateUpload = 0L)
        }
        fetchInterval.calculateInterval(chaptersWithoutUploadDate, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval based on more recent chapters`() {
        val oldChapters = (1..5).map {
            chapterWithTime(chapter, (it * 7).days) // Would have interval of 7 days
        }
        val newChapters = (1..10).map {
            chapterWithTime(chapter, oldChapters.lastUploadDate() + it.days)
        }

        val chapters = oldChapters + newChapters

        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 1
    }

    @Test
    fun `returns interval based on smaller subset of recent chapters if very few chapters`() {
        val oldChapters = (1..3).map {
            chapterWithTime(chapter, (it * 7).days)
        }
        // Significant gap between chapters
        val newChapters = (1..3).map {
            chapterWithTime(chapter, oldChapters.lastUploadDate() + 365.days + (it * 7).days)
        }

        val chapters = oldChapters + newChapters

        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval of 7 days when multiple chapters in 1 day`() {
        val chapters = (1..10).map {
            chapterWithTime(chapter, 10.hours)
        }
        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval of 7 days when multiple chapters in 2 days`() {
        val chapters = (1..2).map {
            chapterWithTime(chapter, 1.days)
        } + (1..5).map {
            chapterWithTime(chapter, 2.days)
        }
        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval of 1 day when chapters are released every 1 day`() {
        val chapters = (1..20).map {
            chapterWithTime(chapter, it.days)
        }
        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 1
    }

    @Test
    fun `returns interval of 1 day when delta is less than 1 day`() {
        val chapters = (1..20).map {
            chapterWithTime(chapter, (15 * it).hours)
        }
        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 1
    }

    @Test
    fun `returns interval of 2 days when chapters are released every 2 days`() {
        val chapters = (1..20).map {
            chapterWithTime(chapter, (2 * it).days)
        }
        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 2
    }

    @Test
    fun `returns interval with floored value when interval is decimal`() {
        val chaptersWithUploadDate = (1..5).map {
            chapterWithTime(chapter, (25 * it).hours)
        }
        fetchInterval.calculateInterval(chaptersWithUploadDate, testZoneId) shouldBe 1

        val chaptersWithoutUploadDate = chaptersWithUploadDate.map {
            it.copy(dateUpload = 0L)
        }
        // Fetch dates are not publisher dates. Use default source cadence.
        fetchInterval.calculateInterval(chaptersWithoutUploadDate, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval of 2 days when chapters are released just below every 2 days`() {
        val chapters = (1..20).map {
            chapterWithTime(chapter, (43 * it).hours)
        }
        fetchInterval.calculateInterval(chapters, testZoneId) shouldBe 2
    }

    @Test
    fun clientPollingCannotProvePublisherCadence() {
        val clientFetches = (1..5).map { day ->
            chapter.copy(dateUpload = 0L, dateFetch = testTime.plusDays(day * 2L).toEpochSecond() * 1000)
        }
        fetchInterval.calculateInterval(clientFetches, testZoneId) shouldBe 7
    }

    @Test
    fun weeklyReleaseAnchorsChecksToPublisherWeekday() {
        val latest = ZonedDateTime.parse("2026-10-02T12:00:00Z")
        val dates = (0..4).map { week ->
            chapter.copy(dateUpload = latest.minusDays(week * 7L).toInstant().toEpochMilli())
        }
        val saturday = ZonedDateTime.parse("2026-10-03T10:00:00Z")
        fetchInterval.calculateInterval(dates, testZoneId) shouldBe 7
        fetchInterval.expectedNextCheckFromUploads(dates, 7, saturday) shouldBe
            ZonedDateTime.parse("2026-10-09T00:00:00Z").toInstant().toEpochMilli()
    }

    @Test
    fun dailyAndTwoDaySeriesKeepDistinctReleaseChecks() {
        val now = ZonedDateTime.parse("2026-10-10T13:00:00Z")
        val latest = ZonedDateTime.parse("2026-10-10T08:00:00Z")
        val daily = (0..4).map { d ->
            chapter.copy(dateUpload = latest.minusDays(d.toLong()).toInstant().toEpochMilli())
        }
        val alternate = (0..4).map { d ->
            chapter.copy(dateUpload = latest.minusDays(d * 2L).toInstant().toEpochMilli())
        }
        fetchInterval.calculateInterval(daily, testZoneId) shouldBe 1
        fetchInterval.calculateInterval(alternate, testZoneId) shouldBe 2
        fetchInterval.expectedNextCheckFromUploads(daily, 1, now) shouldBe
            ZonedDateTime.parse("2026-10-11T00:00:00Z").toInstant().toEpochMilli()
        fetchInterval.expectedNextCheckFromUploads(alternate, 2, now) shouldBe
            ZonedDateTime.parse("2026-10-12T00:00:00Z").toInstant().toEpochMilli()
    }

    @Test
    fun onlyFutureDatedChaptersCannotDetermineReleaseCadence() {
        val future = ZonedDateTime.now(testZoneId).plusDays(10)
        val impossible = (1..5).map { idx ->
            chapter.copy(dateUpload = future.plusDays(idx * 2L).toInstant().toEpochMilli())
        }
        fetchInterval.calculateInterval(impossible, testZoneId) shouldBe 7
    }

    @Test
    fun missingStaleFutureDatesNeverBecomePublisherForecasts() {
        val now = ZonedDateTime.parse("2026-10-10T13:00:00Z")
        val old = ZonedDateTime.parse("2025-01-10T08:00:00Z")
        val stale = (0..3).map { d ->
            chapter.copy(dateUpload = old.minusDays(d * 7L).toInstant().toEpochMilli())
        }
        val fetched = (0..3).map { d ->
            chapter.copy(dateUpload = 0L, dateFetch = now.minusDays(d * 7L).toInstant().toEpochMilli())
        }
        val future = (0..3).map { d ->
            chapter.copy(dateUpload = now.plusDays((d + 1L) * 7L).toInstant().toEpochMilli())
        }
        fetchInterval.expectedNextCheckFromUploads(stale, 7, now) shouldBe null
        fetchInterval.expectedNextCheckFromUploads(fetched, 7, now) shouldBe null
        fetchInterval.expectedNextCheckFromUploads(future, 7, now) shouldBe null
    }

    private fun chapterWithTime(chapter: Chapter, duration: Duration): Chapter {
        val newTime = testTime.plus(duration.toJavaDuration()).toEpochSecond() * 1000
        return chapter.copy(dateFetch = newTime, dateUpload = newTime)
    }

    // chapterWithTime() adds to testTime, so this fixture must return a
    // duration relative to testTime, not absolute epoch milliseconds.
    // The old helper accidentally created year-2070 "release" timestamps.
    private fun List<Chapter>.lastUploadDate() =
        (last().dateUpload - testTime.toEpochSecond() * 1000)
            .toDuration(DurationUnit.MILLISECONDS)
}
