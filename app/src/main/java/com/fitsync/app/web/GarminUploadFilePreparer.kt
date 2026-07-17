package com.fitsync.app.web

import android.content.Context
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.ValidationStatus
import com.fitsync.app.fit.FitEngine
import com.fitsync.app.fit.FitParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

sealed interface GarminUploadPreparation {
    data class Ready(
        val file: File,
        val recordCount: Int,
    ) : GarminUploadPreparation

    data class Rejected(
        val code: String,
        val message: String,
    ) : GarminUploadPreparation
}

/**
 * 为 Garmin 网页导入准备一个标准 `.fit` 文件。
 *
 * 历史导入可能来自 ZIP，且 ZIP 在进入活动库后会被重命名为 `.fit`。这里按文件签名识别，
 * 必要时安全解压第一个 FIT，并统一输出 ASCII 文件名，避免网页上传器误判扩展名或编码。
 */
class GarminUploadFilePreparer(
    private val context: Context,
    private val fitEngine: FitEngine,
) {
    private val uploadDir: File
        get() = File(context.cacheDir, "garmin_upload").also(File::mkdirs)

    suspend fun prepare(
        jobId: Long,
        variant: ActivityVariantEntity,
    ): GarminUploadPreparation = withContext(Dispatchers.IO) {
        cleanup(jobId)
        cleanupStaleFiles()

        val source = runCatching { File(variant.localFilePath).canonicalFile }.getOrNull()
            ?: return@withContext rejected("GARMIN_FILE_MISSING", "找不到待上传的 FIT 文件")
        val fitsRoot = runCatching { File(context.filesDir, "fits").canonicalFile }.getOrNull()
            ?: return@withContext rejected("GARMIN_FILE_PATH_INVALID", "无法访问本地 FIT 目录")
        if (!source.isFile || !source.toPath().startsWith(fitsRoot.toPath())) {
            return@withContext rejected("GARMIN_FILE_PATH_INVALID", "待上传文件不在应用的 FIT 目录中")
        }
        if (FitFileStore.sha256Of(source) != variant.fileSha256) {
            return@withContext rejected("GARMIN_FILE_CHANGED", "本地 FIT 文件内容已变化，请重新导入")
        }

        val target = File(uploadDir, "FitSync_${jobId}_${variant.fileSha256.take(12)}.fit")
        val copied = runCatching {
            when {
                source.hasFitSignature() -> source.inputStream().use { input ->
                    target.outputStream().use { output -> copyLimited(input, output) }
                }
                source.hasSignature(ZIP_SIGNATURE) -> extractFitFromZip(source, target)
                else -> throw GarminPreparationException(
                    "GARMIN_NOT_FIT",
                    "文件内容不是 FIT，也不是包含 FIT 的 ZIP",
                )
            }
        }.fold(
            onSuccess = { null },
            onFailure = { error ->
                target.delete()
                when (error) {
                    is GarminPreparationException -> rejected(error.code, error.message.orEmpty())
                    else -> rejected("GARMIN_FILE_PREPARE_FAILED", error.message ?: "无法准备 Garmin 上传文件")
                }
            },
        )
        if (copied != null) return@withContext copied

        val parsed = runCatching { fitEngine.parseFile(target) }.getOrElse { error ->
            target.delete()
            return@withContext rejected(
                "GARMIN_FIT_PARSE_FAILED",
                error.message ?: "无法校验 Garmin 上传文件",
            )
        }
        garminUploadRejection(target.length(), parsed)?.let { failure ->
            target.delete()
            return@withContext failure
        }
        GarminUploadPreparation.Ready(target, parsed.recordCount)
    }

    fun cleanup(jobId: Long) {
        uploadDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("FitSync_${jobId}_") }
            ?.forEach(File::delete)
    }

    private fun cleanupStaleFiles() {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS
        uploadDir.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach(File::delete)
    }

    private fun extractFitFromZip(source: File, target: File) {
        ZipFile(source).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".fit", ignoreCase = true) }
                ?: throw GarminPreparationException("GARMIN_ZIP_NO_FIT", "ZIP 中没有 FIT 文件")
            if (entry.size > GARMIN_MAX_FIT_BYTES) {
                throw GarminPreparationException("GARMIN_FILE_TOO_LARGE", "Garmin 网页导入要求 FIT 不超过 25 MB")
            }
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> copyLimited(input, output) }
            }
        }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > GARMIN_MAX_FIT_BYTES) {
                throw GarminPreparationException("GARMIN_FILE_TOO_LARGE", "Garmin 网页导入要求 FIT 不超过 25 MB")
            }
            output.write(buffer, 0, read)
        }
    }
}

internal enum class GarminImportEvidenceType {
    SUCCESS,
    DUPLICATE,
    FAILURE,
    UNKNOWN,
}

