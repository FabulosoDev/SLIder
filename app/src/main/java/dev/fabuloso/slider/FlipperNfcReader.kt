package dev.fabuloso.slider

data class FlipperNfcData(
    val uid: ByteArray,
    val blockCount: Int,
    val blockSize: Int,
    val data: ByteArray,
)

object FlipperNfcReader {
    fun read(
        transport: Iso15693Transport,
        uid: ByteArray,
        log: (String) -> Unit,
    ): FlipperNfcData {
        log("Selecting tag...")
        transport.selectCard(uid)
        Thread.sleep(5)

        val (blockCount, blockSize) = transport.getSystemInfoAddressed(uid)
            ?: run {
                log("System info unavailable; using 8 blocks x 4 bytes")
                Pair(8, 4)
            }
        log("Reading $blockCount blocks x $blockSize bytes")

        val data = transport.readBlocksAddressed(uid, blockCount, blockSize) { block ->
            log("Block ${block.toString().padStart(2, '0')} read")
        }
        log("Read complete")

        return FlipperNfcData(
            uid = uid,
            blockCount = blockCount,
            blockSize = blockSize,
            data = data,
        )
    }
}
