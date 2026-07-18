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

    @Test
    fun displayTitle_上午骑行() {
        val start = OffsetDateTime.parse("2026-07-18T08:30:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.CYCLING, zoneId))
            .isEqualTo("2026-07-18 上午 骑行")
    }

    @Test
    fun displayTitle_下午骑行() {
        val start = OffsetDateTime.parse("2026-07-18T14:00:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.CYCLING, zoneId))
            .isEqualTo("2026-07-18 下午 骑行")
    }

    @Test
    fun displayTitle_傍晚骑行() {
        val start = OffsetDateTime.parse("2026-07-18T18:30:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.CYCLING, zoneId))
            .isEqualTo("2026-07-18 傍晚 骑行")
    }

    @Test
    fun displayTitle_晚上骑行() {
        val start = OffsetDateTime.parse("2026-07-18T22:00:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.CYCLING, zoneId))
            .isEqualTo("2026-07-18 晚上 骑行")
    }

    @Test
    fun displayTitle_凌晨归为晚上() {
        // timePeriod: 5-11 上午 / 12-17 下午 / 18-19 傍晚 / 其余晚上；0-4 点属「晚上」。
        val start = OffsetDateTime.parse("2026-07-18T03:00:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.CYCLING, zoneId))
            .isEqualTo("2026-07-18 晚上 骑行")
    }

    @Test
    fun displayTitle_室内骑行与跑步与未知() {
        val start = OffsetDateTime.parse("2026-07-18T08:00:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.INDOOR_CYCLING, zoneId))
            .isEqualTo("2026-07-18 上午 室内骑行")
        assertThat(FitFileNaming.displayTitle(start, SportType.RUNNING, zoneId))
            .isEqualTo("2026-07-18 上午 跑步")
        assertThat(FitFileNaming.displayTitle(start, SportType.UNKNOWN, zoneId))
            .isEqualTo("2026-07-18 上午 未知活动")
    }

    @Test
    fun displayTitle_月日补零() {
        val start = OffsetDateTime.parse("2026-01-05T08:00:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.CYCLING, zoneId))
            .isEqualTo("2026-01-05 上午 骑行")
    }

    @Test
    fun displayTitle_时区影响日期与时段() {
        // 上海 2026-07-18 08:30 == UTC 2026-07-18 00:30；用 UTC 渲染应为 07-18 晚上。
        val start = OffsetDateTime.parse("2026-07-18T08:30:00+08:00").toInstant().toEpochMilli()
        assertThat(FitFileNaming.displayTitle(start, SportType.CYCLING, ZoneId.of("UTC")))
            .isEqualTo("2026-07-18 晚上 骑行")
    }
}
