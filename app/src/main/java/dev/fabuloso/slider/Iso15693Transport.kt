package dev.fabuloso.slider

import android.nfc.Tag
import android.nfc.tech.NfcV

class Iso15693Transport(tag: Tag) : AutoCloseable {
    private val nfcv: NfcV = NfcV.get(tag) ?: error("NfcV.get(tag) returned null")

    fun connect() { nfcv.connect() }

    private fun transceive(frame: ByteArray): ByteArray = nfcv.transceive(frame)

    private fun transceiveChecked(frame: ByteArray): ByteArray {
        val resp = transceive(frame)
        if (resp.isEmpty()) error("Empty response")
        val flags = resp[0].toInt() and 0xFF
        if ((flags and 0x01) != 0) {
            val code = if (resp.size > 1) resp[1].toInt() and 0xFF else -1
            if (code == 0x0F) error("Card error: flags=0x1 code=0xf")
            error("Card error: flags=0x${flags.toString(16)} code=0x${code.toString(16)}")
        }
        return resp
    }

    private fun transceiveCheckedWithRetry(
        frame: ByteArray,
        retries: Int,
        retryDelayMs: Long = 20,
        opName: String,
    ): ByteArray {
        var lastErr: Throwable? = null
        repeat(retries) { attempt ->
            try { return transceiveChecked(frame) }
            catch (t: Throwable) {
                lastErr = t
                if (attempt + 1 < retries) Thread.sleep(retryDelayMs)
            }
        }
        throw error("$opName failed after $retries retries: ${lastErr?.message}")
    }

    fun selectCard(uidMsb: ByteArray) {
        val frame = ByteArray(10)
        frame[0] = 0x22
        frame[1] = 0x25
        for (i in 0 until 8) frame[2 + i] = uidMsb[7 - i]
        transceiveCheckedWithRetry(frame, retries = 3, retryDelayMs = 10, opName = "SELECT")
    }

    fun getSystemInfoAddressed(uidMsb: ByteArray): Pair<Int, Int>? {
        return try {
            val frame = ByteArray(10)
            frame[0] = 0x22
            frame[1] = 0x2B
            for (i in 0 until 8) frame[2 + i] = uidMsb[7 - i]
            val resp = transceiveChecked(frame)
            if (resp.size < 12) return null
            val infoFlags = resp[1].toInt() and 0xFF
            var offset = 10
            if ((infoFlags and 0x01) != 0) offset++
            if ((infoFlags and 0x02) != 0) offset++
            if ((infoFlags and 0x04) != 0 && resp.size >= offset + 2) {
                val blockCount = (resp[offset].toInt() and 0xFF) + 1
                val blockSize = ((resp[offset + 1].toInt() and 0x1F) + 1).coerceAtMost(4)
                Pair(blockCount, blockSize)
            } else null
        } catch (_: Throwable) { null }
    }

    fun readBlockAddressed(uidMsb: ByteArray, blockNum: Int, blockSize: Int): ByteArray {
        val frame = ByteArray(11)
        frame[0] = 0x22
        frame[1] = 0x20
        for (i in 0 until 8) frame[2 + i] = uidMsb[7 - i]
        frame[10] = (blockNum and 0xFF).toByte()
        val resp = transceiveCheckedWithRetry(frame, retries = 3, retryDelayMs = 15, opName = "Read block $blockNum")
        require(resp.size >= 1 + blockSize) { "Read block $blockNum: short response ${resp.toHexSpaced()}" }
        return resp.copyOfRange(1, 1 + blockSize)
    }

    fun readBlocksAddressed(
        uidMsb: ByteArray,
        blockCount: Int,
        blockSize: Int,
        onBlock: (Int) -> Unit,
    ): ByteArray {
        val out = ByteArray(blockCount * blockSize)
        for (b in 0 until blockCount) {
            val blk = readBlockAddressed(uidMsb, b, blockSize)
            System.arraycopy(blk, 0, out, b * blockSize, blockSize)
            onBlock(b)
        }
        return out
    }

    override fun close() {
        try { nfcv.close() } catch (_: Throwable) {}
    }
}
