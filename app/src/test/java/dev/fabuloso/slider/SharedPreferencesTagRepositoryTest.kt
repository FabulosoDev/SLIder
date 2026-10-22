package dev.fabuloso.slider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesTagRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `repository persists deduplicates removes and clears records`() = runTest {
        val repository = SharedPreferencesTagRepository(context, Dispatchers.Unconfined)
        val first = record(uidLastByte = 1)
        val second = record(uidLastByte = 2)

        assertEquals(emptyList<TagRecord>(), repository.load())
        assertEquals(AddTagResult.ADDED, repository.add(first))
        assertEquals(AddTagResult.DUPLICATE, repository.add(first))
        assertEquals(AddTagResult.ADDED, repository.add(second))
        assertTrue(repository.remove(first.uidHex))
        assertFalse(repository.remove(first.uidHex))

        val reloaded = SharedPreferencesTagRepository(context, Dispatchers.Unconfined).load()
        assertEquals(listOf(second), reloaded)

        repository.clear()
        assertTrue(repository.records.value.isEmpty())
        assertFalse(preferences().contains(SharedPreferencesTagRepository.KEY_RECORDS))
    }

    @Test
    fun `malformed storage is repaired without failing load`() = runTest {
        preferences().edit()
            .putString(SharedPreferencesTagRepository.KEY_RECORDS, "not-json")
            .commit()
        val repository = SharedPreferencesTagRepository(context, Dispatchers.Unconfined)

        assertTrue(repository.load().isEmpty())
        assertEquals("[]", preferences().getString(SharedPreferencesTagRepository.KEY_RECORDS, null))
    }

    @Test
    fun `concurrent additions are atomic`() = runTest {
        val repository = SharedPreferencesTagRepository(context, Dispatchers.IO)
        val sameRecord = record(uidLastByte = 7)

        val results = List(20) { async { repository.add(sameRecord) } }.awaitAll()

        assertEquals(1, results.count { it == AddTagResult.ADDED })
        assertEquals(19, results.count { it == AddTagResult.DUPLICATE })
        assertEquals(1, repository.records.value.size)
    }

    @Test
    fun `restore returns a deleted record to its original position`() = runTest {
        val repository = SharedPreferencesTagRepository(context, Dispatchers.Unconfined)
        val first = record(uidLastByte = 1)
        val second = record(uidLastByte = 2)
        val third = record(uidLastByte = 3)
        repository.add(first)
        repository.add(second)
        repository.add(third)
        repository.remove(second.uidHex)

        assertEquals(AddTagResult.ADDED, repository.restore(second, 1))
        assertEquals(AddTagResult.DUPLICATE, repository.restore(second, 1))
        assertEquals(listOf(first, second, third), repository.records.value)

        val reloaded = SharedPreferencesTagRepository(context, Dispatchers.Unconfined).load()
        assertEquals(listOf(first, second, third), reloaded)
    }

    private fun preferences() = context.getSharedPreferences(
        SharedPreferencesTagRepository.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private fun record(uidLastByte: Int) = TagRecord(
        uid = ByteArray(8).apply { this[lastIndex] = uidLastByte.toByte() },
        blockCount = 1,
        blockSize = 4,
        data = byteArrayOf(1, 2, 3, 4),
        readAtEpochMillis = uidLastByte.toLong(),
    )
}
