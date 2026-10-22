package dev.fabuloso.slider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class CoreModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `hex helpers format unsigned bytes and reverse uid prefixes`() {
        val bytes = byteArrayOf(0x01, 0xA5.toByte(), 0xFF.toByte())

        assertEquals("01 A5 FF", bytes.toHexSpaced())
        assertEquals("01A5FF", bytes.toHexCompact())
        assertEquals("01:A5:FF", bytes.toHexColonSeparated())
        assertEquals("FFA5", bytes.toReversedHexCompact(byteCount = 2))
    }

    @Test
    fun `tag record owns defensive byte array copies`() {
        val uid = ByteArray(8) { it.toByte() }
        val data = byteArrayOf(1, 2, 3, 4)
        val record = TagRecord(uid, 1, 4, data, 123L)

        uid[0] = 99
        data[0] = 99
        val returnedUid = record.uid
        val returnedData = record.data
        returnedUid[1] = 99
        returnedData[1] = 99

        assertArrayEquals(ByteArray(8) { it.toByte() }, record.uid)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), record.data)
    }

    @Test
    fun `tag record rejects inconsistent geometry`() {
        assertThrows(IllegalArgumentException::class.java) {
            TagRecord(ByteArray(8), 2, 4, ByteArray(4), 0L)
        }
    }

    @Test
    fun `serializer preserves flipper format and validates data length`() {
        val output = FlipperNfcSerializer.serialize(
            uid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            data = byteArrayOf(0x10, 0x11, 0x12, 0x13),
            blockCount = 1,
            blockSize = 4,
        )

        assertTrue(output.contains("Device type: SLIX"))
        assertTrue(output.contains("UID: 01 02 03 04 05 06 07 08"))
        assertTrue(output.contains("Data Content: 10 11 12 13"))
        assertThrows(IllegalArgumentException::class.java) {
            FlipperNfcSerializer.serialize(ByteArray(8), ByteArray(3), 1, 4)
        }
    }

    @Test
    fun `file name uses read time and reversed uid prefix`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val record = TagRecord(
                uid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                blockCount = 1,
                blockSize = 1,
                data = byteArrayOf(0),
                readAtEpochMillis = 0L,
            )

            assertEquals("19700101_000000_08070605.nfc", TagFileNameFormatter.format(record))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `share file creator writes an nfc file under the dedicated cache path`() = runBlocking {
        val record = TagRecord(
            uid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            blockCount = 1,
            blockSize = 4,
            data = byteArrayOf(0x10, 0x11, 0x12, 0x13),
            readAtEpochMillis = 0L,
        )

        val file = TagShareFileCreator(temporaryFolder.root, Dispatchers.Unconfined).create(record)

        assertEquals("shared_tags", file.parentFile?.name)
        assertTrue(file.name.endsWith("_08070605.nfc"))
        assertTrue(file.readText().contains("Data Content: 10 11 12 13"))
    }
}
