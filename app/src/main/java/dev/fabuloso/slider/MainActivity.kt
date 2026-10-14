package dev.fabuloso.slider

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.fabuloso.slider.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tagStore: StoredTagStore
    private lateinit var tagAdapter: TagListAdapter
    private val records = mutableListOf<StoredTagRecord>()
    private var nfcAdapter: NfcAdapter? = null
    private var pendingSaveUid: String? = null

    private val createReadFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val uid = pendingSaveUid
        pendingSaveUid = null

        if (uri == null) {
            toast(R.string.toast_save_canceled)
            return@registerForActivityResult
        }

        val record = records.firstOrNull { it.uidHex == uid }
        if (record == null) {
            Log.w(TAG, "Save destination returned without a matching tag record")
            toast(R.string.toast_save_missing)
            return@registerForActivityResult
        }

        try {
            val text = FlipperNfcSerializer.serialize(
                uid = record.uid,
                data = record.data,
                blockCount = record.blockCount,
                blockSize = record.blockSize,
            )
            contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open destination")
            Log.i(TAG, "Saved tag ${record.uidHex} to $uri")
            toast(R.string.toast_save_success)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not save tag ${record.uidHex}", t)
            toast(R.string.toast_save_failed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        tagStore = StoredTagStore(this)
        try {
            records += tagStore.load()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load the stored tag list", t)
            toast(R.string.toast_storage_failed)
        }
        pendingSaveUid = savedInstanceState?.getString(STATE_PENDING_SAVE_UID)

        tagAdapter = TagListAdapter(
            context = this,
            records = records,
            onSave = ::saveTag,
            onDelete = ::confirmDeleteTag,
        )
        binding.listTags.adapter = tagAdapter
        binding.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        updateDeleteAllState()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "This device does not support NFC")
            toast(R.string.toast_nfc_unavailable)
        } else {
            Log.i(TAG, "Automatic NFC reading is active")
        }

        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_SAVE_UID, pendingSaveUid)
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
        setIntent(intent)
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
            Log.w(TAG, "Detected tag is not ISO15693 / NfcV")
            toast(R.string.toast_tag_unsupported)
            return
        }

        readTag(tag)
    }

    private fun readTag(tag: Tag) {
        val currentUidMsb = tag.id.reversedArray()
        Log.i(TAG, "Reading UID ${currentUidMsb.toHexCompact()}")

        try {
            val data = Iso15693Transport(tag, lowDataRate = true).use { transport ->
                transport.connect()
                FlipperNfcReader.read(transport, currentUidMsb) { Log.d(TAG, it) }
            }
            onReadCompleted(data)
        } catch (t: Throwable) {
            Log.e(TAG, "Tag read failed", t)
            toast(R.string.toast_read_failed)
        }
    }

    private fun onReadCompleted(data: FlipperNfcData) {
        val record = StoredTagRecord.from(data)
        try {
            if (!tagStore.add(record)) {
                Log.i(TAG, "Ignoring duplicate tag ${record.uidHex}")
                toast(R.string.toast_tag_duplicate)
                return
            }
            records += record
            tagAdapter.notifyDataSetChanged()
            updateDeleteAllState()
            Log.i(TAG, "Added tag ${record.uidHex}")
            toast(R.string.toast_tag_added)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not persist tag ${record.uidHex}", t)
            toast(R.string.toast_storage_failed)
        }
    }

    private fun saveTag(record: StoredTagRecord) {
        pendingSaveUid = record.uidHex
        val uidPrefix = record.uid.toReversedHexCompact(byteCount = 4)
        val defaultName = "${timestamp(record.readAt)}_${uidPrefix}.nfc"
        createReadFile.launch(defaultName)
    }

    private fun confirmDeleteTag(record: StoredTagRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_tag_title)
            .setMessage(getString(R.string.delete_tag_message, record.uidHex))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteTag(record) }
            .show()
    }

    private fun deleteTag(record: StoredTagRecord) {
        try {
            if (tagStore.remove(record.uidHex)) {
                records.removeAll { it.uidHex == record.uidHex }
                tagAdapter.notifyDataSetChanged()
                updateDeleteAllState()
                toast(R.string.toast_tag_deleted)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not delete tag ${record.uidHex}", t)
            toast(R.string.toast_storage_failed)
        }
    }

    private fun confirmDeleteAll() {
        if (records.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(R.string.delete_all_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteAllTags() }
            .show()
    }

    private fun deleteAllTags() {
        try {
            tagStore.clear()
            records.clear()
            tagAdapter.notifyDataSetChanged()
            updateDeleteAllState()
            toast(R.string.toast_all_deleted)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not delete all tags", t)
            toast(R.string.toast_storage_failed)
        }
    }

    private fun updateDeleteAllState() {
        binding.btnDeleteAll.isEnabled = records.isNotEmpty()
        binding.btnDeleteAll.alpha = if (records.isEmpty()) 0.45f else 1f
    }

    private fun applySystemBarInsets() {
        val view = binding.mainContent
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { content, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(
                initialLeft + insets.left,
                initialTop + insets.top,
                initialRight + insets.right,
                initialBottom + insets.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
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

    private fun timestamp(date: Date): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "SLIder"
        private const val STATE_PENDING_SAVE_UID = "pendingSaveUid"
    }
}
