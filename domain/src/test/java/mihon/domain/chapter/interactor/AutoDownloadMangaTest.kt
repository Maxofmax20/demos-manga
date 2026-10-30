package mihon.domain.chapter.interactor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoDownloadMangaTest {
    @Test
    fun `selected library manga downloads even with global downloads off`() {
        assertTrue(shouldAutoDownloadNewChapters(true, true, false, false))
    }

    @Test
    fun `selected manga overrides excluded categories`() {
        assertTrue(shouldAutoDownloadNewChapters(true, true, true, false))
    }

    @Test
    fun `global auto-download still applies to regular included titles`() {
        assertTrue(shouldAutoDownloadNewChapters(true, false, true, true))
    }

    @Test
    fun `regular titles do not download if global setting is off or category excluded`() {
        assertFalse(shouldAutoDownloadNewChapters(true, false, false, true))
        assertFalse(shouldAutoDownloadNewChapters(true, false, true, false))
    }

    @Test
    fun `manga outside the library never auto-download`() {
        assertFalse(shouldAutoDownloadNewChapters(false, true, true, true))
    }
}
