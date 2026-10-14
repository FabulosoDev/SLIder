package dev.fabuloso.slider

/** Immutable representation of one successfully read ISO15693 tag. */
class TagRecord(
    uid: ByteArray,
    val blockCount: Int,
    val blockSize: Int,
    data: ByteArray,
    val readAtEpochMillis: Long,
) {
    private val uidValue = uid.copyOf()
    private val dataValue = data.copyOf()

    val uid: ByteArray
        get() = uidValue.copyOf()

    val data: ByteArray
        get() = dataValue.copyOf()

    val uidHex: String = uidValue.toHexCompact()

    init {
        require(uidValue.size == UID_SIZE) { "UID must be $UID_SIZE bytes" }
        require(blockCount in 1..MAX_BLOCK_COUNT) { "Invalid block count" }
        require(blockSize in 1..MAX_BLOCK_SIZE) { "Invalid block size" }
        require(dataValue.size == blockCount * blockSize) {
            "Tag data length does not match its block geometry"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TagRecord &&
            uidValue.contentEquals(other.uidValue) &&
            blockCount == other.blockCount &&
            blockSize == other.blockSize &&
            dataValue.contentEquals(other.dataValue) &&
            readAtEpochMillis == other.readAtEpochMillis

    override fun hashCode(): Int {
        var result = uidValue.contentHashCode()
        result = 31 * result + blockCount
        result = 31 * result + blockSize
        result = 31 * result + dataValue.contentHashCode()
        result = 31 * result + readAtEpochMillis.hashCode()
        return result
    }

    companion object {
        const val UID_SIZE = 8
        const val MAX_BLOCK_COUNT = 256
        const val MAX_BLOCK_SIZE = 0x20
    }
}
