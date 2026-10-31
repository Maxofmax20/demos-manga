package eu.kanade.tachiyomi.ui.smart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceHealthDiagnosticsTest {
    @Test
    fun `site redirects are not described as healthy`() {
        assertEquals("Unexpected redirect", classifySourceFailure("Olympus Staff redirected to Discord"))
        assertEquals("Unexpected redirect", classifySourceFailure("HTTP redirect"))
    }

    @Test
    fun `homepage checks never confuse HTTP success with working chapters`() {
        assertEquals("Homepage responds (HTTP 200); chapters not verified", classifyHomepageProbe(200, null))
        assertEquals(
            "Redirects to Discord: site may be unavailable",
            classifyHomepageProbe(302, "https://discord.gg/example"),
        )
        assertEquals(
            "Homepage redirects (HTTP 301); chapter access unverified",
            classifyHomepageProbe(301, "https://www.example.org"),
        )
        assertEquals("Homepage access blocked (HTTP 403)", classifyHomepageProbe(403, null))
        assertEquals("Homepage not found (HTTP 404)", classifyHomepageProbe(404, null))
    }

    @Test
    fun `known error classes have useful labels`() {
        assertEquals("Access blocked", classifySourceFailure("Cloudflare captcha"))
        assertEquals("Connection timeout", classifySourceFailure("Connection timed out"))
        assertEquals("Extension missing", classifySourceFailure("Source not installed"))
        assertEquals("Page not found", classifySourceFailure("HTTP 404"))
        assertEquals("Update failed", classifySourceFailure("Unexpected parser response"))
    }
}
