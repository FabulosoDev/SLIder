package dev.fabuloso.slider

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class StoredTagRecord(
    val uid: ByteArray,
    val blockCount: Int,
    val blockSize: Int,
    val data: ByteArray,
) {
    val uidHex: String
        get() = uid.toHexCompact()

    companion object {
        fun from(data: FlipperNfcData) = StoredTagRecord(
            uid = data.uid.copyOf(),
            blockCount = data.blockCount,
            blockSize = data.blockSize,
            data = data.data.copyOf(),
        )
    }
}

@SuppressLint("UseKtx")
class StoredTagStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<StoredTagRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        val records = mutableListOf<StoredTagRecord>()
        var needsRepair = false

        try {
            val array = JSONArray(raw)
            val knownUids = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                try {
                    val record = decode(array.getJSONObject(index))
                    if (knownUids.add(record.uidHex)) {
                        records += record
                    } else {
                        needsRepair = true
                    }
                } catch (t: Throwable) {
                    needsRepair = true
                    Log.w(TAG, "Ignoring malformed stored tag at index $index", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Stored tag list is malformed; resetting it", t)
            needsRepair = true
        }

        if (needsRepair) {
            try {
                persist(records)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not repair the stored tag list", t)
            }
        }
        return records
    }

    fun add(record: StoredTagRecord): Boolean {
        validate(record)
        val records = load().toMutableList()
        if (records.any { it.uidHex == record.uidHex }) return false
        records += record
        persist(records)
        return true
    }

    fun remove(uidHex: String): Boolean {
        val records = load().toMutableList()
        val removed = records.removeAll { it.uidHex == uidHex }
        if (removed) persist(records)
        return removed
    }

    fun clear() {
        if (!preferences.edit().remove(KEY_RECORDS).commit()) {
            error("Unable to clear stored tags")
        }
    }

    private fun persist(records: List<StoredTagRecord>) {
        val array = JSONArray()
        records.forEach { array.put(encode(it)) }
        if (!preferences.edit().putString(KEY_RECORDS, array.toString()).commit()) {
            error("Unable to persist stored tags")
        }
    }

    private fun encode(record: StoredTagRecord) = JSONObject().apply {
        put(FIELD_UID, Base64.encodeToString(record.uid, Base64.NO_WRAP))
        put(FIELD_BLOCK_COUNT, record.blockCount)
        put(FIELD_BLOCK_SIZE, record.blockSize)
        put(FIELD_DATA, Base64.encodeToString(record.data, Base64.NO_WRAP))
    }

    private fun decode(json: JSONObject): StoredTagRecord {
        val record = StoredTagRecord(
            uid = Base64.decode(json.getString(FIELD_UID), Base64.DEFAULT),
            blockCount = json.getInt(FIELD_BLOCK_COUNT),
            blockSize = json.getInt(FIELD_BLOCK_SIZE),
            data = Base64.decode(json.getString(FIELD_DATA), Base64.DEFAULT),
        )
        validate(record)
        return record
    }

    private fun validate(record: StoredTagRecord) {
        require(record.uid.size == 8) { "UID must be 8 bytes" }
        require(record.blockCount in 1..256) { "Invalid block count" }
        require(record.blockSize in 1..0x20) { "Invalid block size" }
        require(record.data.size == record.blockCount * record.blockSize) {
            "Tag data length does not match its block geometry"
        }
    }

    companion object {
        private const val TAG = "StoredTagStore"
        private const val PREFERENCES_NAME = "stored_tags"
        private const val KEY_RECORDS = "records"
        private const val FIELD_UID = "uid"
        private const val FIELD_BLOCK_COUNT = "blockCount"
        private const val FIELD_BLOCK_SIZE = "blockSize"
        private const val FIELD_DATA = "data"
    }
}
