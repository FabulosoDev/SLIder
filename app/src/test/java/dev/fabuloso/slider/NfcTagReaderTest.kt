package dev.fabuloso.slider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NfcTagReaderTest {
    @Test
    fun `reader rejects an unsupported UID before opening the transport`() = runTest {
        var transportOpened = false
        val tag = object : NfcTagHandle {
            override val uidMsb = ByteArray(TagRecord.UID_SIZE)

            override fun openTransport(): NfcTransport {
                transportOpened = true
                return ScriptedTransport()
            }
        }

        val error = runCatching { reader().read(tag) }.exceptionOrNull()

        assertTrue(error is UnsupportedTagException)
        assertTrue(!transportOpened)
    }

    @Test
    fun `reader retries select and assembles the complete memory`() = runTest {
        val transport = ScriptedTransport(selectFailures = 2)
        val delays = mutableListOf<Long>()
        val reader = DefaultNfcTagReader(
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 42L },
            retryDelay = { delays += it },
        )
        val uid = supportedUid()

        val record = reader.readConnected(transport, uid)

        assertEquals(3, transport.selectAttempts)
        assertEquals(listOf(10L, 10L, 5L), delays)
        assertEquals(8, record.blockCount)
        assertEquals(4, record.blockSize)
        assertEquals(42L, record.readAtEpochMillis)
        assertEquals(32, record.data.size)
        assertArrayEquals(
            byteArrayOf(4, 3, 2, 1, 0x10, 0x03, 0x04, 0xE0.toByte()),
            transport.frames.first().copyOfRange(2, 10),
        )
    }

    @Test
    fun `reader falls back to compatible geometry when system info fails`() = runTest {
        val transport = ScriptedTransport(systemInfoFails = true)
        val progress = mutableListOf<String>()
        val reader = DefaultNfcTagReader(
            ioDispatcher = Dispatchers.Unconfined,
            retryDelay = {},
        )

        val record = reader.readConnected(transport, supportedUid(), progress::add)

        assertEquals(8, record.blockCount)
        assertEquals(4, record.blockSize)
        assertEquals(32, record.data.size)
        assertTrue(progress.any { it.contains("supported SLIX-L geometry") })
    }

    @Test
    fun `reader uses high data rate flag when low data rate is disabled`() = runTest {
        val transport = ScriptedTransport()
        val reader = DefaultNfcTagReader(
            ioDispatcher = Dispatchers.Unconfined,
            lowDataRate = { false },
            retryDelay = {},
        )

        reader.readConnected(transport, supportedUid())

        assertTrue(transport.frames.isNotEmpty())
        transport.frames.forEach { frame ->
            assertEquals(0x22, frame[0].toInt() and 0xFF)
        }
    }

    @Test
    fun `reader rejects a different manufacturer before NFC communication`() = runTest {
        val transport = ScriptedTransport()
        val reader = reader()
        val uid = supportedUid().apply { this[1] = 0x05 }

        val error = runCatching { reader.readConnected(transport, uid) }.exceptionOrNull()

        assertTrue(error is UnsupportedTagException)
        assertTrue(transport.frames.isEmpty())
    }

    @Test
    fun `reader rejects the SLI-L variant before NFC communication`() = runTest {
        val transport = ScriptedTransport()
        val reader = reader()
        val uid = supportedUid().apply { this[3] = 0x00 }

        val error = runCatching { reader.readConnected(transport, uid) }.exceptionOrNull()

        assertTrue(error is UnsupportedTagException)
        assertTrue(transport.frames.isEmpty())
    }

    @Test
    fun `reader rejects unexpected memory geometry before reading blocks`() = runTest {
        val reader = reader()

        listOf(28 to 4, 8 to 8).forEach { (blockCount, blockSize) ->
            val transport = ScriptedTransport(blockCount = blockCount, blockSize = blockSize)
            val error = runCatching {
                reader.readConnected(transport, supportedUid())
            }.exceptionOrNull()

            assertTrue(error is UnsupportedTagException)
            assertEquals(0, transport.readAttempts)
        }
    }

    private fun reader() = DefaultNfcTagReader(
        ioDispatcher = Dispatchers.Unconfined,
        retryDelay = {},
    )

    private fun supportedUid() = byteArrayOf(
        0xE0.toByte(),
        0x04,
        0x03,
        0x10,
        1,
        2,
        3,
        4,
    )

    private class ScriptedTransport(
        private val selectFailures: Int = 0,
        private val blockCount: Int = 8,
        private val blockSize: Int = 4,
        private val systemInfoFails: Boolean = false,
    ) : NfcTransport {
        val frames = mutableListOf<ByteArray>()
        var selectAttempts = 0
        var readAttempts = 0

        override fun connect() = Unit

        override fun transceive(frame: ByteArray): ByteArray {
            frames += frame.copyOf()
            return when (frame[1].toInt() and 0xFF) {
                0x25 -> {
                    selectAttempts++
                    if (selectAttempts <= selectFailures) throw IOException("temporary")
                    byteArrayOf(0)
                }
                0x2B -> {
                    if (systemInfoFails) throw IOException("unsupported")
                    ByteArray(12).apply {
                        this[1] = 0x04
                        this[10] = (blockCount - 1).toByte()
                        this[11] = (blockSize - 1).toByte()
                    }
                }
                0x20 -> {
                    readAttempts++
                    val block = frame[10].toInt() and 0xFF
                    ByteArray(blockSize + 1) { index ->
                        if (index == 0) 0 else (block + index - 1).toByte()
                    }
                }
                else -> error("Unexpected command")
            }
        }

        override fun close() = Unit
    }
}
