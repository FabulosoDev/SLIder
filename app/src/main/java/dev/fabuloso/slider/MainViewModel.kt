package dev.fabuloso.slider

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val tags: List<TagRecord> = emptyList(),
    val isLoading: Boolean = true,
    val activeScanUid: String? = null,
    val queuedScanCount: Int = 0,
    val isMutating: Boolean = false,
    val mutatingUid: String? = null,
    val isClearing: Boolean = false,
    val pendingExportUid: String? = null,
    val isExporting: Boolean = false,
    val recentTagHighlight: RecentTagHighlight? = null,
) {
    val scansBusy: Boolean
        get() = activeScanUid != null || queuedScanCount > 0

    val canDeleteAll: Boolean
        get() = tags.isNotEmpty() &&
            !isLoading &&
            !scansBusy &&
            !isMutating &&
            pendingExportUid == null

    val deleteAllButtonEnabled: Boolean
        get() = tags.isNotEmpty() &&
            !isLoading &&
            !isMutating &&
            pendingExportUid == null

    fun canSave(uidHex: String): Boolean =
        !isLoading &&
            pendingExportUid == null &&
            !isClearing &&
            mutatingUid != uidHex

    fun canDelete(uidHex: String): Boolean =
        !isLoading &&
            !scansBusy &&
            !isMutating &&
            pendingExportUid != uidHex

}

data class RecentTagHighlight(val uidHex: String, val startedAtUptimeMillis: Long)

sealed interface MainUiEffect {
    data class ShowMessage(val messageResource: Int) : MainUiEffect

    data class LaunchSaveDocument(val suggestedName: String) : MainUiEffect
}

