package eu.kanade.tachiyomi.data.sync.service
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// KMK -->
class CinemaLinkIdentityTest {
    @Test fun sourceAndChapterIdsAreBoundToExactUrls() {
        val manga = CinemaLinkIdentity.manga(123L, "/manga/slug")
        assertTrue(CinemaLinkIdentity.validReference(manga))
        assertEquals(manga, CinemaLinkIdentity.manga(123L, "/manga/slug"))
        assertNotEquals(manga, CinemaLinkIdentity.manga(124L, "/manga/slug"))
        assertNotEquals(
            CinemaLinkIdentity.chapter(manga, "/chapter/1"),
            CinemaLinkIdentity.chapter(manga, "/chapter/2"),
        )
        assertFalse(CinemaLinkIdentity.validReference(".."))
        assertFalse(CinemaLinkIdentity.validReference("a".repeat(200)))
    }
}
// KMK <--
