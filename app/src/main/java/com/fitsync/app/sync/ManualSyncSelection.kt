package com.fitsync.app.sync

import androidx.work.Data
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType

/**
 * 首页手动同步的明确范围。它只影响本次任务，不会修改长期自动化规则。
 */
data class ManualSyncSelection(
    val sourcePlatform: Platform,
    val destinationAccountId: Long,
    val sportTypes: Set<SportType>,
) {
    init {
        require(sourcePlatform != Platform.UNKNOWN) { "请选择有效的活动来源" }
        require(destinationAccountId > 0) { "请选择目标平台" }
        require(sportTypes.isNotEmpty()) { "请至少选择一种活动类型" }
    }
}

internal fun Data.Builder.putManualSyncSelection(selection: ManualSyncSelection): Data.Builder = apply {
    putBoolean(KEY_MANUAL_SCOPED, true)
    putString(KEY_MANUAL_SOURCE, selection.sourcePlatform.name)
    putLong(KEY_MANUAL_DESTINATION, selection.destinationAccountId)
    putStringArray(KEY_MANUAL_SPORT_TYPES, selection.sportTypes.map { it.name }.toTypedArray())
}

internal fun Data.toManualSyncSelection(): ManualSyncSelection? {
    if (!getBoolean(KEY_MANUAL_SCOPED, false)) return null
    val sourceName = requireNotNull(getString(KEY_MANUAL_SOURCE)) { "手动同步缺少活动来源" }
    val destinationAccountId = getLong(KEY_MANUAL_DESTINATION, -1L)
    val sportTypes = getStringArray(KEY_MANUAL_SPORT_TYPES)
        ?.map(SportType::valueOf)
        ?.toSet()
        .orEmpty()
    return ManualSyncSelection(
        sourcePlatform = Platform.valueOf(sourceName),
        destinationAccountId = destinationAccountId,
        sportTypes = sportTypes,
    )
}

private const val KEY_MANUAL_SCOPED = "manual_sync_scoped"
private const val KEY_MANUAL_SOURCE = "manual_sync_source"
private const val KEY_MANUAL_DESTINATION = "manual_sync_destination"
private const val KEY_MANUAL_SPORT_TYPES = "manual_sync_sport_types"
