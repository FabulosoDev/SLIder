package dev.fabuloso.slider

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scan queue is FIFO and duplicate persistence is isolated`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val reader = FakeReader()
        val viewModel = viewModel(repository, reader)
        advanceUntilIdle()

        viewModel.enqueueTag(FakeHandle(1))
        viewModel.enqueueTag(FakeHandle(2))
        advanceUntilIdle()
        val highlightAfterAdd = viewModel.uiState.value.recentTagHighlight
        assertEquals(viewModel.uiState.value.tags.last().uidHex, highlightAfterAdd?.uidHex)

        viewModel.enqueueTag(FakeHandle(1))
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 1), reader.readOrder)
        assertEquals(2, viewModel.uiState.value.tags.size)
        assertNull(viewModel.uiState.value.activeScanUid)
        assertEquals(0, viewModel.uiState.value.queuedScanCount)
        assertEquals(highlightAfterAdd, viewModel.uiState.value.recentTagHighlight)
    }

    @Test
    fun `failed scan does not stop the next queued scan`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val reader = FakeReader(failUid = 1)
        val viewModel = viewModel(repository, reader)
        advanceUntilIdle()

        viewModel.enqueueTag(FakeHandle(1))
        viewModel.enqueueTag(FakeHandle(2))
        advanceUntilIdle()

        assertEquals(listOf(1, 2), reader.readOrder)
        assertEquals(listOf(2), viewModel.uiState.value.tags.map { it.uid.last().toInt() })
    }

    @Test
    fun `export retains pending uid and clears it after write`() = runTest(dispatcher) {
        val record = record(4)
        val repository = FakeRepository(listOf(record))
        val exporter = FakeExporter()
        val savedState = SavedStateHandle()
        val viewModel = MainViewModel(
            application,
            savedState,
            repository,
            FakeReader(),
            exporter,
        )
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.recentTagHighlight)

        viewModel.requestExport(record.uidHex)
        advanceUntilIdle()
        assertEquals(record.uidHex, viewModel.uiState.value.pendingExportUid)
        assertFalse(viewModel.uiState.value.canSave(record.uidHex))

        val destination = Uri.parse("content://test/tag.nfc")
        viewModel.onExportDestinationSelected(destination)
        advanceUntilIdle()

        assertEquals(listOf(record.uidHex to destination), exporter.exports)
        assertNull(viewModel.uiState.value.pendingExportUid)
        assertFalse(viewModel.uiState.value.isExporting)
    }

    @Test
    fun `ui state guards only conflicting operations`() {
        val first = record(1)
        val second = record(2)
        val exporting = MainUiState(
            tags = listOf(first, second),
            isLoading = false,
            pendingExportUid = first.uidHex,
        )

        assertFalse(exporting.canDelete(first.uidHex))
        assertTrue(exporting.canDelete(second.uidHex))
        assertFalse(exporting.canDeleteAll)

        val scanning = exporting.copy(pendingExportUid = null, activeScanUid = "active")
        assertFalse(scanning.canDelete(first.uidHex))
        assertTrue(scanning.deleteAllButtonEnabled)
        assertTrue(scanning.canSave(first.uidHex))
    }

    @Test
    fun `deleted tag can be restored to its original position`() = runTest(dispatcher) {
        val first = record(1)
        val second = record(2)
        val third = record(3)
        val repository = FakeRepository(listOf(first, second, third))
        val viewModel = viewModel(repository, FakeReader())
        advanceUntilIdle()

        val deletionEffect = async { viewModel.effects.first() }
        viewModel.deleteTag(second.uidHex)
        advanceUntilIdle()

        val deleted = deletionEffect.await() as MainUiEffect.TagDeleted
        assertEquals(second, deleted.record)
        assertEquals(1, deleted.originalIndex)
        assertEquals(listOf(first, third), viewModel.uiState.value.tags)

        val restoredEffect = async { viewModel.effects.first() }
        viewModel.undoDelete(deleted.record, deleted.originalIndex)
        advanceUntilIdle()

        assertEquals(listOf(first, second, third), viewModel.uiState.value.tags)
        assertEquals(
            R.string.toast_tag_restored,
            (restoredEffect.await() as MainUiEffect.ShowMessage).messageResource,
        )
    }

    private fun viewModel(
        repository: FakeRepository,
        reader: FakeReader,
        exporter: FakeExporter = FakeExporter(),
    ) = MainViewModel(
        application,
        SavedStateHandle(),
        repository,
        reader,
        exporter,
    )

    private class FakeHandle(val id: Int) : NfcTagHandle {
        override val uidMsb: ByteArray = ByteArray(8).apply { this[lastIndex] = id.toByte() }
        override fun openTransport(): NfcTransport = error("Fake reader does not use transport")
    }

    private class FakeReader(private val failUid: Int? = null) : NfcTagReader {
        val readOrder = mutableListOf<Int>()

        override suspend fun read(tag: NfcTagHandle, onProgress: (String) -> Unit): TagRecord {
            val id = tag.uidMsb.last().toInt()
            readOrder += id
            if (id == failUid) error("Read failed")
            return record(id)
        }
    }

    private class FakeExporter : TagFileExporter {
        val exports = mutableListOf<Pair<String, Uri>>()
        override suspend fun export(record: TagRecord, destination: Uri) {
            exports += record.uidHex to destination
        }
    }

    private class FakeRepository(initial: List<TagRecord> = emptyList()) : TagRepository {
        private val mutableRecords = MutableStateFlow(initial)
        override val records: StateFlow<List<TagRecord>> = mutableRecords

        override suspend fun load(): List<TagRecord> = mutableRecords.value

        override suspend fun add(record: TagRecord): AddTagResult {
            if (mutableRecords.value.any { it.uidHex == record.uidHex }) return AddTagResult.DUPLICATE
            mutableRecords.value = mutableRecords.value + record
            return AddTagResult.ADDED
        }

        override suspend fun remove(uidHex: String): Boolean {
            val updated = mutableRecords.value.filterNot { it.uidHex == uidHex }
            if (updated.size == mutableRecords.value.size) return false
            mutableRecords.value = updated
            return true
        }

        override suspend fun restore(record: TagRecord, index: Int): AddTagResult {
            if (mutableRecords.value.any { it.uidHex == record.uidHex }) {
                return AddTagResult.DUPLICATE
            }
            mutableRecords.value = mutableRecords.value.toMutableList().apply {
                add(index.coerceIn(0, size), record)
            }
            return AddTagResult.ADDED
        }

        override suspend fun clear() {
            mutableRecords.value = emptyList()
        }
    }

    companion object {
        private fun record(id: Int) = TagRecord(
            uid = ByteArray(8).apply { this[lastIndex] = id.toByte() },
            blockCount = 1,
            blockSize = 4,
            data = byteArrayOf(1, 2, 3, 4),
            readAtEpochMillis = id.toLong(),
        )
    }
}
