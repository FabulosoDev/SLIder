package dev.fabuloso.slider

import android.nfc.Tag
import android.nfc.tech.NfcV

interface NfcTransport : AutoCloseable {
    fun connect()

    fun transceive(frame: ByteArray): ByteArray
}

class Iso15693Transport(tag: Tag) : NfcTransport {
    private val nfcV: NfcV = NfcV.get(tag) ?: error("NfcV.get(tag) returned null")

    override fun connect() {
        nfcV.connect()
    }

    override fun transceive(frame: ByteArray): ByteArray = nfcV.transceive(frame)

    override fun close() {
        runCatching { nfcV.close() }
    }
}

interface NfcTagHandle {
    val uidMsb: ByteArray

    fun openTransport(): NfcTransport
}

class AndroidNfcTagHandle(private val tag: Tag) : NfcTagHandle {
    private val uidValue = tag.id.reversedArray()

    override val uidMsb: ByteArray
        get() = uidValue.copyOf()

    override fun openTransport(): NfcTransport = Iso15693Transport(tag)
}
