package com.fitsync.app.fit

import com.fitsync.app.domain.model.ValidationStatus

object FitQualityScorer {

    fun score(result: FitParseResult, crcOk: Boolean, looksReexported: Boolean): Int {
        var score = 0
        if (crcOk) score += 20
        if (result.hasSession) score += 10
        if (result.recordCount >= 10) score += 5
        if (result.hasGps) score += 10
        if (result.hasHeartRate) score += 10
        if (result.hasCadence) score += 10
        if (result.hasPower) score += 10
        if (result.hasLaps) score += 5
        if (result.manufacturer != null) score += 5
        if (result.startTimeEpochMs != null && result.elapsedSeconds != null) score += 10
        if (!looksReexported) score += 5
        if (result.validationStatus == ValidationStatus.VALID_WITH_WARNINGS) {
            score = (score * 0.9).toInt()
        }
        return score.coerceIn(0, 100)
    }
}
