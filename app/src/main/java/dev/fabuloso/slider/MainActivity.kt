package dev.fabuloso.slider

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val viewModel: MainViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var nfcSettingsLaunched = false
    private var currentMessage: Int? = null
    private var lastScrolledNewBadge: NewTagBadge? = null
    private val hideMessageBanner = Runnable { hideMessageBanner() }

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
        applySystemBarInsets()
        configureTagList()
        configureTopAppBar()
        binding.btnDismissMessage.setOnClickListener { hideMessageBanner() }

        observeViewModel()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "This device does not support NFC")
            showMessage(R.string.toast_nfc_unavailable)
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateNfcState()
    }

    override fun onPause() {
        disableReaderMode()
        super.onPause()
    }

    override fun onDestroy() {
        binding.messageBanner.removeCallbacks(hideMessageBanner)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
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
        ItemTouchHelper(TagSwipeCallback()).attachToRecyclerView(binding.listTags)
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
            is MainUiEffect.ShowMessage -> showMessage(effect.messageResource)
            is MainUiEffect.TagDeleted -> showDeletedMessage(effect)
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
                showMessage(R.string.toast_share_failed)
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

    private fun enableReaderMode() {
        nfcAdapter?.enableReaderMode(
            this,
            ::handleTag,
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    private fun updateNfcState() {
        val adapter = nfcAdapter ?: return
        if (adapter.isEnabled) {
            nfcSettingsLaunched = false
            if (currentMessage == R.string.toast_nfc_disabled) hideMessageBanner()
            enableReaderMode()
            Log.i(TAG, "Automatic NFC reading is active")
            return
        }

        Log.w(TAG, "NFC is disabled")
        showMessage(
            message = R.string.toast_nfc_disabled,
            style = messageBannerStyle(R.string.toast_nfc_disabled),
            actionLabel = R.string.open_settings,
            onAction = ::openNfcSettings,
        )
        if (!nfcSettingsLaunched) openNfcSettings()
    }

    private fun openNfcSettings() {
        nfcSettingsLaunched = true
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Dedicated NFC settings are unavailable; opening wireless settings", error)
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    private fun showMessage(message: Int) {
        showMessage(message, messageBannerStyle(message))
    }

    private fun showDeletedMessage(effect: MainUiEffect.TagDeleted) {
        showMessage(
            message = R.string.toast_tag_deleted,
            style = messageBannerStyle(R.string.toast_tag_deleted).copy(
                durationMillis = UNDO_BANNER_DURATION_MS,
            ),
            actionLabel = R.string.undo,
            onAction = {
                viewModel.undoDelete(effect.record, effect.originalIndex)
            },
        )
    }

    private fun showMessage(
        message: Int,
        style: MessageBannerStyle,
        actionLabel: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        currentMessage = message
        val banner = binding.messageBanner
        banner.removeCallbacks(hideMessageBanner)
        banner.animate().cancel()

        binding.txtMessageBanner.apply {
            setText(message)
            setTextColor(ContextCompat.getColor(this@MainActivity, style.contentColor))
        }
        binding.btnDismissMessage.apply {
            visibility = if (style.durationMillis == null && onAction == null) {
                View.VISIBLE
            } else {
                View.GONE
            }
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, style.contentColor),
            )
        }
        binding.btnMessageAction.apply {
            visibility = if (actionLabel != null && onAction != null) View.VISIBLE else View.GONE
            actionLabel?.let(::setText)
            setTextColor(ContextCompat.getColor(this@MainActivity, style.contentColor))
            setOnClickListener(
                if (onAction == null) {
                    null
                } else {
                    View.OnClickListener {
                        hideMessageBanner()
                        onAction()
                    }
                },
            )
        }
        banner.setBackgroundResource(style.background)

        if (banner.visibility != View.VISIBLE) {
            banner.alpha = 0f
            banner.translationY = -dp(MESSAGE_BANNER_OFFSET_DP).toFloat()
            banner.visibility = View.VISIBLE
        }
        banner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MESSAGE_BANNER_ANIMATION_MS)
            .start()

        style.durationMillis?.let { duration ->
            banner.postDelayed(hideMessageBanner, duration)
        }
    }

    private fun hideMessageBanner() {
        val banner = binding.messageBanner
        banner.removeCallbacks(hideMessageBanner)
        if (banner.visibility != View.VISIBLE) return
        currentMessage = null

        banner.animate().cancel()
        banner.animate()
            .alpha(0f)
            .translationY(-dp(MESSAGE_BANNER_OFFSET_DP).toFloat())
            .setDuration(MESSAGE_BANNER_ANIMATION_MS)
            .withEndAction {
                banner.visibility = View.GONE
                banner.alpha = 1f
                banner.translationY = 0f
            }
            .start()
    }

    private fun messageBannerStyle(message: Int): MessageBannerStyle = when (message) {
        R.string.toast_tag_added,
        R.string.toast_save_success,
        R.string.toast_tag_deleted,
        R.string.toast_tag_restored,
        R.string.toast_all_deleted,
        -> MessageBannerStyle(
            background = R.drawable.bg_message_success,
            contentColor = R.color.message_success_content,
            durationMillis = MESSAGE_BANNER_DURATION_MS,
        )

        R.string.toast_tag_unsupported,
        R.string.toast_tag_duplicate,
        R.string.toast_scan_queue_full,
        -> MessageBannerStyle(
            background = R.drawable.bg_message_warning,
            contentColor = R.color.message_warning_content,
            durationMillis = MESSAGE_BANNER_DURATION_MS,
        )

        R.string.toast_nfc_unavailable,
        R.string.toast_nfc_disabled,
        R.string.toast_read_failed,
        R.string.toast_storage_failed,
        R.string.toast_save_failed,
        R.string.toast_save_missing,
        R.string.toast_share_failed,
        -> MessageBannerStyle(
            background = R.drawable.bg_message_error,
            contentColor = R.color.message_error_content,
            durationMillis = null,
        )

        else -> MessageBannerStyle(
            background = R.drawable.bg_message_info,
            contentColor = R.color.message_info_content,
            durationMillis = MESSAGE_BANNER_DURATION_MS,
        )
    }

    private inner class TagSwipeCallback :
        ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val backgroundPaint = Paint()
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                resources.displayMetrics,
            )
            typeface = Typeface.DEFAULT_BOLD
        }
        private val shareIcon = swipeIcon(R.drawable.ic_share)
        private val deleteIcon = swipeIcon(R.drawable.ic_delete)

        override fun getSwipeDirs(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            val item = tagAdapter.currentList.getOrNull(viewHolder.bindingAdapterPosition)
                ?: return 0
            var directions = 0
            if (item.shareEnabled) directions = directions or ItemTouchHelper.RIGHT
            if (item.deleteEnabled) directions = directions or ItemTouchHelper.LEFT
            return directions
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            val item = tagAdapter.currentList.getOrNull(position) ?: return
            when (direction) {
                ItemTouchHelper.RIGHT -> {
                    shareTag(item.record)
                    resetSwipedRow(item.record.uidHex)
                }
                ItemTouchHelper.LEFT -> {
                    resetSwipedRow(item.record.uidHex)
                    viewModel.deleteTag(item.record.uidHex)
                }
            }
        }

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float =
            SWIPE_THRESHOLD

        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                if (dX > 0f) {
                    drawSwipeAction(
                        canvas,
                        viewHolder.itemView,
                        dX,
                        R.color.action_share,
                        shareIcon,
                        getString(R.string.swipe_share),
                    )
                } else {
                    drawSwipeAction(
                        canvas,
                        viewHolder.itemView,
                        dX,
                        R.color.action_danger,
                        deleteIcon,
                        getString(R.string.delete),
                    )
                }
            }
            super.onChildDraw(
                canvas,
                recyclerView,
                viewHolder,
                dX,
                dY,
                actionState,
                isCurrentlyActive,
            )
        }

        private fun drawSwipeAction(
            canvas: Canvas,
            itemView: View,
            dX: Float,
            colorResource: Int,
            icon: Drawable,
            label: String,
        ) {
            val fromStart = dX > 0f
            val revealLeft = if (fromStart) itemView.left.toFloat() else itemView.right + dX
            val revealRight = if (fromStart) itemView.left + dX else itemView.right.toFloat()
            backgroundPaint.color = ContextCompat.getColor(this@MainActivity, colorResource)
            canvas.drawRect(
                revealLeft,
                itemView.top.toFloat(),
                revealRight,
                itemView.bottom.toFloat(),
                backgroundPaint,
            )

            val iconSize = dp(SWIPE_ICON_SIZE_DP)
            val margin = dp(SWIPE_CONTENT_MARGIN_DP)
            val spacing = dp(SWIPE_ICON_TEXT_SPACING_DP)
            val centerY = (itemView.top + itemView.bottom) / 2
            val iconTop = centerY - iconSize / 2
            val textBaseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f

            canvas.save()
            canvas.clipRect(revealLeft, itemView.top.toFloat(), revealRight, itemView.bottom.toFloat())
            if (fromStart) {
                val iconLeft = itemView.left + margin
                icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                icon.draw(canvas)
                canvas.drawText(label, (iconLeft + iconSize + spacing).toFloat(), textBaseline, textPaint)
            } else {
                val iconRight = itemView.right - margin
                val iconLeft = iconRight - iconSize
                icon.setBounds(iconLeft, iconTop, iconRight, iconTop + iconSize)
                icon.draw(canvas)
                val textX = iconLeft - spacing - textPaint.measureText(label)
                canvas.drawText(label, textX, textBaseline, textPaint)
            }
            canvas.restore()
        }

        private fun swipeIcon(resource: Int): Drawable =
            requireNotNull(ContextCompat.getDrawable(this@MainActivity, resource)).mutate().apply {
                setTint(Color.WHITE)
            }
    }

    private fun resetSwipedRow(uidHex: String) {
        binding.listTags.post {
            val position = tagAdapter.currentList.indexOfFirst {
                it.record.uidHex == uidHex
            }
            if (position >= 0) tagAdapter.notifyItemChanged(position)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "SLIder"
        private const val DISABLED_ALPHA = 0.45f
        private const val SWIPE_THRESHOLD = 0.35f
        private const val SWIPE_ICON_SIZE_DP = 24
        private const val SWIPE_CONTENT_MARGIN_DP = 16
        private const val SWIPE_ICON_TEXT_SPACING_DP = 8
        private const val NFC_FILE_MIME_TYPE = "application/octet-stream"
        private const val MESSAGE_BANNER_DURATION_MS = 3_500L
        private const val UNDO_BANNER_DURATION_MS = 5_000L
        private const val MESSAGE_BANNER_ANIMATION_MS = 180L
        private const val MESSAGE_BANNER_OFFSET_DP = 8
    }

    private data class MessageBannerStyle(
        val background: Int,
        val contentColor: Int,
        val durationMillis: Long?,
    )
}
