package dev.fabuloso.slider

internal object SlixLProfile {
    const val BLOCK_COUNT = 8
    const val BLOCK_SIZE = 4

    private val uidPrefix = byteArrayOf(0xE0.toByte(), 0x04, 0x03)
    private const val SLIX_VARIANT_MASK = 0x10

    fun requireSupportedUid(uidMsb: ByteArray) {
        if (!isSupportedUid(uidMsb)) {
            throw UnsupportedTagException("UID does not identify a supported ICODE SLIX-L tag")
        }
    }

    fun requireSupportedGeometry(blockCount: Int, blockSize: Int) {
        if (blockCount != BLOCK_COUNT || blockSize != BLOCK_SIZE) {
            throw UnsupportedTagException(
                "Unsupported memory geometry: $blockCount blocks x $blockSize bytes",
            )
        }
    }

    private fun isSupportedUid(uidMsb: ByteArray): Boolean =
        uidMsb.size == TagRecord.UID_SIZE &&
            uidPrefix.indices.all { index -> uidMsb[index] == uidPrefix[index] } &&
            (uidMsb[3].toInt() and SLIX_VARIANT_MASK) != 0
}

internal class UnsupportedTagException(message: String) : IllegalArgumentException(message)
