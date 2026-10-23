package dev.fabuloso.slider

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface TagFileExporter {
    suspend fun export(record: TagRecord, destination: Uri)
}

class ContentResolverTagFileExporter(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TagFileExporter {
    override suspend fun export(record: TagRecord, destination: Uri) = withContext(ioDispatcher) {
        val text = FlipperNfcSerializer.serialize(
            uid = record.uid,
            data = record.data,
            blockCount = record.blockCount,
            blockSize = record.blockSize,
        )
        val output = contentResolver.openOutputStream(destination)
            ?: error("Unable to open destination")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

class TagShareFileCreator(
    private val cacheDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun create(record: TagRecord): File = withContext(ioDispatcher) {
        val shareDirectory = File(cacheDirectory, SHARE_DIRECTORY).apply {
            check(isDirectory || mkdirs()) { "Unable to create share directory" }
        }
        deleteStaleFiles(shareDirectory)
        File(shareDirectory, TagFileNameFormatter.format(record)).apply {
            writeText(
                FlipperNfcSerializer.serialize(
                    uid = record.uid,
                    data = record.data,
                    blockCount = record.blockCount,
                    blockSize = record.blockSize,
                ),
                Charsets.UTF_8,
            )
        }
    }

    private fun deleteStaleFiles(shareDirectory: File) {
        val staleBefore = currentTimeMillis() - SHARED_FILE_MAX_AGE_MS
        shareDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.lastModified() < staleBefore }
            ?.forEach(File::delete)
    }

    companion object {
        const val SHARE_DIRECTORY = "shared_tags"
        const val SHARED_FILE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}

object TagFileNameFormatter {
    fun format(record: TagRecord): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(record.readAtEpochMillis))
        val uidPrefix = record.uid.toReversedHexCompact(byteCount = 4)
        return "${timestamp}_${uidPrefix}.nfc"
    }
}
