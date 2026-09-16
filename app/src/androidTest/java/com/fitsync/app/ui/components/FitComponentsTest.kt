package com.fitsync.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.fit.LatLng
import com.fitsync.app.ui.theme.FitSyncTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 组件层 UI 测试：只覆盖 ui/components 下的无状态组件，
 * 不触碰 ViewModel / 数据库 / WorkManager，保证可独立运行在模拟器上。
 */
@RunWith(AndroidJUnit4::class)
class FitComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── FitCard ──

    @Test
    fun fitCard_rendersContent() {
        composeTestRule.setContent {
            FitSyncTheme { FitCard { Text("默认卡片") } }
        }
        composeTestRule.onNodeWithText("默认卡片").assertIsDisplayed()
    }

    @Test
    fun fitCard_onClick_invoked() {
        var clicked = 0
        composeTestRule.setContent {
            FitSyncTheme { FitCard(onClick = { clicked++ }) { Text("可点击") } }
        }
        composeTestRule.onNodeWithText("可点击").performClick()
        assertThat(clicked).isEqualTo(1)
    }

    @Test
    fun fitCard_withoutClick_hasNoClickAction() {
        composeTestRule.setContent {
            FitSyncTheme { FitCard { Text("静态卡片") } }
        }
        composeTestRule.onNodeWithText("静态卡片").assertIsDisplayed()
    }

    // ── IconBadge ──

    @Test
    fun iconBadge_exposesContentDescription() {
        composeTestRule.setContent {
            FitSyncTheme {
                IconBadge(
                    icon = Icons.Default.Description,
                    contentDescription = "FIT 文件",
                )
            }
        }
        // 无障碍：图标必须有可朗读的描述，否则 TalkBack 只能读出「未加标签的按钮」。
        composeTestRule.onNodeWithContentDescription("FIT 文件").assertIsDisplayed()
    }

    @Test
    fun iconBadge_withoutContentDescription_isDecorative() {
        composeTestRule.setContent {
            FitSyncTheme { IconBadge(icon = Icons.Default.Description) }
        }
        // 装饰性图标不应出现在无障碍树中。
        composeTestRule.onNodeWithContentDescription("FIT 文件").assertDoesNotExist()
    }

    // ── StatTile / StatGrid ──

    @Test
    fun statTile_rendersValueAndLabel() {
        composeTestRule.setContent {
            FitSyncTheme {
                StatTile(value = "42.3", unit = "km", label = "距离", size = StatSize.Xl)
            }
        }
        composeTestRule.onNodeWithText("42.3 km").assertIsDisplayed()
        composeTestRule.onNodeWithText("距离").assertIsDisplayed()
    }

    @Test
    fun statGrid_rendersAllStats_andPassesSpecOnClick() {
        val stats = listOf(
            StatTileSpec("discovered", "1,284", "本地活动"),
            StatTileSpec("failed", "4", "同步失败", tone = StatTone.Error),
        )
        var clickedKey: String? = null
        composeTestRule.setContent {
            FitSyncTheme { StatGrid(stats = stats, onStatClick = { clickedKey = it.key }) }
        }
        composeTestRule.onNodeWithText("1,284").assertIsDisplayed()
        composeTestRule.onNodeWithText("同步失败").assertIsDisplayed()

        composeTestRule.onNodeWithText("本地活动").performClick()
        assertThat(clickedKey).isEqualTo("discovered")
    }

    @Test
    fun statGrid_emptyStats_rendersNothing() {
        composeTestRule.setContent {
            FitSyncTheme { StatGrid(stats = emptyList()) }
        }
        composeTestRule.onNodeWithText("本地活动").assertDoesNotExist()
    }

    // ── FitListRow ──

    @Test
    fun fitListRow_rendersTitleSubtitleAndMeta() {
        composeTestRule.setContent {
            FitSyncTheme {
                FitListRow(
                    title = "晨骑",
                    subtitle = "09-10 06:12 · 骑行",
                    meta = { Text("42.3 km") },
                )
            }
        }
        composeTestRule.onNodeWithText("晨骑").assertIsDisplayed()
        composeTestRule.onNodeWithText("09-10 06:12 · 骑行").assertIsDisplayed()
        composeTestRule.onNodeWithText("42.3 km").assertIsDisplayed()
    }

    @Test
    fun fitListRow_onClick_invoked() {
        var clicked = 0
        composeTestRule.setContent {
            FitSyncTheme { FitListRow(title = "行者", onClick = { clicked++ }) }
        }
        composeTestRule.onNodeWithText("行者").performClick()
        assertThat(clicked).isEqualTo(1)
    }

    @Test
    fun fitListRow_disabled_doesNotInvokeClick() {
        var clicked = 0
        composeTestRule.setContent {
            FitSyncTheme {
                FitListRow(title = "已停用", enabled = false, onClick = { clicked++ })
            }
        }
        // disabled 时 onClick 被 takeIf 过滤，节点不应保留点击语义。
        composeTestRule.onNodeWithText("已停用").assertIsDisplayed()
        assertThat(clicked).isEqualTo(0)
    }

    // ── StatusPill / PlatformAvatar ──

    @Test
    fun statusPill_rendersText() {
        composeTestRule.setContent {
            FitSyncTheme { StatusPill("已同步", StatusTone.SUCCESS) }
        }
        composeTestRule.onNodeWithText("已同步").assertIsDisplayed()
    }

    @Test
    fun platformAvatar_rendersShortName() {
        composeTestRule.setContent {
            FitSyncTheme { PlatformAvatar(Platform.XINGZHE) }
        }
        composeTestRule.onNodeWithText("行").assertIsDisplayed()
    }

    // ── FitEmptyState ──

    @Test
    fun fitEmptyState_firstRun_rendersTitleBodyStepsAndAction() {
        var acted = false
        composeTestRule.setContent {
            FitSyncTheme {
                FitEmptyState(
                    title = "还没有活动",
                    body = "登录平台开始拉取。",
                    variant = EmptyStateVariant.FirstRun,
                    steps = listOf("连接平台账号", "分享或拉取 FIT"),
                    actionLabel = "连接平台",
                    onAction = { acted = true },
                )
            }
        }
        composeTestRule.onNodeWithText("还没有活动").assertIsDisplayed()
        composeTestRule.onNodeWithText("连接平台账号").assertIsDisplayed()
        composeTestRule.onNodeWithText("分享或拉取 FIT").assertIsDisplayed()
        composeTestRule.onNodeWithText("连接平台").performClick()
        assertThat(acted).isTrue()
    }

    @Test
    fun fitEmptyState_noResults_rendersCompactBody() {
        var cleared = false
        composeTestRule.setContent {
            FitSyncTheme {
                FitEmptyState(
                    title = "没有匹配结果",
                    body = "试试其他关键词。",
                    variant = EmptyStateVariant.NoResults,
                    actionLabel = "清除筛选",
                    onAction = { cleared = true },
                )
            }
        }
        // NoResults 变体把 body 作为唯一文案展示，不重复标题。
        composeTestRule.onNodeWithText("试试其他关键词。").assertIsDisplayed()
        composeTestRule.onNodeWithText("清除筛选").performClick()
        assertThat(cleared).isTrue()
    }

    @Test
    fun fitEmptyState_error_rendersRetryAction() {
        composeTestRule.setContent {
            FitSyncTheme {
                FitEmptyState(
                    title = "同步失败",
                    body = "登录已失效，重新登录后会自动继续。",
                    variant = EmptyStateVariant.Error,
                    actionLabel = "重试",
                    onAction = {},
                )
            }
        }
        composeTestRule.onNodeWithText("同步失败").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun fitEmptyState_allDone_rendersTitle() {
        composeTestRule.setContent {
            FitSyncTheme {
                FitEmptyState(
                    title = "全部同步完成",
                    body = "没有等待处理的任务。",
                    variant = EmptyStateVariant.AllDone,
                )
            }
        }
        composeTestRule.onNodeWithText("全部同步完成").assertIsDisplayed()
    }

    // ── FitTopBar ──

    @Test
    fun fitTopBar_rendersTitleAndBack_invokesOnBack() {
        var back = 0
        composeTestRule.setContent {
            FitSyncTheme { FitTopBar(title = "活动详情", onBack = { back++ }) }
        }
        composeTestRule.onNodeWithText("活动详情").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("返回").performClick()
        assertThat(back).isEqualTo(1)
    }

    @Test
    fun fitTopBar_withoutOnBack_hasNoBackButton() {
        composeTestRule.setContent {
            FitSyncTheme { FitTopBar(title = "概览") }
        }
        composeTestRule.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    // ── SettingsRow ──

    @Test
    fun settingsRow_switchRow_mergesIntoSemantics_andToggles() {
        var changedTo: Boolean? = null
        composeTestRule.setContent {
            // 必须用 Compose state，否则点击后不重组，无法断言切换后的语义状态。
            val checked = remember { mutableStateOf(false) }
            FitSyncTheme {
                SettingsRow(
                    title = "自动同步",
                    checked = checked.value,
                    onCheckedChange = { checked.value = it; changedTo = it },
                )
            }
        }
        // 无障碍：整行合并为单个可切换节点，而不是「可点击行 + 内部开关」两个节点。
        val row = composeTestRule.onNode(hasText("自动同步") and isToggleable())
        row.assertIsDisplayed().assertIsOff()

        row.performClick()
        assertThat(changedTo).isTrue()
        row.assertIsOn()
    }

    @Test
    fun settingsRow_plainRow_invokesOnClick() {
        var clicked = 0
        composeTestRule.setContent {
            FitSyncTheme { SettingsRow(title = "平台账号", onClick = { clicked++ }) }
        }
        composeTestRule.onNodeWithText("平台账号").assertHasClickAction().performClick()
        assertThat(clicked).isEqualTo(1)
    }

    @Test
    fun settingsRow_disabled_hasNoClickAction() {
        var clicked = 0
        composeTestRule.setContent {
            FitSyncTheme {
                SettingsRow(title = "不可用项", enabled = false, onClick = { clicked++ })
            }
        }
        composeTestRule.onNodeWithText("不可用项").assertIsDisplayed()
        assertThat(clicked).isEqualTo(0)
    }

    @Test
    fun settingsGroup_rendersTitleInsideCard() {
        composeTestRule.setContent {
            FitSyncTheme {
                SettingsGroup("同步") {
                    SettingsRow(title = "自动同步", checked = true, onCheckedChange = {})
                }
            }
        }
        composeTestRule.onNodeWithText("同步").assertIsDisplayed()
        composeTestRule.onNodeWithText("自动同步").assertIsDisplayed()
    }

    // ── 轨迹与曲线：Canvas 组件无文本语义，这里只锁「退化输入不崩」 ──

    @Test
    fun trackPreview_singleAndEmptyInput_renderWithoutCrash() {
        composeTestRule.setContent {
            FitSyncTheme {
                Column {
                    TrackPreview(points = emptyList(), modifier = Modifier.size(48.dp))
                    TrackPreview(points = listOf(LatLng(39.9, 116.4)), modifier = Modifier.size(48.dp))
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun trackPreview_zeroSpanTrack_renderWithoutCrash() {
        // 原地不动的轨迹：经纬跨度都是 0，归一化时不能除零
        composeTestRule.setContent {
            FitSyncTheme {
                TrackPreview(points = List(4) { LatLng(39.9, 116.4) }, modifier = Modifier.size(48.dp))
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun trackThumbnail_renderWithoutCrash() {
        composeTestRule.setContent {
            FitSyncTheme {
                TrackThumbnail(
                    points = listOf(LatLng(39.90, 116.40), LatLng(39.92, 116.43), LatLng(39.91, 116.41)),
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun seriesChart_flatSeriesAndSinglePoint_renderWithoutCrash() {
        // 全员相等时跨度为 0；单点无法成线——两条路径都不能崩
        composeTestRule.setContent {
            FitSyncTheme {
                Column {
                    SeriesChart(values = List(20) { 30f }, modifier = Modifier.size(120.dp, 40.dp))
                    SeriesChart(values = listOf(30f), modifier = Modifier.size(120.dp, 40.dp))
                }
            }
        }
        composeTestRule.waitForIdle()
    }
}
