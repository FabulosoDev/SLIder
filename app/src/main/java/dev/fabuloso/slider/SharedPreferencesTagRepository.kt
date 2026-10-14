package dev.fabuloso.slider

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("UseKtx")
class SharedPreferencesTagRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TagRepository {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow<List<TagRecord>>(emptyList())
    private var loaded = false

    override val records: StateFlow<List<TagRecord>> = mutableRecords.asStateFlow()

    override suspend fun load(): List<TagRecord> = withContext(ioDispatcher) {
        mutex.withLock { ensureLoadedLocked() }
    }

    override suspend fun add(record: TagRecord): AddTagResult = withContext(ioDispatcher) {
        mutex.withLock {
            val current = ensureLoadedLocked()
            if (current.any { it.uidHex == record.uidHex }) {
                return@withLock AddTagResult.DUPLICATE
            }

            val updated = current + record
            persistLocked(updated)
            mutableRecords.value = updated
            AddTagResult.ADDED
        }
    }

    override suspend fun remove(uidHex: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val current = ensureLoadedLocked()
            val updated = current.filterNot { it.uidHex == uidHex }
            if (updated.size == current.size) return@withLock false

            persistLocked(updated)
            mutableRecords.value = updated
            true
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            if (!preferences.edit().remove(KEY_RECORDS).commit()) {
                error("Unable to clear stored tags")
            }
            mutableRecords.value = emptyList()
        }
    }

    private fun ensureLoadedLocked(): List<TagRecord> {
        if (loaded) return mutableRecords.value

        val decoded = decodeStoredRecords()
        loaded = true
        mutableRecords.value = decoded.records

        if (decoded.needsRepair) {
            runCatching { persistLocked(decoded.records) }
                .onFailure { Log.w(TAG, "Could not repair the stored tag list", it) }
        }
        return decoded.records
    }

    private fun decodeStoredRecords(): DecodeResult {
        val raw = preferences.getString(KEY_RECORDS, null)
            ?: return DecodeResult(emptyList(), needsRepair = false)
        val records = mutableListOf<TagRecord>()
        var needsRepair = false

        try {
            val array = JSONArray(raw)
            val knownUids = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                try {
                    val json = array.getJSONObject(index)
                    if (!json.has(FIELD_READ_AT)) needsRepair = true
                    val record = decode(json)
                    if (knownUids.add(record.uidHex)) {
                        records += record
                    } else {
                        needsRepair = true
                    }
                } catch (error: Throwable) {
                    needsRepair = true
                    Log.w(TAG, "Ignoring malformed stored tag at index $index", error)
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Stored tag list is malformed; resetting it", error)
            needsRepair = true
        }

        return DecodeResult(records.toList(), needsRepair)
    }

    private fun persistLocked(records: List<TagRecord>) {
        val array = JSONArray()
        records.forEach { array.put(encode(it)) }
        if (!preferences.edit().putString(KEY_RECORDS, array.toString()).commit()) {
            error("Unable to persist stored tags")
        }
    }

    private fun encode(record: TagRecord) = JSONObject().apply {
        put(FIELD_UID, Base64.encodeToString(record.uid, Base64.NO_WRAP))
        put(FIELD_BLOCK_COUNT, record.blockCount)
        put(FIELD_BLOCK_SIZE, record.blockSize)
        put(FIELD_DATA, Base64.encodeToString(record.data, Base64.NO_WRAP))
        put(FIELD_READ_AT, record.readAtEpochMillis)
    }

    private fun decode(json: JSONObject) = TagRecord(
        uid = Base64.decode(json.getString(FIELD_UID), Base64.DEFAULT),
        blockCount = json.getInt(FIELD_BLOCK_COUNT),
        blockSize = json.getInt(FIELD_BLOCK_SIZE),
        data = Base64.decode(json.getString(FIELD_DATA), Base64.DEFAULT),
        readAtEpochMillis = json.optLong(FIELD_READ_AT, System.currentTimeMillis()),
    )

    private data class DecodeResult(
        val records: List<TagRecord>,
        val needsRepair: Boolean,
    )

    companion object {
        private const val TAG = "TagRepository"
        internal const val PREFERENCES_NAME = "stored_tags"
        internal const val KEY_RECORDS = "records"
        private const val FIELD_UID = "uid"
        private const val FIELD_BLOCK_COUNT = "blockCount"
        private const val FIELD_BLOCK_SIZE = "blockSize"
        private const val FIELD_DATA = "data"
        private const val FIELD_READ_AT = "readAt"
    }
}
