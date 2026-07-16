package com.fitsync.app.fit

import com.fitsync.app.domain.model.SportType
import com.fitsync.app.domain.model.ValidationStatus

data class LatLng(
    val lat: Double,
    val lon: Double,
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
    val avgHeartRate: Int? = null,
    val avgPower: Int? = null,
    val avgCadence: Int? = null,
    val qualityScore: Int = 0,
    val isActivityFile: Boolean = false,
)
