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
