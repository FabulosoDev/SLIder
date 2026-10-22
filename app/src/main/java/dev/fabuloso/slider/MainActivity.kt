package dev.fabuloso.slider

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
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
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Toast
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
        configureTopAppBar()

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
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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

        binding.topAppBar.menu.findItem(R.id.actionDeleteAll)?.isEnabled =
            state.deleteAllButtonEnabled
    }

    private fun handleEffect(effect: MainUiEffect) {
        when (effect) {
            is MainUiEffect.LaunchSaveDocument -> createReadFile.launch(effect.suggestedName)
            is MainUiEffect.ShowMessage -> toast(effect.messageResource)
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
            confirmDeleteTag(record)
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
                toast(R.string.toast_share_failed)
            }
        }
    }

    private fun confirmDeleteTag(record: TagRecord) {
        if (!viewModel.uiState.value.canDelete(record.uidHex)) return
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
                    confirmDeleteTag(item.record)
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
    }
}