internal data class GarminImportEvidence(
    val type: GarminImportEvidenceType,
    val remoteActivityId: String? = null,
    val remoteUrl: String? = null,
)

internal fun classifyGarminImportPage(
    pageUrl: String?,
    pageText: String?,
    activityUrl: String?,
): GarminImportEvidence {
    val trustedActivityUrl = sequenceOf(activityUrl, pageUrl)
        .filterNotNull()
        .mapNotNull { raw -> GARMIN_ACTIVITY_URL.matchEntire(raw.trim())?.let { raw.trim() to it.groupValues[1] } }
        .firstOrNull()
    val text = pageText.orEmpty().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val duplicate = DUPLICATE_MARKERS.any(text::contains)
    val failure = FAILURE_MARKERS.any(text::contains)
    val success = SUCCESS_MARKERS.any(text::contains)
    return when {
        duplicate -> GarminImportEvidence(
            GarminImportEvidenceType.DUPLICATE,
            trustedActivityUrl?.second,
            trustedActivityUrl?.first,
        )
        failure -> GarminImportEvidence(GarminImportEvidenceType.FAILURE)
        trustedActivityUrl != null || success -> GarminImportEvidence(
            GarminImportEvidenceType.SUCCESS,
            trustedActivityUrl?.second,
            trustedActivityUrl?.first,
        )
        else -> GarminImportEvidence(GarminImportEvidenceType.UNKNOWN)
    }
}

internal fun garminUploadRejection(
    fileSize: Long,
    parsed: FitParseResult,
): GarminUploadPreparation.Rejected? {
    if (fileSize <= 0) return rejected("GARMIN_FILE_EMPTY", "FIT 文件为空")
    if (fileSize > GARMIN_MAX_FIT_BYTES) {
        return rejected("GARMIN_FILE_TOO_LARGE", "Garmin 网页导入要求 FIT 不超过 25 MB")
    }
    if (parsed.validationStatus !in setOf(ValidationStatus.VALID, ValidationStatus.VALID_WITH_WARNINGS)) {
        return rejected("GARMIN_FIT_INVALID", parsed.errorMessage ?: "FIT 文件校验未通过")
    }
    if (parsed.warnings.any { warning ->
            warning.contains("CRC", ignoreCase = true) || warning.contains("截断")
        }
    ) {
        return rejected("GARMIN_FIT_CRC_INVALID", "FIT 文件 CRC 不完整，Garmin 可能拒绝导入，请换用原始文件")
    }
    if (!parsed.isActivityFile || !parsed.hasSession) {
        return rejected("GARMIN_NOT_ACTIVITY", "FIT 缺少 Garmin 导入所需的 Activity/Session 结构")
    }
    if (parsed.startTimeEpochMs == null) {
        return rejected("GARMIN_START_TIME_MISSING", "FIT 缺少活动开始时间")
    }
    if (parsed.recordCount >= GARMIN_MAX_DATA_POINTS) {
        return rejected("GARMIN_TOO_MANY_POINTS", "Garmin 网页导入要求数据点少于 99,999 个")
    }
    return null
}

private fun File.hasSignature(signature: ByteArray): Boolean = inputStream().use { input ->
    val header = ByteArray(signature.size)
    input.read(header) == signature.size && header.contentEquals(signature)
}

private fun File.hasFitSignature(): Boolean = inputStream().use { input ->
    val header = ByteArray(12)
    input.read(header) == header.size && String(header, 8, 4, Charsets.US_ASCII) == ".FIT"
}

private fun rejected(code: String, message: String) = GarminUploadPreparation.Rejected(code, message)

private class GarminPreparationException(val code: String, message: String) : Exception(message)

private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B)
private const val GARMIN_MAX_FIT_BYTES = 25L * 1024 * 1024
private const val GARMIN_MAX_DATA_POINTS = 99_999
private const val STALE_FILE_AGE_MS = 24L * 60 * 60 * 1000
private val GARMIN_ACTIVITY_URL = Regex("https://connect\\.garmin\\.cn/(?:modern/|app/)?activity/(\\d+)(?:[/?#].*)?")
private val DUPLICATE_MARKERS = listOf(
    "duplicate activity",
    "already exists",
    "activity already uploaded",
    "活动已存在",
    "重复活动",
    "已经上传",
)
private val FAILURE_MARKERS = listOf(
    "an error occurred with your upload",
    "failed to import",
    "file type not supported",
    "unsupported file",
    "导入失败",
    "上传失败",
    "不支持的文件",
)
private val SUCCESS_MARKERS = listOf(
    "import complete",
    "import successful",
    "successfully imported",
    "imported successfully",
    "activity imported",
    "activities imported",
    "导入完成",
    "导入成功",
    "上传成功",
)
