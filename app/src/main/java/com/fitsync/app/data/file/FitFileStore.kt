package com.fitsync.app.data.file

import android.content.Context
import android.net.Uri
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class FitFileStore(private val context: Context) {

    private val fitsDir: File
        get() = File(context.filesDir, "fits").also { it.mkdirs() }

    private val tempDir: File
        get() = File(context.cacheDir, "fit_import").also { it.mkdirs() }

    suspend fun copyFromUri(uri: Uri, preferredName: String?): StoredFitFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = preferredName
            ?: resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
            ?: "import_${System.currentTimeMillis()}.fit"

        val tempFile = File(tempDir, "tmp_${System.currentTimeMillis()}_${safeName(displayName)}")
        resolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法打开分享文件")

        finalizeStoredFile(tempFile, displayName)
    }

    suspend fun storeFromStream(
        input: InputStream,
        displayName: String,
        maxBytes: Long = 50L * 1024 * 1024,
    ): StoredFitFile =
        withContext(Dispatchers.IO) {
            val tempFile = File(tempDir, "tmp_${System.currentTimeMillis()}_${safeName(displayName)}")
            try {
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        require(total <= maxBytes) { "FIT 文件超过大小限制" }
                        output.write(buffer, 0, read)
                    }
                }
                require(isFitFile(tempFile)) { "下载内容不是有效的 FIT 文件" }
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
            finalizeStoredFile(tempFile, displayName)
        }

    suspend fun copyFromFile(file: File, displayName: String = file.name): StoredFitFile =
        file.inputStream().use { storeFromStream(it, displayName) }

    suspend fun renameForActivity(
        storedFile: StoredFitFile,
        platform: Platform,
        startTimeEpochMs: Long,
        sportType: SportType,
    ): StoredFitFile = withContext(Dispatchers.IO) {
        require(storedFile.file.parentFile?.canonicalFile == fitsDir.canonicalFile) {
            "只能重命名应用沙箱中的 FIT 文件"
        }

        repeat(MAX_NAME_ATTEMPTS) {
            val displayName = FitFileNaming.create(platform, startTimeEpochMs, sportType)
            val target = File(fitsDir, safeName(displayName))
            if (target.exists()) return@repeat

            if (!storedFile.file.renameTo(target)) {
                storedFile.file.copyTo(target, overwrite = false)
                check(storedFile.file.delete()) { "FIT 文件重命名后无法清理旧文件" }
            }
            return@withContext storedFile.copy(
                file = target,
                size = target.length(),
                displayName = target.name,
            )
        }
        error("无法生成不重复的 FIT 文件名")
    }

    fun resolve(path: String): File = File(path)

    fun cleanupTemporaryFiles() {
        tempDir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    private fun finalizeStoredFile(tempFile: File, displayName: String): StoredFitFile {
        val sha256 = sha256Of(tempFile)
        val target = File(fitsDir, "${sha256.take(16)}_${safeName(displayName)}")
        if (!target.exists()) {
            tempFile.copyTo(target, overwrite = false)
        }
        tempFile.delete()
        return StoredFitFile(
            file = target,
            sha256 = sha256,
            size = target.length(),
            displayName = displayName,
        )
    }

    companion object {
        private const val MAX_NAME_ATTEMPTS = 10

        private fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
                .take(120)
                .ifBlank { "activity.fit" }

        private fun isFitFile(file: File): Boolean {
            if (file.length() < 12) return false
            return file.inputStream().use { input ->
                val header = ByteArray(12)
                input.read(header) == header.size && String(header, 8, 4, Charsets.US_ASCII) == ".FIT"
            }
        }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class StoredFitFile(
    val file: File,
    val sha256: String,
    val size: Long,
    val displayName: String,
)
