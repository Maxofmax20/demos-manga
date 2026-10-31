package eu.kanade.tachiyomi.ui.smart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ContinueReadingTest {
    private fun chapter(id: Long, read: Boolean) = Chapter.create().copy(id = id, read = read)

    @Test
    fun `continue reading hides untouched and completed titles by default`() {
        assertEquals(false, shouldShowInContinueReading(0, 10, 2, false))
        assertEquals(false, shouldShowInContinueReading(1234, 0, 2, false))
        assertEquals(false, shouldShowInContinueReading(1234, 10, 0, false))
        assertEquals(true, shouldShowInContinueReading(1234, 10, 2, false))
        assertEquals(true, shouldShowInContinueReading(1234, 10, 0, true))
    }

    @Test
    fun `resume skips read chapters after most recently read`() {
        val chapters = listOf(chapter(1, false), chapter(2, true), chapter(3, true), chapter(4, false))
        assertEquals(4L, findNextChapterToRead(chapters, 3)?.id)
    }

    @Test
    fun `resume repeats a partially read chapter`() {
        val chapters = listOf(chapter(1, true), chapter(2, false), chapter(3, false))
        assertEquals(2L, findNextChapterToRead(chapters, 2)?.id)
    }

    @Test
    fun `unknown last chapter falls back and completed titles have no resume`() {
        val chapters = listOf(chapter(1, true), chapter(2, false))
        assertEquals(2L, findNextChapterToRead(chapters, null)?.id)
        assertNull(findNextChapterToRead(listOf(chapter(1, true)), 1))
    }
}
