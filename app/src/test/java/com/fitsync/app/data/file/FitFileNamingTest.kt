package com.fitsync.app.data.file

import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class FitFileNamingTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun create_usesPlatformDatePeriodSportAndSixDigitRandomNumber() {
        val startTime = OffsetDateTime.parse("2026-07-17T10:15:00+08:00").toInstant().toEpochMilli()

        val name = FitFileNaming.create(
            platform = Platform.ONELAP,
            startTimeEpochMs = startTime,
            sportType = SportType.CYCLING,
            zoneId = zoneId,
            randomNumber = 38_421,
        )

        assertThat(name).isEqualTo("迈金_20260717_上午_骑行_038421.fit")
    }

    @Test
    fun create_mapsAllTimePeriodsAtTheirBoundaries() {
        assertThat(FitFileNaming.timePeriod(4)).isEqualTo("晚上")
        assertThat(FitFileNaming.timePeriod(5)).isEqualTo("上午")
        assertThat(FitFileNaming.timePeriod(12)).isEqualTo("下午")
        assertThat(FitFileNaming.timePeriod(18)).isEqualTo("傍晚")
        assertThat(FitFileNaming.timePeriod(20)).isEqualTo("晚上")
    }

    @Test
    fun create_mapsPlatformAndActivityTypeLabels() {
        val startTime = OffsetDateTime.parse("2026-07-17T18:00:00+08:00").toInstant().toEpochMilli()

        val name = FitFileNaming.create(
            platform = Platform.GARMIN,
            startTimeEpochMs = startTime,
            sportType = SportType.RUNNING,
            zoneId = zoneId,
            randomNumber = 7,
        )

        assertThat(name).isEqualTo("Garmin_20260717_傍晚_跑步_000007.fit")
    }
}
