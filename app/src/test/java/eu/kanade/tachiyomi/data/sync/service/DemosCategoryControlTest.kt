package eu.kanade.tachiyomi.data.sync.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

// KMK -->
class DemosCategoryControlTest {
    @Test
    fun `Arabic categories and multiple memberships survive metadata round trip`() {
        val data = DemosCategoryControl.State(
            listOf(DemosCategoryControl.CategoryRecord("9", "قيد القراءة ✓", 2, false, "0")),
            listOf(DemosCategoryControl.MangaRecord("a".repeat(24), "عنوان", listOf("7", "9"))),
        )
        assertEquals(data, Json.decodeFromString<DemosCategoryControl.State>(Json.encodeToString(data)))
    }

    @Test
    fun `proposal preserves original and proposed memberships separately`() {
        val change = DemosCategoryControl.Change("a".repeat(24), "عنوان", listOf("7"), listOf("7", "9"))
        val proposal = DemosCategoryControl.Proposal("b".repeat(64), listOf(change), null, "c".repeat(32))
        val decoded = Json.decodeFromString<DemosCategoryControl.Proposal>(Json.encodeToString(proposal))
        assertEquals(listOf("7"), decoded.changes.single().before)
        assertEquals(listOf("7", "9"), decoded.changes.single().after)
        assertFalse(decoded.changes.single().after.contains("0"))
    }
}
// KMK <--
