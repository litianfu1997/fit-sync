package com.fitsync.app.data.repo

import android.net.Uri
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.ImportJobEntity
import com.fitsync.app.data.file.FitFileNaming
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.dedup.ActivityFingerprint
import com.fitsync.app.domain.dedup.DedupDecision
import com.fitsync.app.domain.dedup.DedupEngine
import com.fitsync.app.domain.model.CollectorType
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.ImportJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.domain.model.ValidationStatus
import com.fitsync.app.fit.FitEngine
import com.fitsync.app.fit.FitParseResult
import com.fitsync.app.fit.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ImportService(
    private val db: FitSyncDatabase,
    private val fileStore: FitFileStore,
    private val fitEngine: FitEngine = FitEngine(),
    private val dedupEngine: DedupEngine = DedupEngine(),
    private val mutationGate: ActivityMutationGate = ActivityMutationGate(),
) {

    suspend fun importFromUri(
        uri: Uri,
        displayName: String? = null,
        sourcePlatform: Platform? = null,
        collectorType: CollectorType = CollectorType.SHARE,
        remoteActivityId: String? = null,
        preferredTitle: String? = null,
    ): ImportOutcome = mutationGate.withLock {
        importFromUriLocked(uri, displayName, sourcePlatform, collectorType, remoteActivityId, preferredTitle)
    }

    private suspend fun importFromUriLocked(
        uri: Uri,
        displayName: String? = null,
        sourcePlatform: Platform? = null,
        collectorType: CollectorType = CollectorType.SHARE,
        remoteActivityId: String? = null,
        preferredTitle: String? = null,
    ): ImportOutcome {
        val importDao = db.importJobDao()
        val jobId = importDao.insert(
            ImportJobEntity(
                displayName = displayName,
                status = ImportJobStatus.COPYING,
            ),
        )

        return try {
            var stored = fileStore.copyFromUri(uri, displayName)
            importDao.update(
                importDao.getById(jobId)!!.copy(
                    status = ImportJobStatus.VALIDATING,
                    localFilePath = stored.file.absolutePath,
                    fileSha256 = stored.sha256,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )

            val existingVariant = db.activityVariantDao().findBySha256(stored.sha256)
            if (existingVariant != null) {
                importDao.update(
                    importDao.getById(jobId)!!.copy(
                        status = ImportJobStatus.DUPLICATE_FILE,
                        validationStatus = ValidationStatus.VALID,
                        canonicalActivityId = existingVariant.canonicalActivityId,
                        variantId = existingVariant.id,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                return ImportOutcome.DuplicateFile(jobId, existingVariant.canonicalActivityId, existingVariant.id)
            }

            val parsed = fitEngine.parseFile(stored.file)
            if (parsed.validationStatus in setOf(
                    ValidationStatus.UNSUPPORTED,
                    ValidationStatus.NOT_ACTIVITY,
                    ValidationStatus.TRUNCATED,
                    ValidationStatus.CRC_INVALID,
                ) && parsed.startTimeEpochMs == null
            ) {
                importDao.update(
                    importDao.getById(jobId)?.copy(
                        status = ImportJobStatus.INVALID,
                        validationStatus = parsed.validationStatus,
                        errorMessage = parsed.errorMessage,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ) ?: return ImportOutcome.Invalid(jobId, parsed.validationStatus, parsed.errorMessage),
                )
                deleteOrphanStoredFile(stored.file.absolutePath, stored.sha256)
                return ImportOutcome.Invalid(jobId, parsed.validationStatus, parsed.errorMessage)
            }

            val resolvedSportType = parsed.sportType.takeUnless { it == SportType.UNKNOWN }
                ?: SportType.CYCLING
            val start = parsed.startTimeEpochMs ?: System.currentTimeMillis()
            stored = fileStore.renameForActivity(
                storedFile = stored,
                platform = sourcePlatform ?: Platform.LOCAL_IMPORT,
                startTimeEpochMs = start,
                sportType = resolvedSportType,
            )

            importDao.update(
                importDao.getById(jobId)?.copy(
                    displayName = stored.displayName,
                    status = ImportJobStatus.DEDUPLICATING,
                    localFilePath = stored.file.absolutePath,
                    validationStatus = parsed.validationStatus,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ) ?: return ImportOutcome.Failed(jobId, "导入任务丢失"),
            )

            // 与 DedupEngine 时间窗一致，避免全表扫描
            val windowMs = DEDUP_TIME_WINDOW_MS
            val nearby = db.canonicalActivityDao().findByStartTimeRange(start - windowMs, start + windowMs)
            val candidates = dedupEngine.filterCandidates(
                all = nearby,
                startTimeEpochMs = start,
                distanceMeters = parsed.distanceMeters,
                sportType = parsed.sportType,
            )
            val candidatePairs = candidates.map { c ->
                val variants = db.activityVariantDao().findByCanonical(c.id)
                val track = variants.firstNotNullOfOrNull { parseTrack(it.trackSummaryJson) } ?: emptyList()
                c to track
            }
            val decision = dedupEngine.decide(parsed, candidatePairs)

            val canonicalId = when (decision.action) {
                DedupDecision.Action.CREATE_NEW -> {
                    db.canonicalActivityDao().insert(
                        CanonicalActivityEntity(
                            sportType = resolvedSportType,
                            startTimeEpochMs = start,
                            endTimeEpochMs = parsed.endTimeEpochMs,
                            elapsedSeconds = parsed.elapsedSeconds,
                            movingSeconds = parsed.movingSeconds,
                            distanceMeters = parsed.distanceMeters,
                            elevationGain = parsed.elevationGain,
                            avgHeartRate = parsed.avgHeartRate,
                            title = preferredTitle?.takeIf { it.isNotBlank() }
                                ?: FitFileNaming.displayTitle(start, resolvedSportType),
                            activityFingerprint = ActivityFingerprint.compute(parsed),
                            dedupStatus = DedupStatus.UNIQUE,
                        ),
                    )
                }
                DedupDecision.Action.AUTO_MERGE -> {
                    val id = decision.matchedCanonicalId!!
                    val existing = db.canonicalActivityDao().getById(id)!!
                    val merged = existing.withMissingMetricsFrom(parsed).copy(
                        dedupStatus = DedupStatus.AUTO_MERGED,
                        reviewCandidateCanonicalId = null,
                    )
                    // 合并后强制使用「日期 时段 活动类型」中性标题，不偏袒任一来源平台。
                    db.canonicalActivityDao().update(
                        merged.copy(title = FitFileNaming.displayTitle(merged.startTimeEpochMs, merged.sportType)),
                    )
                    id
                }
                DedupDecision.Action.NEEDS_REVIEW -> {
                    // 独立 canonical + 记录候选，供详情页人工合并/保持分离
                    val id = db.canonicalActivityDao().insert(
                        CanonicalActivityEntity(
                            sportType = resolvedSportType,
                            startTimeEpochMs = start,
                            endTimeEpochMs = parsed.endTimeEpochMs,
                            elapsedSeconds = parsed.elapsedSeconds,
                            movingSeconds = parsed.movingSeconds,
                            distanceMeters = parsed.distanceMeters,
                            elevationGain = parsed.elevationGain,
                            avgHeartRate = parsed.avgHeartRate,
                            title = preferredTitle?.takeIf { it.isNotBlank() }
                                ?: FitFileNaming.displayTitle(start, resolvedSportType),
                            activityFingerprint = ActivityFingerprint.compute(parsed),
                            dedupStatus = DedupStatus.NEEDS_REVIEW,
                            reviewCandidateCanonicalId = decision.matchedCanonicalId,
                        ),
                    )
                    id
                }
            }

            val variantId = db.activityVariantDao().insert(
                ActivityVariantEntity(
                    canonicalActivityId = canonicalId,
                    collectorType = collectorType,
                    sourcePlatform = sourcePlatform ?: Platform.LOCAL_IMPORT,
                    remoteActivityId = remoteActivityId,
                    localFilePath = stored.file.absolutePath,
                    fileSha256 = stored.sha256,
                    fileSize = stored.size,
                    recordingManufacturer = parsed.manufacturer,
                    recordingProduct = parsed.product,
                    deviceSerial = parsed.deviceSerial,
                    qualityScore = parsed.qualityScore,
                    validationStatus = parsed.validationStatus,
                    hasGps = parsed.hasGps,
                    hasHeartRate = parsed.hasHeartRate,
                    hasCadence = parsed.hasCadence,
                    hasPower = parsed.hasPower,
                    recordCount = parsed.recordCount,
                    trackSummaryJson = encodeTrack(parsed.trackSample),
                ),
            )

            val canonical = db.canonicalActivityDao().getById(canonicalId)!!
            if (canonical.primaryVariantId == null ||
                (canonical.primaryVariantId.let { db.activityVariantDao().getById(it)?.qualityScore ?: 0 } < parsed.qualityScore)
            ) {
                db.canonicalActivityDao().update(canonical.copy(primaryVariantId = variantId))
            }

            val finalStatus = when (decision.action) {
                DedupDecision.Action.NEEDS_REVIEW -> ImportJobStatus.NEEDS_REVIEW
                else -> ImportJobStatus.READY
            }
            importDao.update(
                importDao.getById(jobId)?.copy(
                    status = finalStatus,
                    canonicalActivityId = canonicalId,
                    variantId = variantId,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ) ?: return ImportOutcome.Failed(jobId, "导入任务丢失"),
            )

            ImportOutcome.Success(
                jobId = jobId,
                canonicalActivityId = canonicalId,
                variantId = variantId,
                dedupAction = decision.action,
                qualityScore = parsed.qualityScore,
                validationStatus = parsed.validationStatus,
            )
        } catch (t: Throwable) {
            importDao.getById(jobId)?.let { job ->
                importDao.update(
                    job.copy(
                        status = ImportJobStatus.INVALID,
                        errorMessage = t.message,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                job.localFilePath?.let { path ->
                    deleteOrphanStoredFile(path, job.fileSha256)
                }
            }
            ImportOutcome.Failed(jobId, t.message)
        }
    }

    private suspend fun deleteOrphanStoredFile(path: String, sha256: String?) {
        if (sha256 != null && db.activityVariantDao().findBySha256(sha256) != null) return
        fileStore.deleteStoredFiles(listOf(path))
    }

    suspend fun importFromFile(
        file: File,
        displayName: String = file.name,
        sourcePlatform: Platform,
        collectorType: CollectorType = CollectorType.CLOUD,
        remoteActivityId: String? = null,
        preferredTitle: String? = null,
    ): ImportOutcome = importFromUri(
        uri = Uri.fromFile(file),
        displayName = displayName,
        sourcePlatform = sourcePlatform,
        collectorType = collectorType,
        remoteActivityId = remoteActivityId,
        preferredTitle = preferredTitle,
    )

    suspend fun reparseStoredActivitiesIfNeeded() = mutationGate.withLock {
        reparseStoredActivitiesIfNeededLocked()
    }

    private suspend fun reparseStoredActivitiesIfNeededLocked() {
        val settingsDao = db.appSettingsDao()
        val settings = settingsDao.get() ?: AppSettingsEntity()
        if (settings.fitParserVersion >= CURRENT_PARSER_VERSION) return

        // 标题一次性回填：仅针对 v2 及更早的历史数据（fitParserVersion < TITLE_BACKFILL_GATE）。
        // 与解析器版本升级解耦——未来 FitEngine 改动递增 CURRENT_PARSER_VERSION 时不会重复触发，
        // 避免把此后导入活动已捕获的平台原名(preferredTitle)再次抹成中性 displayTitle 而造成不可逆丢失。
        // 用 canonical 持久的 startTime/sport 回填，不依赖 FIT 文件是否存在，保证所有历史活动都被覆盖。
        if (settings.fitParserVersion < TITLE_BACKFILL_GATE) {
            backfillDisplayTitlesLocked()
        }

        db.canonicalActivityDao().getAll().forEach { canonical ->
            val reparsed = db.activityVariantDao().findByCanonical(canonical.id).mapNotNull { variant ->
                val file = File(variant.localFilePath)
                if (!file.isFile) return@mapNotNull null
                val parsed = runCatching { fitEngine.parseFile(file) }.getOrNull() ?: return@mapNotNull null
                if (!parsed.isActivityFile) return@mapNotNull null

                db.activityVariantDao().update(
                    variant.copy(
                        recordingManufacturer = parsed.manufacturer,
                        recordingProduct = parsed.product,
                        deviceSerial = parsed.deviceSerial,
                        qualityScore = parsed.qualityScore,
                        validationStatus = parsed.validationStatus,
                        hasGps = parsed.hasGps,
                        hasHeartRate = parsed.hasHeartRate,
                        hasCadence = parsed.hasCadence,
                        hasPower = parsed.hasPower,
                        recordCount = parsed.recordCount,
                        trackSummaryJson = encodeTrack(parsed.trackSample),
                    ),
                )
                ReparsedVariant(variant.id, parsed)
            }
            val preferred = reparsed.maxByOrNull { it.result.qualityScore } ?: return@forEach
            val parsed = preferred.result
            val finalSportType = parsed.sportType.takeUnless { it == SportType.UNKNOWN } ?: canonical.sportType
            val finalStartTime = parsed.startTimeEpochMs ?: canonical.startTimeEpochMs
            db.canonicalActivityDao().update(
                canonical.copy(
                    sportType = finalSportType,
                    startTimeEpochMs = finalStartTime,
                    endTimeEpochMs = parsed.endTimeEpochMs ?: canonical.endTimeEpochMs,
                    elapsedSeconds = parsed.elapsedSeconds ?: canonical.elapsedSeconds,
                    movingSeconds = parsed.movingSeconds ?: canonical.movingSeconds,
                    distanceMeters = parsed.distanceMeters ?: canonical.distanceMeters,
                    elevationGain = parsed.elevationGain ?: canonical.elevationGain,
                    avgHeartRate = parsed.avgHeartRate ?: canonical.avgHeartRate,
                    activityFingerprint = if (parsed.startTimeEpochMs != null) {
                        ActivityFingerprint.compute(parsed)
                    } else {
                        canonical.activityFingerprint
                    },
                    primaryVariantId = preferred.variantId,
                ),
            )
        }

        settingsDao.upsert(settings.copy(fitParserVersion = CURRENT_PARSER_VERSION))
    }

    /**
     * 一次性把所有 canonical 的标题回填为「日期 时段 活动类型」。
     * 仅依赖 canonical 持久的 startTimeEpochMs/sportType（导入时必然写入），不读取 FIT 文件，
     * 因此即便某活动的 FIT 缺失/损坏也能回填，避免历史标题与 displayTitle 格式不一致或列表空白。
     */
    private suspend fun backfillDisplayTitlesLocked() {
        db.canonicalActivityDao().getAll().forEach { canonical ->
            db.canonicalActivityDao().update(
                canonical.copy(
                    title = FitFileNaming.displayTitle(canonical.startTimeEpochMs, canonical.sportType),
                ),
            )
        }
    }

    private fun encodeTrack(points: List<LatLng>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().put("lat", p.lat).put("lon", p.lon))
        }
        return arr.toString()
    }

    private fun parseTrack(json: String?): List<LatLng> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(LatLng(o.getDouble("lat"), o.getDouble("lon")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun CanonicalActivityEntity.withMissingMetricsFrom(parsed: FitParseResult) = copy(
        endTimeEpochMs = endTimeEpochMs ?: parsed.endTimeEpochMs,
        elapsedSeconds = elapsedSeconds ?: parsed.elapsedSeconds,
        movingSeconds = movingSeconds ?: parsed.movingSeconds,
        distanceMeters = distanceMeters ?: parsed.distanceMeters,
        elevationGain = elevationGain ?: parsed.elevationGain,
        avgHeartRate = avgHeartRate ?: parsed.avgHeartRate,
    )

    private data class ReparsedVariant(val variantId: Long, val result: FitParseResult)

    private companion object {
        const val CURRENT_PARSER_VERSION = 3

        // 标题回填闸门：仅对 fitParserVersion < 此值的历史数据回填 displayTitle。
        // 固定为本次命名重构引入时的 CURRENT_PARSER_VERSION(=3)，实现「v2→v3 一次性回填」语义；
        // 未来解析器版本继续递增(3→4→…)时，settings.fitParserVersion 已 ≥ 3，不再重复回填，
        // 从而保护此后新导入活动捕获的平台原名(preferredTitle)不被覆盖。
        const val TITLE_BACKFILL_GATE = 3

        /** 与 DedupEngine 候选时间窗一致（±10 分钟） */
        const val DEDUP_TIME_WINDOW_MS = 10L * 60 * 1000
    }
}

sealed class ImportOutcome {
    data class Success(
        val jobId: Long,
        val canonicalActivityId: Long,
        val variantId: Long,
        val dedupAction: DedupDecision.Action,
        val qualityScore: Int,
        val validationStatus: ValidationStatus,
    ) : ImportOutcome()

    data class DuplicateFile(
        val jobId: Long,
        val canonicalActivityId: Long,
        val variantId: Long,
    ) : ImportOutcome()

    data class Invalid(
        val jobId: Long,
        val validationStatus: ValidationStatus,
        val message: String?,
    ) : ImportOutcome()

    data class Failed(val jobId: Long, val message: String?) : ImportOutcome()
}
