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
    fun `known error classes have useful labels`() {
        assertEquals("Access blocked", classifySourceFailure("Cloudflare captcha"))
        assertEquals("Connection timeout", classifySourceFailure("Connection timed out"))
        assertEquals("Extension missing", classifySourceFailure("Source not installed"))
        assertEquals("Page not found", classifySourceFailure("HTTP 404"))
        assertEquals("Update failed", classifySourceFailure("Unexpected parser response"))
    }
}
