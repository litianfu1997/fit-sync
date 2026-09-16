package com.fitsync.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.data.repo.ActivityDeletionResult
import com.fitsync.app.data.repo.MergeResult
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.FitCard
import com.fitsync.app.ui.components.FitCardTone
import com.fitsync.app.ui.components.FitListRow
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.HeroStatStrip
import com.fitsync.app.ui.components.IconBadge
import com.fitsync.app.ui.components.IconBadgeSize
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.SectionHeader
import com.fitsync.app.ui.components.StatGrid
import com.fitsync.app.ui.components.StatTileSpec
import com.fitsync.app.ui.components.SeriesChart
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.TrackMap
import com.fitsync.app.ui.components.deliveryStatusText
import com.fitsync.app.ui.components.deliveryStatusTone
import com.fitsync.app.ui.components.LocalAppSnackbar
import com.fitsync.app.fit.TrackCodec
import com.fitsync.app.ui.theme.FitGradients
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType
import com.fitsync.app.ui.theme.fitHero
import com.fitsync.app.web.PlatformWebActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityDetailScreen(activityId: Long, onBack: () -> Unit, onOpenMap: () -> Unit) {
    val app = FitSyncApp.instance
    val activity by app.database.canonicalActivityDao().observeById(activityId)
        .collectAsStateWithLifecycle(initialValue = null)
    val variants by app.database.activityVariantDao().observeByCanonical(activityId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val copies by app.database.platformCopyDao().observeByCanonical(activityId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val jobs by app.database.deliveryJobDao().observeByCanonical(activityId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.database.platformAccountDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showMergeConfirmation by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var isMerging by remember { mutableStateOf(false) }
    var backRequested by remember { mutableStateOf(false) }
    var reviewCandidate by remember { mutableStateOf<CanonicalActivityEntity?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appSnackbar = LocalAppSnackbar.current
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }
    val shortDateFormat = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }
    val navigateBackOnce = {
        if (!backRequested) {
            backRequested = true
            onBack()
        }
    }

    fun notify(message: String) {
        val host = appSnackbar
        if (host != null) {
            scope.launch { host.showSnackbar(message) }
        } else {
            actionMessage = message
        }
    }

    LaunchedEffect(activity?.reviewCandidateCanonicalId, activity?.dedupStatus) {
        val candidateId = activity?.reviewCandidateCanonicalId
        reviewCandidate = if (
            activity?.dedupStatus == DedupStatus.NEEDS_REVIEW && candidateId != null
        ) {
            withContext(Dispatchers.IO) { app.database.canonicalActivityDao().getById(candidateId) }
        } else {
            null
        }
    }

    var hasObservedActivity by remember { mutableStateOf(false) }

    LaunchedEffect(activity) {
        if (activity != null) {
            hasObservedActivity = true
        } else if (hasObservedActivity && !isDeleting && !isMerging) {
            navigateBackOnce()
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirmation = false },
            title = { Text("删除本地活动？") },
            text = {
                Text("将删除全部本地 FIT、活动记录、平台副本映射和同步历史，并取消未完成任务。各平台已有活动不会被删除；已发出的上传可能已被平台接收，之后同步也可能重新下载此活动。")
            },
            confirmButton = {
                Button(
                    enabled = !isDeleting,
                    onClick = {
                        isDeleting = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { app.activityDeletionService.delete(activityId) }
                            withContext(Dispatchers.Main) {
                                result.onSuccess { deletion ->
                                    showDeleteConfirmation = false
                                    when (deletion) {
                                        ActivityDeletionResult.AlreadyDeleted -> navigateBackOnce()
                                        is ActivityDeletionResult.Deleted -> {
                                            if (deletion.failedPaths.isNotEmpty()) {
                                                notify("活动已删除，但有 ${deletion.failedPaths.size} 个残留文件未能清理")
                                            }
                                            navigateBackOnce()
                                        }
                                    }
                                }.onFailure {
                                    isDeleting = false
                                    actionMessage = "删除失败：${it.message ?: "请稍后重试"}"
                                }
                            }
                        }
                    },
                ) { Text(if (isDeleting) "正在删除…" else "确认删除") }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = { showDeleteConfirmation = false }) { Text("取消") }
            },
        )
    }

    if (showMergeConfirmation) {
        val candidate = reviewCandidate
        AlertDialog(
            onDismissRequest = { if (!isMerging) showMergeConfirmation = false },
            title = { Text("合并到候选活动？") },
            text = {
                Text(
                    if (candidate != null) {
                        "将把本活动的 FIT 副本与平台映射合并到「${candidate.title ?: "活动 #${candidate.id}"}」，并删除本条记录。此操作不可撤销。"
                    } else {
                        "候选活动已不存在，无法合并。"
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !isMerging && candidate != null,
                    onClick = {
                        isMerging = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { app.activityMergeService.mergeIntoCandidate(activityId) }
                            withContext(Dispatchers.Main) {
                                showMergeConfirmation = false
                                result.onSuccess { merge ->
                                    when (merge) {
                                        is MergeResult.Merged -> {
                                            notify("已合并，迁移 ${merge.movedVariants} 个副本")
                                            navigateBackOnce()
                                        }
                                        MergeResult.CandidateMissing ->
                                            actionMessage = "候选活动已不存在"
                                        MergeResult.NoCandidate ->
                                            actionMessage = "没有可合并的候选"
                                        MergeResult.NotFound, MergeResult.InvalidState ->
                                            actionMessage = "当前状态无法合并"
                                    }
                                }.onFailure {
                                    actionMessage = "合并失败：${it.message ?: "请稍后重试"}"
                                }
                                isMerging = false
                            }
                        }
                    },
                ) { Text(if (isMerging) "合并中…" else "确认合并") }
            },
            dismissButton = {
                TextButton(enabled = !isMerging, onClick = { showMergeConfirmation = false }) { Text("取消") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        FitTopBar(
            title = "活动详情",
            subtitle = activity?.let { dateFormat.format(Date(it.startTimeEpochMs)) },
            onBack = navigateBackOnce,
        )
        val distributionAccounts = accounts.filter { it.status != AccountStatus.DISABLED }
        val problemJobs = jobs.filter { it.status in problemDeliveryStatuses }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FitSpacing.screenGutter,
                top = FitSpacing.sm,
                end = FitSpacing.screenGutter,
                bottom = FitSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            activity?.let { current ->
                item(key = "hero") { ActivityHero(current) }
                if (current.dedupStatus == DedupStatus.NEEDS_REVIEW) {
                    item(key = "review") {
                        ReviewActionsCard(
                            candidate = reviewCandidate,
                            dateFormat = dateFormat,
                            busy = isMerging,
                            onMerge = { showMergeConfirmation = true },
                            onKeepSeparate = {
                                isMerging = true
                                scope.launch(Dispatchers.IO) {
                                    val ok = runCatching {
                                        app.activityMergeService.keepSeparate(activityId)
                                    }.getOrDefault(false)
                                    withContext(Dispatchers.Main) {
                                        isMerging = false
                                        if (ok) {
                                            notify("已保持分离")
                                            navigateBackOnce()
                                        } else {
                                            actionMessage = "操作失败"
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                item(key = "stats") { ActivityStatPanel(current, variants) }
                item(key = "track") {
                    ActivityTrackCard(variants, current.primaryVariantId, onOpenMap)
                }
            }
            item(key = "distribution-header") {
                SectionHeader("分发", "同一活动在各平台中的记录")
            }
            if (distributionAccounts.isEmpty()) {
                item(key = "distribution-empty") {
                    FitCard(tone = FitCardTone.Muted, contentPadding = PaddingValues(FitSpacing.md)) {
                        Text(
                            "还没有可用的平台账号",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(distributionAccounts, key = { "distribution-${it.id}" }) { account ->
                    DistributionRow(
                        account = account,
                        copy = copies.firstOrNull { it.platformAccountId == account.id },
                        job = jobs.firstOrNull { it.destinationAccountId == account.id },
                        dateFormat = shortDateFormat,
                        onOpenWeb = { job ->
                            context.startActivity(
                                PlatformWebActivity.createIntent(
                                    context,
                                    account.platform,
                                    job.id,
                                    job.userActionUrl,
                                ),
                            )
                        },
                        onRetry = { job ->
                            scope.launch(Dispatchers.IO) {
                                val result = runCatching {
                                    app.syncCoordinator.retryJob(job.id)
                                    SyncScheduler.enqueueNow(context)
                                }
                                withContext(Dispatchers.Main) {
                                    result.fold(
                                        onSuccess = { notify("已重新加入同步队列") },
                                        onFailure = { actionMessage = "重试失败：${it.message ?: "请稍后重试"}" },
                                    )
                                }
                            }
                        },
                        onDispatch = {
                            scope.launch(Dispatchers.IO) {
                                val result = runCatching {
                                    val id = app.syncCoordinator.enqueueManual(activityId, account.id)
                                    SyncScheduler.enqueueNow(context)
                                    id
                                }
                                withContext(Dispatchers.Main) {
                                    result.fold(
                                        onSuccess = { id ->
                                            if (id > 0) {
                                                notify("已加入 ${account.platform.displayName} 队列")
                                            } else {
                                                actionMessage = "该平台已有副本或任务"
                                            }
                                        },
                                        onFailure = { actionMessage = "补发失败：${it.message ?: "请稍后重试"}" },
                                    )
                                }
                            }
                        },
                    )
                }
            }
            item(key = "variants-header") { SectionHeader("原始 FIT", "原始文件与采集来源") }
            item(key = "variants") {
                VariantsCard(variants, activity?.primaryVariantId)
            }
            if (problemJobs.isNotEmpty()) {
                item(key = "jobs-header") {
                    SectionHeader("投递记录", "仅显示需要关注的异常任务")
                }
                items(problemJobs, key = { "job-${it.id}" }) { job ->
                    val account = accounts.firstOrNull { it.id == job.destinationAccountId }
                    JobProblemRow(job = job, account = account)
                }
            }
            actionMessage?.let { message ->
                item(key = "action-message") {
                    StatusPill(message, StatusTone.ERROR)
                }
            }
            item(key = "delete") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        enabled = !isDeleting,
                        onClick = { showDeleteConfirmation = true },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(FitSpacing.iconSm),
                        )
                        Text(
                            "删除本地活动",
                            modifier = Modifier.padding(start = FitSpacing.sm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** Hero：标题行 + 单一主数字（距离）+ 一行次级数据。 */
@Composable
private fun ActivityHero(activity: CanonicalActivityEntity) {
    val dateFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()) }
    val distance = activity.distanceMeters?.let { "%.1f".format(it / 1000) } ?: "--"
    val speed = activity.distanceMeters?.let { distanceMeters ->
        activity.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
            "%.1f".format(distanceMeters / 1000.0 / (seconds / 3600.0))
        }
    }
    CompositionLocalProvider(LocalContentColor provides FitGradients.onHeroPrimary) {
        Column(Modifier.fillMaxWidth().fitHero().padding(FitSpacing.xl)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(FitShape.control)
                        .background(FitGradients.heroSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        sportIcon(activity.sportType),
                        contentDescription = null,
                        modifier = Modifier.size(FitSpacing.iconMd),
                    )
                }
                Text(
                    "${activitySummaryTitle(activity)} · ${dateFormat.format(Date(activity.startTimeEpochMs))}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(
                modifier = Modifier.padding(top = FitSpacing.lg),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
            ) {
                Text(
                    distance,
                    style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                )
                Text(
                    "km",
                    modifier = Modifier.padding(bottom = FitSpacing.xs),
                    style = MaterialTheme.typography.titleMedium,
                    color = FitGradients.onHeroSecondary,
                )
            }
            HeroStatStrip(
                stats = listOfNotNull(
                    activity.elapsedSeconds?.let {
                        StatTileSpec("duration", formatDuration(it), "时长")
                    },
                    speed?.let { StatTileSpec("speed", it, "均速", unit = "km/h") },
                    activity.elevationGain?.let {
                        StatTileSpec("elevation", "%.0f".format(it), "爬升", unit = "m")
                    },
                ),
                modifier = Modifier.padding(top = FitSpacing.md),
            )
        }
    }
}

/** 数据面板：距离 / 时长 / 均速 / 爬升 / 均心率 / 数据点。 */
/**
 * 轨迹与曲线：地图轨迹 + 速度 / 海拔变化。
 * 轨迹优先用高密度地图轨迹（解析器 v8 起写入），老数据回退 21 点摘要；
 * 两者都没有则整块不显示。点击地图进入全屏。
 */
@Composable
private fun ActivityTrackCard(
    variants: List<ActivityVariantEntity>,
    primaryVariantId: Long?,
    onOpenMap: () -> Unit,
) {
    val primary = variants.firstOrNull { it.id == primaryVariantId } ?: variants.firstOrNull()
    val denseTrack = primary?.denseTrackBase64?.takeIf(String::isNotBlank)
        ?: variants.firstNotNullOfOrNull { it.denseTrackBase64?.takeIf(String::isNotBlank) }
    val trackJson = primary?.trackSummaryJson
    val profileJson = primary?.profileJson
    val track = remember(denseTrack, trackJson) { TrackCodec.decodeBestTrack(denseTrack, trackJson) }
    val profile = remember(profileJson) { TrackCodec.decodeProfile(profileJson) }
    val speed = remember(profile) { profile.mapNotNull { it.speedMps }.map { it.toFloat() } }
    val altitude = remember(profile) { profile.mapNotNull { it.altitudeMeters }.map { it.toFloat() } }
    if (track.size < 2 && speed.size < 2 && altitude.size < 2) return
    val colors = MaterialTheme.colorScheme

    FitCard(contentPadding = PaddingValues(FitSpacing.md)) {
        if (track.size >= 2) {
            TrackMap(
                points = track,
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(FitShape.control),
                interactive = false,
                onTap = onOpenMap,
            )
        }
        if (speed.size >= 2) {
            ChartBlock(
                label = "速度",
                values = speed,
                unit = "km/h",
                scale = 3.6f,
                color = colors.tertiary,
                modifier = Modifier.padding(top = if (track.size >= 2) FitSpacing.md else 0.dp),
            )
        }
        if (altitude.size >= 2) {
            ChartBlock(
                label = "海拔",
                values = altitude,
                unit = "m",
                scale = 1f,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = FitSpacing.md),
            )
        }
    }
}

@Composable
private fun ChartBlock(
    label: String,
    values: List<Float>,
    unit: String,
    scale: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = FitType.dataLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                "最大 %.1f %s".format(values.max() * scale, unit),
                style = FitType.numericSm,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SeriesChart(
            values = values,
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = FitSpacing.xs),
            color = color,
        )
    }
}

@Composable
private fun ActivityStatPanel(activity: CanonicalActivityEntity, variants: List<ActivityVariantEntity>) {
    val speed = activity.distanceMeters?.let { distanceMeters ->
        activity.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
            "%.1f".format(distanceMeters / 1000.0 / (seconds / 3600.0))
        }
    }
    // 合并后的多个副本记录的是同一次骑行，取主副本的数据点，避免重复计数。
    val dataPoints = (
        variants.firstOrNull { it.id == activity.primaryVariantId }?.recordCount
            ?: variants.mapNotNull { it.recordCount }.maxOrNull()
        )?.takeIf { it > 0 }
    val tiles = listOf(
        StatTileSpec("distance", activity.distanceMeters?.let { "%.1f".format(it / 1000) } ?: "--", "距离", unit = "km"),
        StatTileSpec("duration", activity.elapsedSeconds?.let(::formatDuration) ?: "--", "时长"),
        StatTileSpec("speed", speed ?: "--", "均速", unit = "km/h"),
        StatTileSpec("elevation", activity.elevationGain?.let { "%.0f".format(it) } ?: "--", "爬升", unit = "m"),
        StatTileSpec("heart-rate", activity.avgHeartRate?.toString() ?: "--", "均心率", unit = "bpm"),
        StatTileSpec("data-points", dataPoints?.let { "%,d".format(it) } ?: "--", "数据点"),
    )
    StatGrid(stats = tiles, columns = 3)
}

/**
 * 分发行：合并「平台副本」与「单次补发」。
 * 已同步显示状态徽标；未同步显示补发按钮；异常任务显示重试 / 继续。
 */
@Composable
private fun DistributionRow(
    account: PlatformAccountEntity,
    copy: PlatformCopyEntity?,
    job: DeliveryJobEntity?,
    dateFormat: SimpleDateFormat,
    onOpenWeb: (DeliveryJobEntity) -> Unit,
    onRetry: (DeliveryJobEntity) -> Unit,
    onDispatch: () -> Unit,
) {
    val subtitle = when {
        copy != null -> "已同步 · ${dateFormat.format(Date(copy.createdAtEpochMs))}"
        job != null -> deliveryStatusText(job.status)
        else -> "未同步"
    }
    FitListRow(
        title = account.platform.displayName,
        subtitle = subtitle,
        supporting = if (copy == null && job != null) deliveryJobDetail(job) else null,
        leading = { PlatformAvatar(account.platform, 32.dp) },
        trailing = {
            when {
                copy != null -> StatusPill("已同步", StatusTone.SUCCESS)
                job == null -> Button(onClick = onDispatch) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("补发", modifier = Modifier.padding(start = FitSpacing.xs))
                }
                job.status == DeliveryJobStatus.WAITING_USER -> Button(onClick = { onOpenWeb(job) }) {
                    Text(if (account.platform == Platform.GARMIN) "重新登录" else "继续处理")
                }
                job.status == DeliveryJobStatus.FAILED || job.status == DeliveryJobStatus.RETRY_WAIT ->
                    OutlinedButton(onClick = { onRetry(job) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("重试", modifier = Modifier.padding(start = FitSpacing.xs))
                    }
                else -> StatusPill(deliveryStatusText(job.status), deliveryStatusTone(job.status), compact = true)
            }
        },
    )
}

/** 折叠的原始 FIT 区：默认只显示汇总，展开后列出每个副本。 */
@Composable
private fun VariantsCard(variants: List<ActivityVariantEntity>, primaryVariantId: Long?) {
    var expanded by remember { mutableStateOf(false) }
    val totalSize = variants.sumOf { it.fileSize }
    val dataPoints = variants.firstOrNull { it.id == primaryVariantId }?.recordCount
        ?: variants.mapNotNull { it.recordCount }.maxOrNull()
        ?: 0
    val summary = buildString {
        append("${variants.size} 个来源")
        if (totalSize > 0) append(" · ${formatFileSize(totalSize)}")
        if (dataPoints > 0) append(" · ${"%,d".format(dataPoints)} 数据点")
    }
    FitCard(
        modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
        contentPadding = PaddingValues(0.dp),
        onClick = { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = FitSpacing.rowPaddingH, vertical = FitSpacing.rowPaddingV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            IconBadge(Icons.Default.Description, size = IconBadgeSize.Md)
            Text(
                summary,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FitSpacing.iconMd),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = FitSpacing.rowPaddingH, end = FitSpacing.rowPaddingH, bottom = FitSpacing.rowPaddingV),
                verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
            ) {
                variants.forEach { variant ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                variant.sourcePlatform?.displayName ?: "未知来源",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                buildString {
                                    append(formatFileSize(variant.fileSize))
                                    variant.recordCount?.let { append(" · ${"%,d".format(it)} 数据点") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(
                            if (variant.validationStatus.name.startsWith("VALID")) "校验通过"
                            else "${variant.validationStatus}",
                            if (variant.validationStatus.name.startsWith("VALID")) StatusTone.SUCCESS
                            else StatusTone.WARNING,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}

/** 异常投递任务：只显示「发生了什么 + 你能做什么」。 */
@Composable
private fun JobProblemRow(job: DeliveryJobEntity, account: PlatformAccountEntity?) {
    val retryNote = deliveryJobRetryNote(job.attemptCount)
    FitListRow(
        title = account?.platform?.displayName ?: "未知平台",
        subtitle = deliveryJobDetail(job),
        supporting = retryNote,
        leading = { account?.let { PlatformAvatar(it.platform, 32.dp) } ?: IconBadge(Icons.AutoMirrored.Filled.Send, size = IconBadgeSize.Md) },
        trailing = { StatusPill(deliveryStatusText(job.status), deliveryStatusTone(job.status), compact = true) },
    )
}

@Composable
private fun ReviewActionsCard(
    candidate: CanonicalActivityEntity?,
    dateFormat: SimpleDateFormat,
    busy: Boolean,
    onMerge: () -> Unit,
    onKeepSeparate: () -> Unit,
) {
    FitCard(tone = FitCardTone.Primary) {
        Text("疑似重复活动", style = MaterialTheme.typography.titleSmall)
        Text(
            if (candidate != null) {
                "与「${candidate.title ?: "活动 #${candidate.id}"}」" +
                    "（${dateFormat.format(Date(candidate.startTimeEpochMs))}）高度相似，请选择合并或保持分离。"
            } else {
                "系统标记为待复核，但候选活动已不存在。可选择保持分离。"
            },
            modifier = Modifier.padding(top = FitSpacing.xs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = FitSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
        ) {
            Button(enabled = !busy && candidate != null, onClick = onMerge) {
                Text("合并到候选")
            }
            OutlinedButton(enabled = !busy, onClick = onKeepSeparate) {
                Text("保持分离")
            }
        }
    }
}

private val generatedActivityTitle = Regex(
    """^\d{4}-\d{2}-\d{2}\s+(凌晨|上午|中午|下午|晚上)\s+.+$""",
)

internal fun activitySummaryTitle(activity: CanonicalActivityEntity): String =
    activity.title
        ?.takeIf { it.isNotBlank() && !generatedActivityTitle.matches(it) }
        ?: sportLabel(activity.sportType)

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
