package eu.kanade.tachiyomi.data.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemosAutoCategoryJobTest {
    @Test
    fun onlyReviewableCategoryChangesMayRunWithoutSecondPhoneConfirmation() {
        assertTrue(DemosAutoCategoryJob.allowedAction("category_changes"))
        for (action in listOf(
            "mark_read", "download_chapter", "library_set", "install_extension",
            "uninstall_extension", "open_screen", "set_setting", "backup_restore",
            "delete_category", "revoke_device", "unknown",
        )) {
            assertFalse(DemosAutoCategoryJob.allowedAction(action), "Unexpected unattended action: $action")
        }
    }
}
