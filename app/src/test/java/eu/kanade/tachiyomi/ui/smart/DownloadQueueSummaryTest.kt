package eu.kanade.tachiyomi.ui.smart

import eu.kanade.tachiyomi.data.download.model.Download
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadQueueSummaryTest {
    @Test
    fun `queue summary distinguishes downloading failed and waiting chapters`() {
        val states = listOf(Download.State.QUEUE, Download.State.ERROR, Download.State.DOWNLOADING)
        assertEquals(DownloadQueueSummary(3, 1, 1), summarizeDownloadStates(states))
        assertEquals(DownloadQueueSummary(0, 0, 0), summarizeDownloadStates(emptyList()))
    }
}
