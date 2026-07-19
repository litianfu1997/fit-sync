package com.fitsync.app.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncWorkResultTest {
    @Test
    fun emptyRun_explainsMissingRulesAndExpiredAccount() {
        val message = SyncWorkResult(
            deliveryOnly = false,
            pulledActivities = 0,
            queuedDeliveries = 0,
            completedDeliveries = 0,
            hasMoreDeliveries = false,
            checkedAccounts = 1,
            enabledRules = 0,
            actionRequiredAccounts = 1,
            failedAccounts = 1,
        ).userMessage()

        assertThat(message).contains("未生成新的投递任务")
        assertThat(message).contains("1 个账号需要重新登录或处理")
        assertThat(message).contains("尚未启用自动化规则")
    }

    @Test
    fun successfulRun_reportsAllCounts() {
        val message = SyncWorkResult(
            deliveryOnly = false,
            pulledActivities = 2,
            queuedDeliveries = 3,
            completedDeliveries = 1,
            hasMoreDeliveries = false,
            checkedAccounts = 2,
            enabledRules = 2,
            actionRequiredAccounts = 0,
            failedAccounts = 0,
        ).userMessage()

        assertThat(message).isEqualTo("同步完成：拉取 2 条，新增 3 个任务，完成 1 个")
    }

    @Test
    fun workData_roundTripsSummary() {
        val data = SyncRunSummary(
            pulledActivities = 4,
            queuedDeliveries = 3,
            completedDeliveries = 2,
            hasMoreDeliveries = true,
            checkedAccounts = 5,
            enabledRules = 4,
            actionRequiredAccounts = 1,
            failedAccounts = 1,
        ).toWorkData(deliveryOnly = false)

        assertThat(data.toSyncWorkResult()).isEqualTo(
            SyncWorkResult(false, 4, 3, 2, true, 5, 4, 1, 1),
        )
    }
}
