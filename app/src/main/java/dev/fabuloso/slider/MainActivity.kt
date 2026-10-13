package dev.fabuloso.slider

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.fabuloso.slider.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingReadData: FlipperNfcData? = null

    private val createReadFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val readData = pendingReadData
        pendingReadData = null

        if (uri == null) {
            appLog("Save canceled")
            setStatus("Ready — hold a tag nearby")
            return@registerForActivityResult
        }
        if (readData == null) {
            appLog("Nothing to save")
            setStatus("Error")
            return@registerForActivityResult
        }

        try {
            val text = FlipperNfcSerializer.serialize(
                uid = readData.uid,
                data = readData.data,
                blockCount = readData.blockCount,
                blockSize = readData.blockSize,
            )
            contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open destination")
            appLog("Saved: $uri")
            setStatus("Read saved — ready for another tag")
        } catch (t: Throwable) {
            appLog("Save failed: ${t.message}")
            setStatus("Save error")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            setStatus("NFC not available")
            appLog("This device does not support NFC")
        } else {
            setStatus("Ready — hold a tag nearby")
            appLog("Automatic reading is active")
        }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        } ?: return
        if (!tag.techList.orEmpty().contains(NfcV::class.java.name)) {
            setStatus("Unsupported tag")
            appLog("Detected tag is not ISO15693 / NfcV")
            return
        }

        doRead(tag)
    }

    private fun doRead(tag: Tag) {
        setStatus("Reading...")
        val currentUidMsb = tag.id.reversedArray()
        appLog("UID: ${currentUidMsb.toHexCompact()}")

        try {
            val data = Iso15693Transport(tag).use { transport ->
                transport.connect()
                FlipperNfcReader.read(transport, currentUidMsb) { appLog(it) }
            }
            onReadCompleted(data)
        } catch (t: Throwable) {
            setStatus("Read error")
            appLog("READ FAILED: ${t.message}")
        }
    }

    private fun onReadCompleted(data: FlipperNfcData) {
        appLog("Data: ${data.data.toHexSpaced()}")
        pendingReadData = data
        val defaultName = "card_${data.uid.toHexCompact()}_${timestamp()}.nfc"
        setStatus("Choose save location")
        createReadFile.launch(defaultName)
    }

    private fun enableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
        )
        val filters = arrayOf(android.content.IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        val techLists = arrayOf(arrayOf(NfcV::class.java.name))
        adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    private fun disableForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun appLog(message: String) {
        binding.txtLog.append("[SLIder] $message\n")
        binding.txtLog.post {
            (binding.txtLog.parent as? android.widget.ScrollView)
                ?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun setStatus(status: String) {
        binding.txtStatus.text = "Status: $status"
    }
}
