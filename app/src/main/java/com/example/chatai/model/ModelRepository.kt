package com.example.chatai.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Download / import progress. [bytes] and [total] are absolute byte counts. */
sealed class TransferProgress {
    data class Running(val bytes: Long, val total: Long) : TransferProgress() {
        val fraction: Float
            get() = if (total <= 0) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    data object Verifying : TransferProgress()
    data class Done(val file: File) : TransferProgress()
    data class Failed(val message: String, val cause: Throwable? = null) : TransferProgress()
}

class ModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "ModelRepository"
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 512 * 1024
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelsDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun fileFor(descriptor: ModelDescriptor): File = File(modelsDir(), descriptor.fileName)

    fun isInstalled(descriptor: ModelDescriptor): Boolean = fileFor(descriptor).isFile

    fun installedBytes(descriptor: ModelDescriptor): Long = fileFor(descriptor).let {
        if (it.isFile) it.length() else 0L
    }

    fun delete(descriptor: ModelDescriptor): Boolean {
        val file = fileFor(descriptor)
        val part = partFileFor(descriptor)
        var removed = false
        if (file.isFile) removed = file.delete() || removed
        if (part.isFile) removed = part.delete() || removed
        return removed
    }

    private fun partFileFor(descriptor: ModelDescriptor): File =
        File(modelsDir(), descriptor.fileName + PART_SUFFIX)

    /**
     * Downloads [descriptor], resuming from an existing `.part` file when the
     * server honours the Range request. Verifies SHA-256 before renaming.
     *
     * Implemented as a single flow: an inner flow that was collected from
     * inside this builder stalled the last few hundred KB because the nested
     * collector and the outer emit shared one dispatcher slot.
     */
    fun download(descriptor: ModelDescriptor): Flow<TransferProgress> = flow {
        val finalFile = fileFor(descriptor)

        if (finalFile.isFile && finalFile.length() == descriptor.sizeBytes) {
            emit(TransferProgress.Done(finalFile))
            return@flow
        }

        val urls = listOfNotNull(descriptor.url, descriptor.mirrorUrl)
        var lastError: Throwable? = null

        for ((index, url) in urls.withIndex()) {
            try {
                val partFile = partFileFor(descriptor)
                val existing = if (partFile.isFile) partFile.length() else 0L

                val request = Request.Builder()
                    .url(url)
                    .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }

                    val body = response.body ?: throw IOException("响应体为空")
                    val resuming = response.code == 206 && existing > 0
                    if (!resuming && partFile.exists()) {
                        partFile.delete()
                    }

                    val startBytes = if (resuming) existing else 0L
                    val totalBytes = if (resuming) {
                        descriptor.sizeBytes
                    } else {
                        body.contentLength().takeIf { it > 0 } ?: descriptor.sizeBytes
                    }

                    var written = startBytes
                    var lastReported = startBytes

                    body.byteStream().use { input ->
                        java.io.FileOutputStream(partFile, resuming).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
                                    lastReported = written
                                    emit(TransferProgress.Running(written, totalBytes))
                                }
                            }
                            output.flush()
                        }
                    }
                    emit(TransferProgress.Running(written, totalBytes))
                }

                emit(TransferProgress.Verifying)
                val actual = sha256(partFile)
                if (!actual.equals(descriptor.sha256, ignoreCase = true)) {
                    partFile.delete()
                    throw IOException(
                        "SHA-256 校验失败（期望 ${descriptor.sha256.take(12)}…，实际 ${actual.take(12)}…）"
                    )
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    throw IOException("无法重命名临时文件")
                }

                emit(TransferProgress.Done(finalFile))
                return@flow
            } catch (t: Throwable) {
                // A cancelled collector must not silently fall through to the mirror.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "下载失败（第 ${index + 1} 个源 $url）：${t.message}")
                lastError = t
            }
        }

        emit(TransferProgress.Failed("下载失败：${lastError?.message ?: "未知错误"}", lastError))
    }.flowOn(Dispatchers.IO)

    /**
     * Copies a user-picked `.gguf` into the models directory. A content:// URI is
     * not a real path, so the file must be materialised before llama.cpp can mmap it.
     */
    fun importFromUri(uri: Uri): Flow<TransferProgress> = flow {
        val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let {
            if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf"
        }
        val partFile = File(modelsDir(), "$safeName$PART_SUFFIX")
        val finalFile = File(modelsDir(), safeName)

        val total = querySize(uri)

        context.contentResolver.openInputStream(uri)?.use { stream ->
            java.io.FileOutputStream(partFile, false).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var lastReported = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
                        lastReported = written
                        emit(TransferProgress.Running(written, if (total > 0) total else written))
                    }
                }
                output.flush()
                emit(TransferProgress.Running(written, if (total > 0) total else written))
            }
        } ?: throw IOException("无法打开所选文件")

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            throw IOException("无法写入模型目录")
        }
        emit(TransferProgress.Done(finalFile))
    }.flowOn(Dispatchers.IO)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