class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val repository: TagRepository,
    private val tagReader: NfcTagReader,
    private val fileExporter: TagFileExporter,
) : AndroidViewModel(application) {
    constructor(
        application: Application,
        savedStateHandle: SavedStateHandle,
    ) : this(
        application = application,
        savedStateHandle = savedStateHandle,
        repository = SharedPreferencesTagRepository(application),
        tagReader = DefaultNfcTagReader(),
        fileExporter = ContentResolverTagFileExporter(application.contentResolver),
    )

    private val mutableUiState = MutableStateFlow(
        MainUiState(pendingExportUid = savedStateHandle[STATE_PENDING_EXPORT_UID]),
    )
    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<MainUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private val scanQueue = Channel<NfcTagHandle>(Channel.UNLIMITED)
    private val initializationComplete = CompletableDeferred<Unit>()

    init {
        observeRepository()
        loadStoredTags()
        consumeScanQueue()
    }

    fun enqueueTag(tag: NfcTagHandle) {
        mutableUiState.update { state ->
            state.copy(queuedScanCount = state.queuedScanCount + 1)
        }
        if (scanQueue.trySend(tag).isFailure) {
            mutableUiState.update { state ->
                state.copy(queuedScanCount = (state.queuedScanCount - 1).coerceAtLeast(0))
            }
        }
    }

    fun reportUnsupportedTag() {
        showMessage(R.string.toast_tag_unsupported)
    }

    fun requestExport(uidHex: String) {
        val state = mutableUiState.value
        val record = state.tags.firstOrNull { it.uidHex == uidHex }
        if (record == null) {
            showMessage(R.string.toast_save_missing)
            return
        }
        if (!state.canSave(uidHex)) return

        setPendingExport(uidHex)
        viewModelScope.launch {
            effectChannel.send(MainUiEffect.LaunchSaveDocument(TagFileNameFormatter.format(record)))
        }
    }

    fun onExportDestinationSelected(destination: Uri?) {
        val uidHex = mutableUiState.value.pendingExportUid ?: return
        if (destination == null) {
            setPendingExport(null)
            showMessage(R.string.toast_save_canceled)
            return
        }

        mutableUiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            initializationComplete.await()
            val record = mutableUiState.value.tags.firstOrNull { it.uidHex == uidHex }
            if (record == null) {
                Log.w(TAG, "Save destination returned without a matching tag record")
                showMessage(R.string.toast_save_missing)
                finishExport()
                return@launch
            }

            try {
                fileExporter.export(record, destination)
                Log.i(TAG, "Saved tag ${record.uidHex} to $destination")
                showMessage(R.string.toast_save_success)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Could not save tag ${record.uidHex}", error)
                showMessage(R.string.toast_save_failed)
            } finally {
                finishExport()
            }
        }
    }

    fun deleteTag(uidHex: String) {
        val state = mutableUiState.value
        if (!state.canDelete(uidHex)) return

        mutableUiState.update {
            it.copy(isMutating = true, mutatingUid = uidHex, isClearing = false)
        }
        viewModelScope.launch {
            try {
                if (repository.remove(uidHex)) showMessage(R.string.toast_tag_deleted)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Could not delete tag $uidHex", error)
                showMessage(R.string.toast_storage_failed)
            } finally {
                mutableUiState.update {
                    it.copy(isMutating = false, mutatingUid = null, isClearing = false)
                }
            }
        }
    }

    fun deleteAllTags() {
        val state = mutableUiState.value
        if (!state.canDeleteAll) return

        mutableUiState.update {
            it.copy(isMutating = true, mutatingUid = null, isClearing = true)
        }
        viewModelScope.launch {
            try {
                repository.clear()
                showMessage(R.string.toast_all_deleted)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Could not delete all tags", error)
                showMessage(R.string.toast_storage_failed)
            } finally {
                mutableUiState.update {
                    it.copy(isMutating = false, mutatingUid = null, isClearing = false)
                }
            }
        }
    }

    private fun observeRepository() {
        viewModelScope.launch {
            repository.records.collect { records ->
                mutableUiState.update { it.copy(tags = records) }
            }
        }
    }

    private fun loadStoredTags() {
        viewModelScope.launch {
            try {
                repository.load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Could not load the stored tag list", error)
                showMessage(R.string.toast_storage_failed)
            } finally {
                mutableUiState.update { it.copy(isLoading = false) }
                initializationComplete.complete(Unit)
            }
        }
    }

    private fun consumeScanQueue() {
        viewModelScope.launch {
            for (tag in scanQueue) {
                val uidHex = tag.uidMsb.toHexCompact()
                mutableUiState.update { state ->
                    state.copy(
                        activeScanUid = uidHex,
                        queuedScanCount = (state.queuedScanCount - 1).coerceAtLeast(0),
                    )
                }
                try {
                    initializationComplete.await()
                    readAndStoreTag(tag, uidHex)
                } finally {
                    mutableUiState.update { it.copy(activeScanUid = null) }
                }
            }
        }
    }

    private suspend fun readAndStoreTag(tag: NfcTagHandle, uidHex: String) {
        Log.i(TAG, "Reading UID $uidHex")
        val record = try {
            tagReader.read(tag) { Log.d(TAG, it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Tag read failed", error)
            showMessage(R.string.toast_read_failed)
            return
        }

        try {
            when (repository.add(record)) {
                AddTagResult.ADDED -> {
                    Log.i(TAG, "Added tag ${record.uidHex}")
                    mutableUiState.update {
                        it.copy(
                            tags = repository.records.value,
                            recentTagHighlight = RecentTagHighlight(
                                record.uidHex,
                                SystemClock.uptimeMillis(),
                            ),
                        )
                    }
                    showMessage(R.string.toast_tag_added)
                }

                AddTagResult.DUPLICATE -> {
                    Log.i(TAG, "Ignoring duplicate tag ${record.uidHex}")
                    showMessage(R.string.toast_tag_duplicate)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Could not persist tag ${record.uidHex}", error)
            showMessage(R.string.toast_storage_failed)
        }
    }

    private fun setPendingExport(uidHex: String?) {
        savedStateHandle[STATE_PENDING_EXPORT_UID] = uidHex
        mutableUiState.update { it.copy(pendingExportUid = uidHex) }
    }

    private fun finishExport() {
        savedStateHandle[STATE_PENDING_EXPORT_UID] = null
        mutableUiState.update { it.copy(pendingExportUid = null, isExporting = false) }
    }

    private fun showMessage(messageResource: Int) {
        viewModelScope.launch {
            effectChannel.send(MainUiEffect.ShowMessage(messageResource))
        }
    }

    override fun onCleared() {
        scanQueue.close()
        effectChannel.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "SLIder"
        private const val STATE_PENDING_EXPORT_UID = "pendingExportUid"
    }

}
