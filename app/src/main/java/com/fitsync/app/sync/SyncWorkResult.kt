package com.fitsync.app.sync

import androidx.work.Data
import androidx.work.workDataOf

internal data class SyncWorkResult(
    val deliveryOnly: Boolean,
    val pulledActivities: Int,
    val queuedDeliveries: Int,
    val completedDeliveries: Int,
    val hasMoreDeliveries: Boolean,
    val checkedAccounts: Int,
    val enabledRules: Int,
    val actionRequiredAccounts: Int,
    val failedAccounts: Int,
) {
    fun userMessage(): String {
        if (deliveryOnly) {
            return when {
                completedDeliveries > 0 && hasMoreDeliveries ->
                    "已完成 $completedDeliveries 个投递任务，剩余任务继续在后台处理"
                completedDeliveries > 0 -> "后台投递完成：$completedDeliveries 个任务"
                hasMoreDeliveries -> "投递任务仍在后台处理中"
                else -> "投递检查完成，没有可执行任务"
            }
        }

        val changed = pulledActivities > 0 || queuedDeliveries > 0 || completedDeliveries > 0
        val result = if (changed) {
            "同步完成：拉取 $pulledActivities 条，新增 $queuedDeliveries 个任务，完成 $completedDeliveries 个"
        } else {
            "检查完成，未生成新的投递任务"
        }
        val notices = buildList {
            if (checkedAccounts == 0) add("未连接可同步的平台")
            if (actionRequiredAccounts > 0) add("$actionRequiredAccounts 个账号需要重新登录或处理")
            if (failedAccounts > actionRequiredAccounts) add("${failedAccounts - actionRequiredAccounts} 个账号检查失败")
            if (enabledRules == 0) add("尚未启用自动化规则")
        }
        return if (notices.isEmpty()) result else "$result；${notices.joinToString("；")}" 
    }
}

internal fun SyncRunSummary.toWorkData(deliveryOnly: Boolean): Data = workDataOf(
    KEY_RESULT_VERSION to RESULT_VERSION,
    KEY_DELIVERY_ONLY_RESULT to deliveryOnly,
    KEY_PULLED to pulledActivities,
    KEY_QUEUED to queuedDeliveries,
    KEY_COMPLETED to completedDeliveries,
    KEY_HAS_MORE to hasMoreDeliveries,
    KEY_CHECKED_ACCOUNTS to checkedAccounts,
    KEY_ENABLED_RULES to enabledRules,
    KEY_ACTION_REQUIRED_ACCOUNTS to actionRequiredAccounts,
    KEY_FAILED_ACCOUNTS to failedAccounts,
)

internal fun Data.toSyncWorkResult(): SyncWorkResult? {
    if (getInt(KEY_RESULT_VERSION, 0) != RESULT_VERSION) return null
    return SyncWorkResult(
        deliveryOnly = getBoolean(KEY_DELIVERY_ONLY_RESULT, false),
        pulledActivities = getInt(KEY_PULLED, 0),
        queuedDeliveries = getInt(KEY_QUEUED, 0),
        completedDeliveries = getInt(KEY_COMPLETED, 0),
        hasMoreDeliveries = getBoolean(KEY_HAS_MORE, false),
        checkedAccounts = getInt(KEY_CHECKED_ACCOUNTS, 0),
        enabledRules = getInt(KEY_ENABLED_RULES, 0),
        actionRequiredAccounts = getInt(KEY_ACTION_REQUIRED_ACCOUNTS, 0),
        failedAccounts = getInt(KEY_FAILED_ACCOUNTS, 0),
    )
}

internal fun syncFailureData(message: String): Data = workDataOf(KEY_ERROR_MESSAGE to message)
internal fun Data.syncErrorMessage(): String? = getString(KEY_ERROR_MESSAGE)

private const val RESULT_VERSION = 1
private const val KEY_RESULT_VERSION = "sync_result_version"
private const val KEY_DELIVERY_ONLY_RESULT = "sync_result_delivery_only"
private const val KEY_PULLED = "sync_result_pulled"
private const val KEY_QUEUED = "sync_result_queued"
private const val KEY_COMPLETED = "sync_result_completed"
private const val KEY_HAS_MORE = "sync_result_has_more"
private const val KEY_CHECKED_ACCOUNTS = "sync_result_checked_accounts"
private const val KEY_ENABLED_RULES = "sync_result_enabled_rules"
private const val KEY_ACTION_REQUIRED_ACCOUNTS = "sync_result_action_required_accounts"
private const val KEY_FAILED_ACCOUNTS = "sync_result_failed_accounts"
private const val KEY_ERROR_MESSAGE = "sync_error_message"
