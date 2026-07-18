package com.fitsync.app.web

import com.fitsync.app.domain.model.ValidationStatus
import com.fitsync.app.fit.FitParseResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GarminUploadFilePreparerTest {
    @Test
    fun validActivity_passesGarminPreflight() {
        val rejection = garminUploadRejection(
            fileSize = 1024,
            parsed = validActivity(recordCount = 8_000),
        )

        assertThat(rejection).isNull()
    }

    @Test
    fun crcWarning_isRejectedBeforeOpeningFileChooser() {
        val rejection = garminUploadRejection(
            fileSize = 1024,
            parsed = validActivity().copy(
                validationStatus = ValidationStatus.VALID_WITH_WARNINGS,
                warnings = listOf("文件 CRC 无效或缺失"),
            ),
        )

        assertThat(rejection?.code).isEqualTo("GARMIN_FIT_CRC_INVALID")
    }

    @Test
    fun officialPointLimit_isEnforced() {
        val rejection = garminUploadRejection(
            fileSize = 1024,
            parsed = validActivity(recordCount = 99_999),
        )

        assertThat(rejection?.code).isEqualTo("GARMIN_TOO_MANY_POINTS")
    }

    @Test
    fun missingSession_isRejected() {
        val rejection = garminUploadRejection(
            fileSize = 1024,
            parsed = validActivity().copy(hasSession = false),
        )

        assertThat(rejection?.code).isEqualTo("GARMIN_NOT_ACTIVITY")
    }

    @Test
    fun activityLink_isStrongSuccessEvidenceAndExtractsRemoteId() {
        val evidence = classifyGarminImportPage(
            pageUrl = "https://connect.garmin.cn/app/import-data",
            pageText = "Import complete",
            activityUrl = "https://connect.garmin.cn/modern/activity/23626050000",
        )

        assertThat(evidence.type).isEqualTo(GarminImportEvidenceType.SUCCESS)
        assertThat(evidence.remoteActivityId).isEqualTo("23626050000")
    }

    @Test
    fun duplicateMessage_isRecordedAsDuplicate() {
        val evidence = classifyGarminImportPage(
            pageUrl = "https://connect.garmin.cn/app/import-data",
            pageText = "This activity already exists in your account",
            activityUrl = null,
        )

        assertThat(evidence.type).isEqualTo(GarminImportEvidenceType.DUPLICATE)
    }

    @Test
    fun uploadError_isNotMistakenForSuccess() {
        val evidence = classifyGarminImportPage(
            pageUrl = "https://connect.garmin.cn/app/import-data",
            pageText = "An error occurred with your upload. Please try again.",
            activityUrl = null,
        )

        assertThat(evidence.type).isEqualTo(GarminImportEvidenceType.FAILURE)
    }

    private fun validActivity(recordCount: Int = 10): FitParseResult = FitParseResult(
        validationStatus = ValidationStatus.VALID,
        startTimeEpochMs = 1_700_000_000_000,
        hasSession = true,
        recordCount = recordCount,
        isActivityFile = true,
    )
}
