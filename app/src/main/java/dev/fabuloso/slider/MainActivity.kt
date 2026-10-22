package dev.fabuloso.slider

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import dev.fabuloso.slider.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var tagAdapter: TagListAdapter
    private val viewModel: MainViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var lastScrolledHighlight: RecentTagHighlight? = null

    private val createReadFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        viewModel.onExportDestinationSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        configureTagList()

        binding.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        observeViewModel()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "This device does not support NFC")
            toast(R.string.toast_nfc_unavailable)
        } else {
            Log.i(TAG, "Automatic NFC reading is active")
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        disableReaderMode()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun configureTagList() {
        tagAdapter = TagListAdapter(
            onSave = { viewModel.requestExport(it.uidHex) },
            onShare = ::shareTag,
            onDelete = { record ->
                if (viewModel.uiState.value.canDelete(record.uidHex)) {
                    confirmDeleteTag(record)
                }
            },
        )
        binding.listTags.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = tagAdapter
            addItemDecoration(
                DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL),
            )
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.effects.collect(::handleEffect) }
            }
        }
    }

    private fun render(state: MainUiState) {
        val rows = state.tags.map { record ->
            TagRowItem(
                record = record,
                saveEnabled = state.canSave(record.uidHex),
                shareEnabled = state.canSave(record.uidHex),
                deleteEnabled = state.deleteButtonEnabled(record.uidHex),
                highlightStartedAtUptimeMillis = state.recentTagHighlight
                    ?.takeIf { it.uidHex == record.uidHex }
                    ?.startedAtUptimeMillis ?: 0L,
            )
        }
        val highlight = state.recentTagHighlight
        if (highlight != null &&
            highlight != lastScrolledHighlight &&
            SystemClock.uptimeMillis() - highlight.startedAtUptimeMillis <
                TagListAdapter.HIGHLIGHT_WINDOW_MS
        ) {
            tagAdapter.submitList(rows) {
                val position = tagAdapter.currentList.indexOfFirst {
                    it.record.uidHex == highlight.uidHex
                }
                if (position >= 0 && highlight != lastScrolledHighlight) {
                    lastScrolledHighlight = highlight
                    binding.listTags.scrollToPosition(position)
                }
            }
        } else {
            tagAdapter.submitList(rows)
        }

        binding.btnDeleteAll.isEnabled = state.deleteAllButtonEnabled
        binding.btnDeleteAll.alpha = if (state.deleteAllButtonEnabled) 1f else DISABLED_ALPHA
    }

    private fun handleEffect(effect: MainUiEffect) {
        when (effect) {
            is MainUiEffect.LaunchSaveDocument -> createReadFile.launch(effect.suggestedName)
            is MainUiEffect.ShowMessage -> toast(effect.messageResource)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        } ?: return

        handleTag(tag)
    }

    private fun handleTag(tag: Tag) {
        if (!tag.techList.orEmpty().contains(NfcV::class.java.name)) {
            Log.w(TAG, "Detected tag is not ISO15693 / NfcV")
            viewModel.reportUnsupportedTag()
            return
        }
        viewModel.enqueueTag(AndroidNfcTagHandle(tag))
    }

    private fun shareTag(record: TagRecord) {
        lifecycleScope.launch {
            try {
                val file = TagShareFileCreator(cacheDir).create(record)
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    file,
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = NFC_FILE_MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    clipData = ClipData.newUri(contentResolver, file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        getString(R.string.share_tag_chooser),
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Could not share tag ${record.uidHex}", error)
                toast(R.string.toast_share_failed)
            }
        }
    }

    private fun confirmDeleteTag(record: TagRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_tag_title)
            .setMessage(getString(R.string.delete_tag_message, record.uidHex))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteTag(record.uidHex) }
            .show()
    }

    private fun confirmDeleteAll() {
        if (!viewModel.uiState.value.canDeleteAll) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(R.string.delete_all_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteAllTags() }
            .show()
    }

    private fun applySystemBarInsets() {
        val view = binding.mainContent
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { content, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
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

    private fun enableReaderMode() {
        nfcAdapter?.enableReaderMode(
            this,
            ::handleTag,
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "SLIder"
        private const val DISABLED_ALPHA = 0.45f
        private const val NFC_FILE_MIME_TYPE = "application/octet-stream"
    }
}
