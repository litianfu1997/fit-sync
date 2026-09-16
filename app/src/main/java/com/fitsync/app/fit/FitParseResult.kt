package com.fitsync.app.fit

import com.fitsync.app.domain.model.SportType
import com.fitsync.app.domain.model.ValidationStatus

data class LatLng(
    val lat: Double,
    val lon: Double,
)

/**
 * 逐点采样序列的一项：详情页用 [speedMps] / [altitudeMeters] 画曲线。
 * 解析时按固定步长抽稀（见 [FitEngine.PROFILE_MAX_SAMPLES]），不是全量记录流。
 */
data class ProfileSample(
    /** 相对活动开始的秒数 */
    val seconds: Int,
    val speedMps: Double? = null,
    val altitudeMeters: Double? = null,
)

data class FitParseResult(
    val validationStatus: ValidationStatus,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
    val sportType: SportType = SportType.UNKNOWN,
    val startTimeEpochMs: Long? = null,
    val endTimeEpochMs: Long? = null,
    val elapsedSeconds: Int? = null,
    val movingSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val elevationGain: Double? = null,
    val manufacturer: Int? = null,
    val product: Int? = null,
    val deviceSerial: String? = null,
    val hasGps: Boolean = false,
    val hasHeartRate: Boolean = false,
    val hasCadence: Boolean = false,
    val hasPower: Boolean = false,
    val hasLaps: Boolean = false,
    val hasSession: Boolean = false,
    val recordCount: Int = 0,
    val trackSample: List<LatLng> = emptyList(),
    /**
     * 地图用高密度轨迹（最多 [FitEngine.DENSE_TRACK_MAX_POINTS] 点），与 [trackSample] 独立。
     * 列表缩略图与去重仍只读 [trackSample]。
     */
    val denseTrackSample: List<LatLng> = emptyList(),
    val profileSamples: List<ProfileSample> = emptyList(),
    val avgHeartRate: Int? = null,
    val avgPower: Int? = null,
    val avgCadence: Int? = null,
    val qualityScore: Int = 0,
    val isActivityFile: Boolean = false,
)
