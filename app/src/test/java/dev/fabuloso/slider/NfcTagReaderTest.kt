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
    fun `reader retries select and assembles all reported blocks`() = runTest {
        val transport = ScriptedTransport(selectFailures = 2, blockCount = 2, blockSize = 4)
        val delays = mutableListOf<Long>()
        val reader = DefaultNfcTagReader(
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 42L },
            retryDelay = { delays += it },
        )
        val uid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val record = reader.readConnected(transport, uid)

        assertEquals(3, transport.selectAttempts)
        assertEquals(listOf(10L, 10L, 5L), delays)
        assertEquals(2, record.blockCount)
        assertEquals(4, record.blockSize)
        assertEquals(42L, record.readAtEpochMillis)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 1, 2, 3, 4), record.data)
        assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), transport.frames.first().copyOfRange(2, 10))
    }

    @Test
    fun `reader falls back to compatible geometry when system info fails`() = runTest {
        val transport = ScriptedTransport(systemInfoFails = true)
        val progress = mutableListOf<String>()
        val reader = DefaultNfcTagReader(
            ioDispatcher = Dispatchers.Unconfined,
            retryDelay = {},
        )

        val record = reader.readConnected(transport, ByteArray(8), progress::add)

        assertEquals(8, record.blockCount)
        assertEquals(4, record.blockSize)
        assertEquals(32, record.data.size)
        assertTrue(progress.any { it.contains("System info unavailable") })
    }

    @Test
    fun `reader uses high data rate flag when low data rate is disabled`() = runTest {
        val transport = ScriptedTransport(blockCount = 1)
        val reader = DefaultNfcTagReader(
            ioDispatcher = Dispatchers.Unconfined,
            lowDataRate = { false },
            retryDelay = {},
        )

        reader.readConnected(transport, ByteArray(8))

        assertTrue(transport.frames.isNotEmpty())
        transport.frames.forEach { frame ->
            assertEquals(0x22, frame[0].toInt() and 0xFF)
        }
    }

    private class ScriptedTransport(
        private val selectFailures: Int = 0,
        private val blockCount: Int = 8,
        private val blockSize: Int = 4,
        private val systemInfoFails: Boolean = false,
    ) : NfcTransport {
        val frames = mutableListOf<ByteArray>()
        var selectAttempts = 0

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
