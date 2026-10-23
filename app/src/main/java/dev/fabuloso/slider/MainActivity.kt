package dev.fabuloso.slider

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.fabuloso.slider.databinding.ActivityMainBinding
import dev.fabuloso.slider.databinding.BottomSheetTagActionsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var tagAdapter: TagListAdapter
    private lateinit var appSettings: AppSettings
    private lateinit var messageBanner: MessageBannerController
    private lateinit var nfcReader: NfcReaderController
    private val viewModel: MainViewModel by viewModels()
    private var lastScrolledNewBadge: NewTagBadge? = null

    private val createReadFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        viewModel.onExportDestinationSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = AppSettings(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        messageBanner = MessageBannerController(this, binding)
        applySystemBarInsets()
        configureTagList()
        configureTopAppBar()

        observeViewModel()
        nfcReader = NfcReaderController(
            activity = this,
            onTagDiscovered = { tag -> viewModel.enqueueTag(AndroidNfcTagHandle(tag)) },
            onUnsupportedTag = viewModel::reportUnsupportedTag,
            onNfcUnavailable = { messageBanner.show(R.string.toast_nfc_unavailable) },
            onNfcDisabled = { openSettings ->
                messageBanner.showAction(
                    message = R.string.toast_nfc_disabled,
                    actionLabel = R.string.open_settings,
                    durationMillis = null,
                    onAction = openSettings,
                )
            },
            onNfcEnabled = {
                messageBanner.hideIfShowing(R.string.toast_nfc_disabled)
            },
        )

        if (savedInstanceState == null) nfcReader.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcReader.resume()
    }

    override fun onPause() {
        nfcReader.pause()
        super.onPause()
    }

    override fun onDestroy() {
        messageBanner.dispose()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nfcReader.handleIntent(intent)
    }

    private fun configureTagList() {
        tagAdapter = TagListAdapter(onClick = ::showTagActions)
        binding.listTags.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = tagAdapter
            addItemDecoration(
                DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL),
            )
        }
        ItemTouchHelper(
            TagSwipeCallback(
                context = this,
                adapter = tagAdapter,
                onShare = ::shareTag,
                onDelete = { record -> viewModel.deleteTag(record.uidHex) },
            ),
        ).attachToRecyclerView(binding.listTags)
    }

    private fun configureTopAppBar() {
        binding.topAppBar.inflateMenu(R.menu.main_menu)
        binding.topAppBar.menu.findItem(R.id.actionLowDataRate)?.isChecked =
            appSettings.lowDataRateEnabled
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.actionLowDataRate -> {
                    item.isChecked = !item.isChecked
                    appSettings.lowDataRateEnabled = item.isChecked
                    true
                }
                R.id.actionDeleteAll -> {
                    confirmDeleteAll()
                    true
                }
                else -> false
            }
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
                shareEnabled = state.canSave(record.uidHex),
                deleteEnabled = state.canDelete(record.uidHex),
                newBadgeStartedAtUptimeMillis = state.newTagBadge
                    ?.takeIf { it.uidHex == record.uidHex }
                    ?.startedAtUptimeMillis ?: 0L,
            )
        }
        val newBadge = state.newTagBadge
        if (newBadge != null &&
            newBadge != lastScrolledNewBadge &&
            SystemClock.uptimeMillis() - newBadge.startedAtUptimeMillis <
                TagListAdapter.NEW_BADGE_WINDOW_MS
        ) {
            tagAdapter.submitList(rows) {
                val position = tagAdapter.currentList.indexOfFirst {
                    it.record.uidHex == newBadge.uidHex
                }
                if (position >= 0 && newBadge != lastScrolledNewBadge) {
                    lastScrolledNewBadge = newBadge
                    binding.listTags.scrollToPosition(position)
                }
            }
        } else {
            tagAdapter.submitList(rows)
        }

        binding.topAppBar.menu.findItem(R.id.actionDeleteAll)?.isEnabled =
            state.deleteAllButtonEnabled
    }

    private fun handleEffect(effect: MainUiEffect) {
        when (effect) {
            is MainUiEffect.LaunchSaveDocument -> createReadFile.launch(effect.suggestedName)
            is MainUiEffect.ShowMessage -> messageBanner.show(effect.messageResource)
            is MainUiEffect.TagDeleted -> {
                messageBanner.showAction(
                    message = R.string.toast_tag_deleted,
                    actionLabel = R.string.undo,
                    durationMillis = UNDO_BANNER_DURATION_MS,
                    onAction = {
                        viewModel.undoDelete(effect.record, effect.originalIndex)
                    },
                )
            }
        }
    }

    private fun showTagActions(record: TagRecord) {
        val state = viewModel.uiState.value
        if (state.tags.none { it.uidHex == record.uidHex }) return

        val sheet = BottomSheetTagActionsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        sheet.txtTagUid.text = record.uid.toHexColonSeparated()
        sheet.txtReadAt.text =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(record.readAtEpochMillis))
        setActionEnabled(sheet.actionSave, state.canSave(record.uidHex))
        setActionEnabled(sheet.actionShare, state.canSave(record.uidHex))
        setActionEnabled(sheet.actionDelete, state.canDelete(record.uidHex))

        sheet.actionSave.setOnClickListener {
            dialog.dismiss()
            viewModel.requestExport(record.uidHex)
        }
        sheet.actionShare.setOnClickListener {
            dialog.dismiss()
            shareTag(record)
        }
        sheet.actionDelete.setOnClickListener {
            dialog.dismiss()
            viewModel.deleteTag(record.uidHex)
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun setActionEnabled(action: View, enabled: Boolean) {
        action.isEnabled = enabled
        action.alpha = if (enabled) 1f else DISABLED_ALPHA
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
                messageBanner.show(R.string.toast_share_failed)
            }
        }
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

    companion object {
        private const val TAG = "SLIder"
        private const val DISABLED_ALPHA = 0.45f
        private const val NFC_FILE_MIME_TYPE = "application/octet-stream"
        private const val UNDO_BANNER_DURATION_MS = 5_000L
    }
}
