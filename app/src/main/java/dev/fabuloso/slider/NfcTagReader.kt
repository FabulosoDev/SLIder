package dev.fabuloso.slider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

interface NfcTagReader {
    suspend fun read(
        tag: NfcTagHandle,
        onProgress: (String) -> Unit = {},
    ): TagRecord
}

class DefaultNfcTagReader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lowDataRate: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) : NfcTagReader {
    private val commandFlags: Byte = if (lowDataRate) 0x20 else 0x22

    override suspend fun read(
        tag: NfcTagHandle,
        onProgress: (String) -> Unit,
    ): TagRecord = withContext(ioDispatcher) {
        val uid = tag.uidMsb
        tag.openTransport().use { transport ->
            transport.connect()
            readConnected(transport, uid, onProgress)
        }
    }

    internal suspend fun readConnected(
        transport: NfcTransport,
        uidMsb: ByteArray,
        onProgress: (String) -> Unit = {},
    ): TagRecord {
        require(uidMsb.size == TagRecord.UID_SIZE) { "UID must be 8 bytes" }
        val readAt = clock()

        onProgress("Selecting tag...")
        selectCard(transport, uidMsb)
        retryDelay(SELECT_SETTLE_DELAY_MS)

        val geometry = getSystemInfoAddressed(transport, uidMsb)
            ?: BlockGeometry(DEFAULT_BLOCK_COUNT, DEFAULT_BLOCK_SIZE).also {
                onProgress("System info unavailable; using 8 blocks x 4 bytes")
            }
        onProgress("Reading ${geometry.blockCount} blocks x ${geometry.blockSize} bytes")

        val data = ByteArray(geometry.blockCount * geometry.blockSize)
        for (block in 0 until geometry.blockCount) {
            val blockData = readBlockAddressed(
                transport = transport,
                uidMsb = uidMsb,
                blockNumber = block,
                blockSize = geometry.blockSize,
            )
            blockData.copyInto(data, destinationOffset = block * geometry.blockSize)
            onProgress("Block ${block.toString().padStart(2, '0')} read")
        }
        onProgress("Read complete")

        return TagRecord(
            uid = uidMsb,
            blockCount = geometry.blockCount,
            blockSize = geometry.blockSize,
            data = data,
            readAtEpochMillis = readAt,
        )
    }

    private suspend fun selectCard(transport: NfcTransport, uidMsb: ByteArray) {
        val frame = addressedFrame(command = SELECT_COMMAND, uidMsb = uidMsb)
        transceiveCheckedWithRetry(
            transport = transport,
            frame = frame,
            retries = RETRY_COUNT,
            retryDelayMs = SELECT_RETRY_DELAY_MS,
            operation = "SELECT",
        )
    }

    private fun getSystemInfoAddressed(
        transport: NfcTransport,
        uidMsb: ByteArray,
    ): BlockGeometry? {
        return try {
            val response = transceiveChecked(
                transport,
                addressedFrame(command = SYSTEM_INFO_COMMAND, uidMsb = uidMsb),
            )
            if (response.size < MINIMUM_SYSTEM_INFO_SIZE) return null

            val infoFlags = response[1].toInt() and 0xFF
            var offset = SYSTEM_INFO_DATA_OFFSET
            if ((infoFlags and 0x01) != 0) offset++
            if ((infoFlags and 0x02) != 0) offset++
            if ((infoFlags and 0x04) == 0 || response.size < offset + 2) return null

            val blockCount = (response[offset].toInt() and 0xFF) + 1
            val blockSize = ((response[offset + 1].toInt() and 0x1F) + 1)
                .coerceAtMost(COMPATIBLE_MAX_BLOCK_SIZE)
            BlockGeometry(blockCount, blockSize)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun readBlockAddressed(
        transport: NfcTransport,
        uidMsb: ByteArray,
        blockNumber: Int,
        blockSize: Int,
    ): ByteArray {
        val frame = addressedFrame(
            command = READ_SINGLE_BLOCK_COMMAND,
            uidMsb = uidMsb,
            trailingByte = blockNumber,
        )
        val response = transceiveCheckedWithRetry(
            transport = transport,
            frame = frame,
            retries = RETRY_COUNT,
            retryDelayMs = READ_RETRY_DELAY_MS,
            operation = "Read block $blockNumber",
        )
        require(response.size >= 1 + blockSize) {
            "Read block $blockNumber: short response ${response.toHexSpaced()}"
        }
        return response.copyOfRange(1, 1 + blockSize)
    }

    private fun addressedFrame(
        command: Int,
        uidMsb: ByteArray,
        trailingByte: Int? = null,
    ): ByteArray {
        val frame = ByteArray(if (trailingByte == null) 10 else 11)
        frame[0] = commandFlags
        frame[1] = command.toByte()
        for (index in uidMsb.indices) frame[2 + index] = uidMsb[7 - index]
        trailingByte?.let { frame[10] = (it and 0xFF).toByte() }
        return frame
    }

    private fun transceiveChecked(transport: NfcTransport, frame: ByteArray): ByteArray {
        val response = transport.transceive(frame)
        check(response.isNotEmpty()) { "Empty response" }

        val flags = response[0].toInt() and 0xFF
        if ((flags and ERROR_FLAG) != 0) {
            val code = response.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
            error("Card error: flags=0x${flags.toString(16)} code=0x${code.toString(16)}")
        }
        return response
    }

    private suspend fun transceiveCheckedWithRetry(
        transport: NfcTransport,
        frame: ByteArray,
        retries: Int,
        retryDelayMs: Long,
        operation: String,
    ): ByteArray {
        var lastError: Throwable? = null
        repeat(retries) { attempt ->
            try {
                return transceiveChecked(transport, frame)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < retries) retryDelay(retryDelayMs)
            }
        }
        throw IllegalStateException(
            "$operation failed after $retries retries: ${lastError?.message}",
            lastError,
        )
    }

    private data class BlockGeometry(val blockCount: Int, val blockSize: Int)

    companion object {
        private const val SELECT_COMMAND = 0x25
        private const val SYSTEM_INFO_COMMAND = 0x2B
        private const val READ_SINGLE_BLOCK_COMMAND = 0x20
        private const val ERROR_FLAG = 0x01
        private const val RETRY_COUNT = 3
        private const val SELECT_RETRY_DELAY_MS = 10L
        private const val READ_RETRY_DELAY_MS = 15L
        private const val SELECT_SETTLE_DELAY_MS = 5L
        private const val DEFAULT_BLOCK_COUNT = 8
        private const val DEFAULT_BLOCK_SIZE = 4
        private const val COMPATIBLE_MAX_BLOCK_SIZE = 4
        private const val MINIMUM_SYSTEM_INFO_SIZE = 12
        private const val SYSTEM_INFO_DATA_OFFSET = 10
    }
}
