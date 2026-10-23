package eu.kanade.presentation.library.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryShelfTest {
    @Test
    fun progressShelvesPartitionReadableTitles() {
        val shelves = LibraryShelf.entries.filter { it != LibraryShelf.ALL }
        val cases = listOf(
            Triple(0L, 12L, LibraryShelf.NOT_STARTED),
            Triple(4L, 12L, LibraryShelf.READING),
            Triple(12L, 12L, LibraryShelf.CAUGHT_UP),
        )
        for ((read, total, expected) in cases) {
            assertEquals(listOf(expected), shelves.filter { it.matches(read, total) })
            assertTrue(LibraryShelf.ALL.matches(read, total))
        }
    }

    @Test
    fun emptyChapterRecordsStayInAllTitles() {
        for (shelf in LibraryShelf.entries.filter { it != LibraryShelf.ALL }) {
            assertFalse(shelf.matches(0, 0))
        }
        assertTrue(LibraryShelf.ALL.matches(0, 0))
    }

    @Test
    fun readingProgressMovesTitlesBetweenShelves() {
        assertTrue(LibraryShelf.NOT_STARTED.matches(0, 8))
        assertTrue(LibraryShelf.READING.matches(1, 8))
        assertTrue(LibraryShelf.CAUGHT_UP.matches(8, 8))
        assertFalse(LibraryShelf.READING.matches(8, 8))
    }
}
