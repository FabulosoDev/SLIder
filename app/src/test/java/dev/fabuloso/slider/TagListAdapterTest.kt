package dev.fabuloso.slider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagListAdapterTest {
    @Test
    fun `swipe availability does not change rendered row content`() {
        val enabled = row(shareEnabled = true, deleteEnabled = true)
        val disabled = row(shareEnabled = false, deleteEnabled = false)

        assertTrue(enabled.hasSameRenderedContent(disabled))
    }

    @Test
    fun `record and badge changes update rendered row content`() {
        val original = row()
        val changedRecord = original.copy(record = record(readAtEpochMillis = 2L))
        val changedBadge = original.copy(newBadgeStartedAtUptimeMillis = 2L)

        assertFalse(original.hasSameRenderedContent(changedRecord))
        assertFalse(original.hasSameRenderedContent(changedBadge))
    }

    private fun row(
        shareEnabled: Boolean = true,
        deleteEnabled: Boolean = true,
    ) = TagRowItem(
        record = record(readAtEpochMillis = 1L),
        shareEnabled = shareEnabled,
        deleteEnabled = deleteEnabled,
        newBadgeStartedAtUptimeMillis = 1L,
    )

    private fun record(readAtEpochMillis: Long) = TagRecord(
        uid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        blockCount = 1,
        blockSize = 4,
        data = byteArrayOf(1, 2, 3, 4),
        readAtEpochMillis = readAtEpochMillis,
    )
}
