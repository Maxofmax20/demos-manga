package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OlympusStaffUrlFixTest {
    @Test
    fun `apex URLs are rewritten without changing the manga path or query`() {
        val original = "https://olympustaff.com/series/martial-peak?page=2"
        assertEquals(
            "https://www.olympustaff.com/series/martial-peak?page=2",
            OlympusStaffUrlFix.normalize(original),
        )
    }

    @Test
    fun `HTTP apex URLs upgrade to HTTPS`() {
        assertEquals(
            "https://www.olympustaff.com/series/example",
            OlympusStaffUrlFix.normalize("http://olympustaff.com/series/example"),
        )
    }

    @Test
    fun `working www URL remains unchanged`() {
        val url = "https://www.olympustaff.com/series/example"
        assertEquals(url, OlympusStaffUrlFix.normalize(url))
    }

    @Test
    fun `other domains and lookalike domains remain unchanged`() {
        listOf(
            "https://othermanga.com/series/example",
            "https://olympustaff.com.evil.example/series/example",
            "https://sub.olympustaff.com/series/example",
            "not a web URL",
        ).forEach { url ->
            assertEquals(url, OlympusStaffUrlFix.normalize(url))
        }
    }

    @Test
    fun `discord redirects are only flagged for Olympus Staff`() {
        val olympus = "https://www.olympustaff.com/series/example".toHttpUrl()
        val other = "https://othermanga.com/series/example".toHttpUrl()
        val discord = "https://discord.com/invite/example".toHttpUrl()
        assertTrue(OlympusStaffUrlFix.isDiscordRedirect(olympus, discord))
        assertFalse(OlympusStaffUrlFix.isDiscordRedirect(other, discord))
        assertFalse(
            OlympusStaffUrlFix.isDiscordRedirect(
                olympus,
                "https://www.olympustaff.com/series/example".toHttpUrl(),
            ),
        )
    }
}
