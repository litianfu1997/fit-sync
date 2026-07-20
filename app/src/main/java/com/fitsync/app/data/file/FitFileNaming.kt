package com.fitsync.app.data.file

import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

object FitFileNaming {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val displayDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun create(
        platform: Platform,
        startTimeEpochMs: Long,
        sportType: SportType,
        zoneId: ZoneId = ZoneId.systemDefault(),
        randomNumber: Int = ThreadLocalRandom.current().nextInt(1_000_000),
    ): String {
        require(randomNumber in 0..999_999) { "随机数必须在 0 到 999999 之间" }
        val localDateTime = Instant.ofEpochMilli(startTimeEpochMs).atZone(zoneId)
        return listOf(
            platform.fileNameLabel(),
            dateFormatter.format(localDateTime),
            timePeriod(localDateTime.hour),
            sportType.fileNameLabel(),
            String.format(Locale.ROOT, "%06d", randomNumber),
        ).joinToString("_") + ".fit"
    }

    /**
     * 生成人类可读的活动展示标题，例如「2026-07-18 上午 骑行」。
     *
     * 用途：
     * - 单来源活动在拿不到平台列表名（preferredTitle 为空）时作为兜底标题；
     * - 多来源活动合并（AUTO_MERGE）后作为统一的中性标题，不偏袒任一平台。
     *
     * 与 [create] 不同：不含平台前缀、随机数、扩展名，适合直接作为 canonical.title 展示。
     */
    fun displayTitle(
        startTimeEpochMs: Long,
        sportType: SportType,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val localDateTime = Instant.ofEpochMilli(startTimeEpochMs).atZone(zoneId)
        return listOf(
            displayDateFormatter.format(localDateTime),
            timePeriod(localDateTime.hour),
            sportType.fileNameLabel(),
        ).joinToString(" ")
    }

    internal fun timePeriod(hour: Int): String = when (hour) {
        in 5..11 -> "上午"
        in 12..17 -> "下午"
        in 18..19 -> "傍晚"
        else -> "晚上"
    }

    private fun Platform.fileNameLabel(): String = when (this) {
        Platform.LOCAL_IMPORT -> "本地"
        Platform.ONELAP -> "迈金"
        Platform.XINGZHE -> "行者"
        Platform.GARMIN -> "Garmin"
        Platform.IGPSPORT -> "iGPSPORT"
        Platform.COROS -> "COROS"
        Platform.STRAVA -> "Strava"
        Platform.UNKNOWN -> "未知平台"
    }

    private fun SportType.fileNameLabel(): String = when (this) {
        SportType.CYCLING -> "骑行"
        SportType.INDOOR_CYCLING -> "室内骑行"
        SportType.MTB -> "山地骑行"
        SportType.GRAVEL -> "砾石骑行"
        SportType.RUNNING -> "跑步"
        SportType.OTHER -> "其他运动"
        SportType.UNKNOWN -> "未知活动"
    }
}
