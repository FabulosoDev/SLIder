package dev.fabuloso.slider

import kotlinx.coroutines.flow.StateFlow

interface TagRepository {
    val records: StateFlow<List<TagRecord>>

    suspend fun load(): List<TagRecord>

    suspend fun add(record: TagRecord): AddTagResult

    suspend fun remove(uidHex: String): Boolean

    suspend fun clear()
}

enum class AddTagResult {
    ADDED,
    DUPLICATE,
}
