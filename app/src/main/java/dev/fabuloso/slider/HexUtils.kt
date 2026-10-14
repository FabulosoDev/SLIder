package dev.fabuloso.slider

import java.util.Locale

fun ByteArray.toHexSpaced(): String =
    joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

fun ByteArray.toHexCompact(): String =
    joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

fun ByteArray.toReversedHexCompact(byteCount: Int = size): String {
    require(byteCount in 0..size) { "Byte count must be between 0 and $size" }
    return reversedArray().copyOf(byteCount).toHexCompact()
}

fun ByteArray.toHexColonSeparated(): String =
    joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
