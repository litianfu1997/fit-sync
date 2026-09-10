# FitSync UI 视觉系统重建方案

> 目标：消除 UI 的「AI 生成感」，把 FitSync 从「默认 M3 组件 + 大圆角卡片堆叠」推向**运动数据感**（Strava / Garmin 气质）。

## 三条贯穿原则

1. **数字是主角，装饰是配角。** 彩色图标方块、大圆角、居中空状态等装饰性表达一律降级；数据的字号/字重/对齐升为中心。
2. **硬朗几何：小圆角 + 1dp 描边 + 零阴影。** 圆角是「消费级」视觉编码，直角与细线是「仪表/工具」编码。
3. **层级靠尺度与对比，不靠颜色。** 单屏高饱和色 ≤ 3 个，其余用 `onSurface / onSurfaceVariant` 明度差。当前「每个统计项一个糖果色」是最大 AI 感来源之一。

## 已核实的决策依据

- Compose BOM `2024.06.00` → material3 **1.2.1**，支持 `surfaceContainerLowest/Low/Container/High/Highest`、`surfaceDim/Bright`。
- ui-text **1.6.8** 支持 `TextStyle.fontFeatureSettings` 与 `FontVariation`。
- **`benchmark/src/main/java/com/fitsync/app/benchmark/BaselineProfileGenerator.kt:26,37` 依赖 `By.text("活动")` 与 `By.textContains("骑行")`——这两个文案不可改。**
- 工作区有未提交改动（`connectors/GarminConnectApiConnector.kt` + 测试）——开工前先提交或 stash 隔离。
- MiSans 为小米官方免费商用字体；打包后需把授权声明加入「关于」页。
- `androidx.core:core-splashscreen` 不在本地 Gradle 缓存，首次构建需联网拉取。

---

## P0 · Token 层 + 字体

**文件**

- 新增 `app/src/main/java/com/fitsync/app/ui/theme/Color.kt` / `Type.kt` / `Shape.kt` / `Spacing.kt`
- 重写 `app/src/main/java/com/fitsync/app/ui/theme/Theme.kt`（只做组装）
- 新增 `app/src/main/res/font/misans_regular.ttf` / `misans_medium.ttf` / `misans_bold.ttf`
- 更新颜色 import：`ui/components/FitSyncUi.kt`、`ui/screens/HomeScreen.kt`、`ui/screens/ActivitiesScreen.kt`、`ui/screens/ActivityDetailScreen.kt`、`share/ShareReceiverActivity.kt`

### 间距：顶层 `object`，不用 `CompositionLocal`

```kotlin
object FitSpacing {
    val xxs = 2.dp;  val xs = 4.dp;   val sm = 8.dp
    val md = 12.dp;  val lg = 16.dp;  val xl = 20.dp
    val xxl = 24.dp; val xxxl = 32.dp; val huge = 48.dp

    // 语义别名：屏级布局只用别名
    val screenGutter = lg; val sectionGap = xxl
    val cardPadding = lg; val cardPaddingCompact = md
    val rowPaddingH = lg; val rowPaddingV = md; val rowMinHeight = 64.dp
    val touchTarget = 48.dp
    val iconSm = 18.dp; val iconMd = 20.dp; val iconLg = 24.dp
}
```

**为什么不用 CompositionLocal**：间距不在树中变化；用 CompositionLocal 只会引入「可覆盖」能力，而可覆盖正是当前 `14/15/16` 混用的制度成因。

**收敛规则**：屏幕左右 16 / 卡片内边距 16（紧凑 12）/ 列表行 `16h + 12v`、最小高 64 / 卡片间距 列表 12、首页区块 16、大区块 24 / 图标与文字间距 12 → **8**。

### 形状：`10/14/20/28/36` → `2/4/8/12/16`

```kotlin
object FitShape {
    val indicator = RoundedCornerShape(2.dp)   // 状态徽标、数据条
    val control   = RoundedCornerShape(4.dp)   // 输入框、chip、IconBadge、PlatformAvatar
    val badge     = control
    val card      = RoundedCornerShape(8.dp)
    val hero      = RoundedCornerShape(12.dp)  // Hero、底部导航
    val sheet     = RoundedCornerShape(16.dp)
    val pill      = RoundedCornerShape(percent = 50)
}

private val FitShapes = Shapes(
    extraSmall = FitShape.indicator, small = FitShape.control, medium = FitShape.card,
    large = FitShape.hero, extraLarge = FitShape.sheet,
)
```

**顺带修复真实缺陷**：当前大量 `Card(默认 shape=medium=20) { Row(border(…, shapes.large=28)) }` 存在**卡片圆角与描边圆角不一致**（如 `HomeScreen.kt:750-756`、`SettingsScreen.kt:613-620`）。新 `FitCard` 用 `Surface(border = BorderStroke(...), shape = ...)` 从结构上杜绝。

### 色板：冷中性底 + 纯白卡 + 表面层级

| 角色 | 现值 | 新值 |
|---|---|---|
| background | `#F7F5F2` | `#F1F3F5` |
| surface | `#FFFCF9` | `#FFFFFF` |
| surfaceContainerLow | — | `#F7F8FA` |
| surfaceContainer | — | `#EEF1F4` |
| surfaceContainerHigh | — | `#E6EAEF` |
| surfaceContainerHighest | — | `#DFE4EA` |
| onSurface | `#111827` | `#0F141A` |
| onSurfaceVariant | `#667085` | `#5A6673` |
| outline / outlineVariant | `#D6D0C9 / #E8E2DC` | `#C6CDD6 / #E3E7EC` |
| primary | `#F97316` | `#F97316`（**保留**） |
| secondary | `#22C55E` | `#16A34A` |
| tertiary | `#0284C7` | `#2563EB` |
| error | `#FB5B67` | `#DC2626` |
| surfaceTint | 默认 | `Color.Transparent`（禁 M3 自动染色） |

深色：`background #0A0E13`、`surface #121820`、`surfaceContainerLow #171E27`、`surfaceContainer #1C242E`、`surfaceContainerHigh #232C37`、`onSurface #E8ECF1`、`onSurfaceVariant #97A3B1`、`outline #38424F`、`outlineVariant #212A34`，**primary 提亮 `#FF8A3D`**（`#F97316` 在深底偏暗）。

**描边优先，不用阴影**：静态容器 `BorderStroke(1.dp, outlineVariant) + shadowElevation = 0`；仅 Dialog / BottomSheet / Snackbar / Menu 用 `tonalElevation`。

**「色不过三」规约**：primary 橙 = 品牌 + 主动作 + 待处理；tertiary 蓝 = 距离/信息；secondary 绿 = 成功；error 红 = 失败；**其余统计与图标默认 `onSurfaceVariant`**。平台品牌色只允许出现在 `PlatformAvatar` 内。

**Hero 渐变 token 化**（统一 `HomeScreen.kt:891` 与 `ActivityDetailScreen.kt:530` 两处不一致的硬编码）：

```kotlin
object FitGradients {
    val hero = listOf(Color(0xFF0E141B), Color(0xFF1A1712), Color(0xFF3A1D07))
    val heroSurface = Color.White.copy(alpha = 0.06f)
    val onHeroPrimary = Color.White
    val onHeroSecondary = Color.White.copy(alpha = 0.62f)
}

@Composable fun Modifier.fitHero(shape: Shape = FitShape.hero): Modifier
```

Hero 内前景色用 `CompositionLocalProvider(LocalContentColor provides FitGradients.onHeroPrimary)`——子组件通过 `LocalContentColor.current` 自适应，避免 `onHero: Boolean` 层层透传。

### 字体：MiSans 单字族，子集化三字重

```kotlin
// ui/theme/Type.kt
val FitFontFamily = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium,  FontWeight.Medium),
    Font(R.font.misans_bold,    FontWeight.Bold),
)
```

**子集化**（保留 GB18030 常用字约 2.7 万字，预计三字重合计 +8~12MB）：

```bash
pyftsubset MiSans-Regular.ttf \
  --output-file="app/src/main/res/font/misans_regular.ttf" \
  --unicodes-file=gb18030_common.txt \
  --layout-features='*' --drop-tables+=DSIG
```

**必须保留 `tnum/kern` 等 layout feature**，不要为省体积砍 GSUB。放 `res/font/`（享受 release 的 `shrinkResources`）。

**明确不用** `androidx.compose.ui.text.googlefonts` 可下载字体：本 App 本地优先、用户可能无网且无 GMS，首次渲染会静默回退，违背单一字体目标。

**字阶表**（替换 `Theme.kt:76-106`；核心变化：正文降到 14–15sp，数据放大到 28–42sp）

| M3 slot | 字重 / 字号 / 行高 / 字距 |
|---|---|
| displayLarge | Bold 42 / 44 / -1.2 |
| displayMedium | Bold 34 / 38 / -0.8 |
| displaySmall | Bold 28 / 34 / -0.6 |
| headlineLarge | Bold 24 / 30 / -0.2 |
| headlineMedium | Bold 20 / 26 / 0 |
| headlineSmall | SemiBold 18 / 24 / 0 |
| titleLarge | SemiBold 17 / 24 / 0 |
| titleMedium | Medium 15 / 22 / 0 |
| titleSmall | Medium 14 / 20 / 0 |
| bodyLarge | Normal 15 / 23 / 0.1 |
| bodyMedium | Normal 14 / 21 / 0.1 |
| bodySmall | Normal 12 / 18 / 0.2 |
| labelLarge | Medium 14 / 20 / 0.2 |
| labelMedium | Medium 12 / 16 / 0.4 |
| labelSmall | Medium 11 / 14 / 0.5 |

**新增 `FitType` 数字样式集**（不占用 M3 slot，带 `fontFeatureSettings = "tnum"`）：

```kotlin
object FitType {
    val numericHero = TextStyle(/* 42sp / 42 / Bold / -1.2 / "tnum" */)
    val numericXl   = TextStyle(/* 30sp / 32 / Bold / -0.6 / "tnum" */)
    val numericMd   = TextStyle(/* 20sp / 24 / Medium / -0.2 / "tnum" */)
    val numericSm   = TextStyle(/* 15sp / 20 / Medium / 0 / "tnum" */)
    val dataLabel   = TextStyle(/* 11sp / 14 / Medium / 0.6 */)
}
```

**中文层级构造法**（中文没有 condensed、没有大小写）：字号差 ≥ 1.35×；字重差 ≥ 2 档；颜色明度差 ≥ 30%；单位小 1–2 档 + `onSurfaceVariant`。

`SectionHeader` 从 21sp Bold **降级为 11–12sp `dataLabel`**（中性 + 加字距），让数据成为主角——这是去 AI 感最有效的一步；页面主标题保持大字号。

**数字与单位用 `AnnotatedString` 在同一 `Text` 内混排**，保证基线与换行正确。

**`tnum` 兜底**：若 `otfinfo --features` 查不到 `tnum`，改用「固定容器宽度 + `textAlign = TextAlign.End`」，**不要**引入 `FontFamily.Monospace`（破坏单一字体策略）。

**授权合规**：在「隐私与关于」页加入 MiSans 授权声明。

---

## P1 · 组件层（只加不改，无视觉变化）

新建 9 个文件于 `app/src/main/java/com/fitsync/app/ui/components/`，`FitSyncUi.kt` 保留为兼容层（旧函数内部委托到新组件），调用点零改动。P6 删除兼容层。

| 新文件 | 内容 | 替代 |
|---|---|---|
| `FitCard.kt` | `FitCard` / `FitCardTone` | 41 处卡片三件套 |
| `FitIconBadge.kt` | `IconBadge` + `IconBadgeSize` / `IconBadgeTone` | ≥10 处图标方块（34/36/38/40/48 五套尺寸） |
| `FitStat.kt` | `StatTile` / `StatRow` / `StatGrid` / `HeroStatStrip` / `StatTileSpec` | `FitSyncUi.kt:132` `MetricCard` + `HomeScreen.kt:797` `StatCell` |
| `FitListRow.kt` | `FitListRow` | 活动卡 / 任务卡 / 平台卡同质化行 |
| `FitSettings.kt` | `SettingsRow` / `SettingsGroup` / `SettingsDivider` | `SettingsLinkRow` + `SettingSwitchRow` + `SettingsCard` |
| `FitTopBar.kt` | `FitTopBar`（含 `onBack`） | `AppTopBar` + 8 处手写返回栏 |
| `FitEmptyState.kt` | `FitEmptyState` + `EmptyStateVariant` | `FitSyncUi.kt:199` `EmptyState` |
| `FitLoading.kt` | `FitSkeleton` / `FitSkeletonListRow` / `FitPagingFooter` | 裸 `CircularProgressIndicator` |
| `FitStatus.kt` | `StatusPill`（重做）/ `PlatformAvatar` / `PlatformRouteAvatar` + 状态文案映射 | `FitSyncUi.kt` 状态部分 |

### 核心 API 草案

```kotlin
// ── FitCard.kt ── 基于 Surface 而非 Card，保证形状与描边同源
enum class FitCardTone { Surface, Muted, Primary, Error }

@Composable
fun FitCard(
    modifier: Modifier = Modifier,
    tone: FitCardTone = FitCardTone.Surface,
    shape: Shape = FitShape.card,
    contentPadding: PaddingValues = PaddingValues(FitSpacing.cardPadding),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    border: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
)   // 刻意不暴露 elevation 参数（强制零阴影）

// ── FitIconBadge.kt ── 尺寸五套收敛为三档，默认中性色抑制糖果感
enum class IconBadgeSize(val container: Dp, val icon: Dp) { Sm(28.dp, 16.dp), Md(36.dp, 20.dp), Lg(44.dp, 22.dp) }
enum class IconBadgeTone { Neutral, Brand, Info, Success, Warning, Error }

@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier,
              size: IconBadgeSize = IconBadgeSize.Md, tone: IconBadgeTone = IconBadgeTone.Neutral,
              contentDescription: String? = null)

// ── FitStat.kt ── 默认无图标，value 用 FitType.numeric*，label 用 FitType.dataLabel
enum class StatSize { Hero, Xl, Md, Sm }
enum class StatTone { Default, Success, Warning, Error }
data class StatTileSpec(val key: String, val value: String, val label: String,
                        val unit: String? = null, val tone: StatTone = StatTone.Default)

@Composable fun StatTile(value: String, label: String, modifier: Modifier = Modifier,
                         unit: String? = null, size: StatSize = StatSize.Md,
                         tone: StatTone = StatTone.Default, onClick: (() -> Unit)? = null)

@Composable fun StatRow(modifier: Modifier = Modifier, divided: Boolean = true,
                        content: @Composable RowScope.() -> Unit)

@Composable fun StatGrid(stats: List<StatTileSpec>, modifier: Modifier = Modifier,
                         columns: Int = 2, onStatClick: ((StatTileSpec) -> Unit)? = null)

@Composable fun HeroStatStrip(stats: List<StatTileSpec>, modifier: Modifier = Modifier)

// ── FitListRow.kt ──
@Composable
fun FitListRow(
    title: String, modifier: Modifier = Modifier,
    subtitle: String? = null, supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    meta: (@Composable RowScope.() -> Unit)? = null,   // 数据条：42.3 km · 1:24 · 320 m
    onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    showChevron: Boolean = false, selected: Boolean = false, enabled: Boolean = true,
    minHeight: Dp = FitSpacing.rowMinHeight,
)

// ── FitSettings.kt ── 合并原先只差尾部的两个行组件
@Composable
fun SettingsRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null,
                icon: ImageVector? = null, onClick: (() -> Unit)? = null,
                checked: Boolean? = null, onCheckedChange: ((Boolean) -> Unit)? = null,
                trailing: (@Composable () -> Unit)? = null, enabled: Boolean = true)

@Composable fun SettingsGroup(title: String? = null, modifier: Modifier = Modifier,
                              content: @Composable ColumnScope.() -> Unit)
@Composable fun SettingsDivider(startInset: Dp = FitSpacing.screenGutter)

// ── FitTopBar.kt ──
@Composable
fun FitTopBar(title: String, modifier: Modifier = Modifier, subtitle: String? = null,
              onBack: (() -> Unit)? = null, backContentDescription: String = "返回",
              actions: @Composable RowScope.() -> Unit = {})

// ── FitEmptyState.kt ── 打破「居中图标 + 标题 + 灰字」
enum class EmptyStateVariant { FirstRun, NoResults, Error, AllDone }

@Composable
fun FitEmptyState(title: String, body: String, modifier: Modifier = Modifier,
                  variant: EmptyStateVariant = EmptyStateVariant.FirstRun,
                  icon: ImageVector? = null, steps: List<String> = emptyList(),
                  actionLabel: String? = null, onAction: (() -> Unit)? = null)

// ── FitStatus.kt ── 形状由 CircleShape 改为 FitShape.indicator(2dp)，10% 底 + 25% 描边 →「遥测徽标」质感
@Composable fun StatusPill(text: String, tone: StatusTone = StatusTone.NEUTRAL, compact: Boolean = false)
@Composable fun PlatformRouteAvatar(source: Platform, destination: Platform, size: Dp = 28.dp)
```

**`FitEmptyState` 差异化规则**（去模板化核心）：

- `FirstRun`：**左对齐**，不用大色块图标，改 3 条编号步骤（① 连接平台 ② 分享或拉取 FIT ③ 自动补齐）+ 主按钮。首次使用需要「怎么做」，而不是一张装饰画。
- `NoResults`：压缩成**一行**（18dp 图标 + 文案 + 「清除筛选」TextButton），紧贴筛选条下方。
- `Error`：左对齐 + error 描边 + 「重试」按钮，文案含可执行动作。
- `AllDone`：小对勾 + 一句「全部同步完成」+ 「查看已同步」链接，不留大空白。
- 需要图形时用 `Canvas` 画极简橙色轨迹折线（复用 launcher 母题），**不引入插画资源**。

**骨架屏要做**（首屏体验收益最大）：`rememberInfiniteTransition` + alpha 0.35→0.6 呼吸动画，**不引入第三方 shimmer 库**。使用位置：Activities 首刷（4 行）、Platforms 首载、ActivityDetail 首帧。

**`FitPagingFooter`**：`append` 显示骨架行 / `error` 显示「加载失败 · 重试」/ 末尾显示「已加载全部 N 条」。

---

## P2 · 首页 `ui/screens/HomeScreen.kt`

现状：Hero（图标 + 状态 + 文案 + 按钮，信息过载）→ 2×2 等高 `StatCell`（后台表格式）→ 待处理卡 → 最近导入卡，**全是卡片、无节奏**。

**新结构（大 → 中 → 小 → 列表的节奏）**

```
┌ Hero（fitHero 渐变）─────────────────────────────┐
│ ● 自动同步正常                        [待处理 3] │  ← 状态行，去掉 40dp 图标方块
│ 本地活动                                        │  ← dataLabel
│ 1,284                                          │  ← numericHero(42sp, tnum, 数字滚动)
│ 已同步 1,201 · 待处理 31 · 失败 4                │  ← onHeroSecondary
│ [ 立即同步 ]              上次同步 09-10 14:20   │
└─────────────────────────────────────────────────┘
 StatGrid(columns = 2)     ← 无图标，仅 1dp 分隔线
 需要你处理  [3]            ← SectionHeader 降级 + pill
 ─ 📱 行者   需要处理    [继续]
 最近导入
 ─ FIT 文件…  09-10 14:20  [导入完成]
```

**改造点**

1. `SyncHero`（`HomeScreen.kt:879-966`）：删除 40dp 图标方块；改为「状态点 + 状态文案」，保留一个主数字（`stats.discovered`）+ 一行次级数据 + 按钮 + 上次同步时间。加 `animateIntAsState` 数字滚动（首帧 gate 见 P5）。
2. `StatsPanel`（2×2）→ `StatGrid(columns = 2)`，**去掉 IconBadge**，四个入口的 onClick 保留。
3. `activeImportCount` 提示卡与 `duplicateImportCount` 提示卡 → 合并为一条 `InlineStatusBar`（用 `surfaceContainerLow`，不是 Card）。
4. 待处理项与最近导入项 → `FitListRow`，`trailing` 用 `StatusPill` / `Button`。
5. 空态 → `FitEmptyState(variant = FirstRun, steps = listOf("连接平台账号", "分享或拉取 FIT", "自动补齐到其他平台"))`。
6. `ManualSyncSheet`：Card → `FitCard`；`FilterChip` 高度 44 → **36**；section 小标题用 `dataLabel`。
7. **不动**：`HomeViewModel`、`produceState` 的 `withFrameNanos {}`、`BackgroundRunGuard` 相关逻辑、`manualSyncUiState` 映射。

---

## P3 · 列表与详情

### `ui/screens/ActivitiesScreen.kt`

- 搜索框：`OutlinedTextField` 描边样式 → **填充式**（`surfaceContainerHigh` 底、`FitShape.control`、高 44），减少线条噪音；保留 debounce 250ms 逻辑。
- 筛选 chips 高度 44 → 36；「N 条已加载 + 排序」行保留。
- `ActivityCard`（`:247-299`）→ `FitListRow`，**数据排版去表格化**：

  ```
  [骑行图标]  晨骑                                  [待复核]
              09-10 06:12 · 骑行
              42.3 km · 1:24 · 320 m                    ›
  ```

  规则：**距离作为唯一主数字**（`numericMd` 20sp，最深色），时长/爬升为次级（`numericSm` + `onSurfaceVariant`），三者用 ` · ` 连接而不是三个等宽 label-value 竖排；日期与运动类型合并到一行副标题。

- 空/无结果分别用 `NoResults`（一行 + 清除筛选）与 `FirstRun` 变体。
- 首屏 refresh 用 4 行 `FitSkeletonListRow`；列表尾部加 `FitPagingFooter`。
- **不动**：`Pager/PagingConfig`、`remember(listQuery)`、`itemKey { it.id }`、`contentType`、`PerformanceTrace` 段。

### `ui/screens/ActivityDetailScreen.kt`

现状：Hero（4 个平级 stat）→ 复核卡 → 来源卡 → 平台副本卡 → 补发卡 → 本地 FIT 卡 → 投递记录卡 → 删除卡，**全部同重量白卡**。

```
┌ Hero ─────────────────────────────────────────────┐
│ [骑行] 晨骑 · 09-10 06:12                           │
│ 42.3 km                                           │  ← displayMedium, tnum
│ 时长 1:24 · 均速 30.2 km/h · 爬升 320 m             │
└───────────────────────────────────────────────────┘
 StatGrid 3×2：距离 / 时长 / 均速 / 爬升 / 均心率 / 数据点
 分发（合并「平台副本 + 单次补发」）
 ─ 行者  已同步 · 3月12日            [已同步]
 ─ COROS 未同步                     [补发]
 原始 FIT（折叠）
 ─ 1 个来源 · 1.2 MB · 4,812 数据点                    ›
 投递记录（仅在有失败/待处理时出现）
 删除本地活动（页面底部低强调 TextButton）
```

**改造点**

1. Hero 主数字单一化（距离），其余降级为 `HeroStatStrip`。
2. 新增 `StatGrid`，把散落的距离/时长/心率/爬升集中成一处数据面板。
3. **「平台副本」与「单次补发」合并为「分发」区**：每平台一行 `FitListRow`（leading `PlatformAvatar`，subtitle 为状态，trailing 为 `StatusPill` 或「补发」按钮）。这直接删掉两张卡与两次 `accounts` 遍历。
4. 「本地 FIT」折叠：`animateContentSize()` + `AnimatedVisibility`。
5. **投递记录默认只显示异常项**（`FAILED / WAITING_USER / RETRY_WAIT`）；成功记录已由「分发」行表达。
6. 删除区从 error 大卡降级为底部低强调 `TextButton(color = error)`（危险操作不该被美化）。
7. **不动**：`navigateBackOnce` / `backRequested` / `hasObservedActivity` 的删除后返回逻辑、`activityMergeService` / `activityDeletionService` 调用、`reviewCandidate` 的 `LaunchedEffect`。

### `ui/screens/SyncJobsScreen.kt`

- `SyncJobCard` → `FitListRow`，`selected` 参数驱动多选态（primary 描边）。
- 顶部 `FitTopBar(onBack = …)`，多选 actions 用 `RowScope`。
- 尾部 `FitPagingFooter`。
- **不动（高风险）**：`selectionMode` / `selectedIds` / `TriStateCheckbox` 全选逻辑、`LaunchedEffect(selectionMode){ scrollToItem(0) }`、`jobs.itemKey { it.id }`、`PagingConfig(maxSize = 150)`、删除/重试调用。

### `ui/screens/SyncRulesScreen.kt`

- `RouteCard` → `FitCard`；顶部行改为 `FitListRow`（title = 「迈金 → 行者」、trailing = `Switch`），中间用 `PlatformRouteAvatar`，**去掉 32/38dp 两个独立头像 + 箭头 + 文字的拥挤排布**。
- 「补发现有历史」内嵌块 → `surfaceContainerLow` + `SettingsRow` 样式。
- 空态 → `AddLink` 图标 + `FirstRun` 变体。
- 保留 `updateRule` / `historyConfirmation` 确认流程与 `SnackbarHostState` 逻辑。

### `ui/screens/PlatformsScreen.kt`

- 每个平台一张大卡（5 个平台 × 4 行文字 + 3 个按钮 = 一屏放不下）→ **折叠式**：
  - 默认一行 `FitListRow`（`PlatformAvatar` + 名称 + 上次同步 + `StatusPill` + chevron）
  - 点击展开操作区（`AnimatedVisibility`）：能力说明、实验性说明、登录/打开平台/重新验证/断开
- 首次加载用 `FitSkeletonListRow` × 5；顶部统计行保留。
- 保留 `platformAccountRepository.validateAndPersist / disconnect / loginWithCredentials` 与对话框调用。

---

## P4 · 设置族 `ui/screens/SettingsScreen.kt`

1. **拆文件**（纯搬迁，低风险）：`SettingsHomeScreen.kt` / `BackgroundSettingsScreen.kt` / `StorageSettingsScreen.kt` / `PrivacyAboutScreen.kt`，共用件留 `SettingsComponents.kt`。注意同包 `private` → `internal`。
2. **摆脱「一坨卡片」**：
   - 组标题从 `labelLarge` 改为 `dataLabel`（11sp / 加字距 / `onSurfaceVariant`），更小更靠左
   - 组容器仍是一张 `FitCard`，但**行与行之间只用 1dp 分割线**，行高 64
   - **去掉图标彩色圆角方块**：改用 20dp 裸线性图标（`onSurfaceVariant`），直接位于文字左侧——这是「专业工具」观感的来源
   - 分割线缩进对齐文本起点（16dp），替代硬编码 `start = 66.dp`
3. 「首次历史范围」：**删除 −30 / +30 按钮行**（同一设置现在有 chips、±、文本三种控件），保留 30/90/180/365 chips + 当前值说明。
4. `StorageOption`（radio 行）保留 radio 语义但视觉并入 `SettingsRow` 体系。
5. `GarminDiagnosticsCard`：图标 `BugReport` → `Troubleshoot`；等宽诊断文本段默认折叠（「查看最近记录」）。
6. `BackgroundProtectionCard` 的 `StatusLine` 用 `SettingsRow(trailing = StatusPill)` 重写。
7. 「隐私与关于」加入 **MiSans 授权声明**。

---

## P5 · 横切

### 动效（含「拖影」的正确修法）

`FitSyncAppRoot.kt:119-123` 用 `snap()` 把动画时长归零来「掩盖」问题，代价是完全没有转场，且与系统预测性返回手势动画冲突。真实的拖影成因通常是转场期间旧 destination 仍在组合（含 `AndroidView/WebView` 时尤甚）——本项目 NavHost 内全是 Compose、WebView 在独立 Activity，**风险低，可安全恢复转场**：

```kotlin
private const val NavMs = 180
enterTransition    = { fadeIn(tween(NavMs)) + slideInHorizontally(tween(NavMs)) { it / 12 } }
exitTransition     = { fadeOut(tween(NavMs / 2)) }
popEnterTransition = { fadeIn(tween(NavMs)) + slideInHorizontally(tween(NavMs)) { -it / 12 } }
popExitTransition  = { fadeOut(tween(NavMs / 2)) }
```

- 位移幅度取 1/12 屏宽（约 30px）——足够暗示方向，不会产生「两页并存」错觉。
- 若低端机仍出现残影：**只保留 `fadeIn/fadeOut(tween(180))`**，先定位具体屏幕再决定是否加位移；**不要回到 `snap`**。
- 改后跑一次启动 benchmark 对比。

| 类型 | 做法 | 风险 |
|---|---|---|
| 点击反馈 | 确保 `clip(shape)` 在 `clickable` 之前（`FitCard` 内部已处理）；长按用 `combinedClickable` + `HapticFeedback` | 低 |
| 列表入场 | **只在 Home 区块**用 `AnimatedVisibility(fadeIn + slideInVertically)`，stagger 延迟 `index * 30ms`（上限 150ms）；**Paging 列表不做 stagger**（与 item 回收冲突） | 中 |
| 数字变化 | `animateIntAsState` 仅用于 Home 的统计；**必须首帧 gate**（`LaunchedEffect { animateStarted = true }`），否则会从 0 滚到初始值 | 中 |
| 状态切换 | `AnimatedContent` 用于状态徽标文案、`animateColorAsState` 用于 tone 颜色 | 低 |
| 展开折叠 | `animateContentSize()` 用于 FIT 详情、平台操作区 | 低 |

### 反馈统一（Toast → Snackbar）

根 `Scaffold` 增加 `snackbarHost`，通过 `LocalAppSnackbar: CompositionLocal<SnackbarHostState>` 下发。

| 位置 | 处理 |
|---|---|
| `ActivityDetailScreen.kt:151`（删除成功但残留文件） | → **根级 App Snackbar**（紧接着 `navigateBackOnce()`，屏级 Snackbar 会随宿主销毁） |
| `ActivityDetailScreen.kt:201`（合并成功） | → 根级 App Snackbar |
| `ActivityDetailScreen.kt:267`（已保持分离） | → 根级 App Snackbar |
| `PlatformWebActivity.kt:224 / :328` | **直接删除 Toast**：`statusText` 已同步更新且信息条常驻，重复反馈更差 |
| `PlatformWebActivity.kt:677` | **保留 Toast**，加注释说明是有意例外：宿主即将 `finish()`，Snackbar 无处存活 |

### 图标语义修正

| 位置 | 现在 | 建议 | 理由 |
|---|---|---|---|
| 自动同步开关 | `AutoAwesome` | `Icons.Default.Sync` | ✨ 是最强的 AI 符号 |
| 室内骑行 | `FitnessCenter`（哑铃） | `Icons.AutoMirrored.Filled.DirectionsBike` + 文案「室内」 | 哑铃 = 健身房，Material 无躺式单车字形 |
| Garmin 诊断 | `BugReport` | `Icons.Default.Troubleshoot` | bug 图标有「测试版」暗示 |
| 清理文件 | `CleaningServices` | `Icons.Default.DeleteSweep` | 拖把 = 家政（注意 `Sweep` 不存在） |
| 等待任务 | `History` | `Icons.Default.PendingActions` | 时钟 ≠ 待办 |
| 本地活动统计 | `FileDownload` | `Icons.AutoMirrored.Filled.DirectionsBike` | 不是下载 |
| 底部导航「活动」 | `AutoMirrored.Filled.List` | `DirectionsBike`（选中 Filled / 未选中 Outlined） | 主题化 |
| 底部导航「概览」 | `Home` | 保留 `Home` 或 `SpaceDashboard` | 后者数据感更强 |
| 「先连接平台」空态 | `Link` | `Icons.Default.AddLink` | 语义更准 |
| 同步自动化 | `Route` | `Icons.Default.Rule` 或 `AltRoute` | Route 易与活动路线混淆 |

并确立规则：**未选中/次级用 `Outlined`，选中/强调用 `Filled`**。全部来自已有依赖，无新增。

### 文案去开发者味

| 场景 | 现在 | 建议 |
|---|---|---|
| 任务无标题 | `本地活动 #1234` | `未命名骑行` + 副标题日期 |
| 任务详情 | `错误码 AUTH_EXPIRED · 已尝试 3 次` | 主：`登录已失效，请重新登录`；次：`已自动重试 3 次` |
| 失败原因 | `错误码 429 · 请求过于频繁` | `平台暂时限流，稍后会自动重试` |
| 平台副本 | `远端 ID · 6a1c9f3d...` | `已同步 · 3月12日 14:20`（原始 ID 折叠进详情或长按复制） |
| FIT 信息 | `1.2 MB · 质量 92` | `1.2 MB · 4,812 个数据点`（「质量分」无上下文，数据点更可感知） |
| 投递区说明 | `每个目标平台只会创建一个幂等任务` | `同一活动只会向每个平台发送一次` |
| 空状态副标题 | `系统分享和平台拉取记录` | `从平台拉取或分享 FIT 后，记录会出现在这里` |
| 首页副标题 | `FitSync · 本地优先同步` | `原始 FIT 只保存在本机` |
| 清理结果 | `按策略删除 N 个 FIT` | `已按设置清理 N 个原始文件` |

原则：错误信息 = **发生了什么 + 你能做什么**；`errorCode / id / attemptCount` 一律降级为次级或折叠信息。注意 `deliveryJobDetail` 定义在 `SyncJobsScreen.kt:394` 且被 `ActivityDetailScreen` 复用，改它两处同时生效，需一并验证。

### 冷启动白屏：引入 `androidx.core:core-splashscreen:1.0.1`

当前 `app/src/main/res/values/themes.xml` 只有 `parent="android:Theme.Material.Light.NoActionBar"`——**深色模式下启动会白闪**。

- 新增 `Theme.FitSync.Starting`（parent `Theme.SplashScreen`），`windowSplashScreenBackground` = 深色 `#0B0F14`（`values-night` 覆盖）、`windowSplashScreenAnimatedIcon` = `@drawable/ic_launcher` 前景、`postSplashScreenTheme` = `@style/Theme.FitSync`
- `Theme.FitSync` 去掉 `Light` 改为 `android:Theme.Material.NoActionBar` 并设置深色 `windowBackground`
- `MainActivity` 在 `setContent` 之前调用 `installSplashScreen()`
- **不要**用 `setKeepOnScreenCondition` 延长停留（会拖慢感知启动）
- 注意：`core-splashscreen` **不在本地 Gradle 缓存中**，首次构建需要联网拉取

### `design-system/fitsync/MASTER.md`

该文件是 **Web/CSS 语境**（`cursor-pointer`、`hover`、`backdrop-filter`、CSS 变量）且指定 Barlow Condensed + Barlow 字体，与「Compose + 中文优先单字体」完全不符，保留会持续误导后续开发。

- `MASTER.md` 顶部加 3 行「已废弃，见 `Tokens.md`」横幅（零风险）
- 新增 `design-system/fitsync/Tokens.md`，把颜色/字阶/间距/圆角/组件用法以人类可读表格写出，并声明**唯一事实来源是 Kotlin token 文件**
- 空的 `pages/` 目录删除

---

## P6 · 收尾

- 删除兼容层：`FitSyncUi.kt` 的旧 `MetricCard` / `EmptyState` / `AppTopBar` 等，全量替换调用点。
- 组件层 `@Preview`（**只预览组件，不预览 Screen**，避免依赖 `FitSyncApp.instance` 与 DB）：`FitCard`（各 tone + selected）、`IconBadge`（全尺寸）、`StatTile`（全 size）、`StatGrid` 2×2、`FitListRow`（含 meta / trailing / 选中）、`FitEmptyState`（4 变体）、`StatusPill`（5 tone）。每个加 Light / Dark 双预览，硬编码假数据。
- `grep -n "padding(" ui/screens/` 逐条核对残留的 `.dp` 字面量。

---

## 风险与约束

### 高风险（触及状态/逻辑，改动需克制）

| 位置 | 注意 |
|---|---|
| `FitSyncAppRoot.kt` NavHost | 只改 transition 与 bottomBar；route / 参数 / `rememberSaveable` / `navigateToMainDestination` 一律不动；转场必须真机验证 |
| Paging3 列表（Activities / SyncJobs） | 只允许加 footer item 与替换 item 视觉；`itemKey` / `contentType` / `PagingConfig` / `scrollToItem(0)` 必须保留 |
| Home 的 WorkManager 状态展示 | `produceState` + `withFrameNanos {}` + `manualSyncUiState` 只改展示层 |
| ActivityDetail 删除/合并 | `navigateBackOnce` / `hasObservedActivity` 逻辑保留；Snackbar 必须 hoist 到根，否则消息随页面销毁丢失 |
| `deliveryJobDetail`（`SyncJobsScreen.kt:394`） | `internal` 且被两个屏共用，改文案需两处同步验证 |
| `benchmark` 模块 | `BaselineProfileGenerator` 依赖文案「活动」「骑行」，保持；splash 会改变冷启动时序，改后需复查 `StartupBenchmark` |

### 中风险

- `FitTopBar.actions` 签名从 `@Composable () -> Unit` → `@Composable RowScope.() -> Unit`（`SyncJobsScreen` 的 actions 需顺带简化）
- `SettingsScreen.kt` 拆文件（`private` → `internal`）
- 字体子集化造成生僻字形变

### 约束

- 新增依赖**仅** `androidx.core:core-splashscreen:1.0.1`（需联网拉取）；建议显式声明 `androidx.compose.animation:animation`（BOM 管理，无版本号）。**不引入** shimmer / 图片库 / Hilt。
- 只动 UI 层。`web/PlatformWebActivity.kt` 与 `share/ShareReceiverActivity.kt` 属于 UI 层但不在 `ui/` 包内，提交信息需标注。
- 所有注释与用户可见文案为**简体中文**（项目约定）。
- 保留橙色 `#F97316` 作为品牌识别点。

---

## 实施顺序

| 阶段 | 内容 | 涉及文件 |
|---|---|---|
| **P0** | 字体 + Token | `res/font/*`、`ui/theme/*`、5 个 import 文件 |
| **P1** | 组件层（只加不改） | `ui/components/*` |
| **P2** | 首页 | `ui/screens/HomeScreen.kt` |
| **P3** | 列表与详情 | Activities / ActivityDetail / SyncJobs / SyncRules / Platforms |
| **P4** | 设置族 | `ui/screens/Settings*.kt` |
| **P5** | 横切 | `FitSyncAppRoot.kt`、`ActivityDetailScreen.kt`、`web/PlatformWebActivity.kt`、`res/values/themes.xml`、`MainActivity.kt` |
| **P6** | 收尾 | 删兼容层 + `@Preview` + `Tokens.md` |

---

## 验证

```bash
export JAVA_HOME="D:/jdk-21"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
cd "d:/IdeaProjects/fit sync" && ./gradlew :app:assembleDebug
./gradlew :app:test          # 确认未碰到 domain/data 逻辑
```

**逐屏 × 状态矩阵**

- 首页：Hero 三态（自动同步关 / 受限 / 正常）、统计从 0 → 有值（数字动画是否从 0 滚动）、无活动首跑空态、待处理存在/不存在、最近导入含重复文件提示
- 活动列表：首屏骨架 → 列表、筛选到空结果、搜索无结果、加载更多 footer、加载失败重试、待复核徽标
- 活动详情：有待复核候选 / 候选已消失、无平台副本、有失败任务、展开/折叠原始 FIT、删除确认→返回提示
- 同步任务：空/等待/完成/失败三态、进入多选、全选/反选、批量删除、批量重试
- 同步规则：无账号（FirstRun）、无可用目标、规则开启/关闭、补发历史确认弹窗
- 平台账号：账号列表加载骨架、展开/收起、登录失效态、验证/断开
- 设置族：4 个子页进出转场、开关、通知权限（Android 13+）、存储策略切换、立即清理
- 分享导入页 / 平台网页页：进度 → 结果、阻止跳转提示（确认不再弹 Toast）

**横切检查**

- 深色 / 浅色：卡片是否仍有层级（不能「糊在一起」）
- 小屏 360dp：`StatGrid` 与列表 `meta` 是否换行/截断（`GARMIN`、`iGPSPORT` 长名）
- 字体：生僻字、emoji、中英数字混排、系统字体缩放 1.3×（统计块是否崩版）
- 数字：10,000+ 大数值下 `tnum` 是否对齐；单位是否基线对齐
- 交互：底部手势导航条是否遮挡悬浮导航（内容 bottom padding ≥ 24）；软键盘弹出时搜索框是否被顶
- 动效：进出转场无拖影（低端机重点）、点击 ripple 有界、开关/展开动画流畅
- 性能：冷启动对比改造前（splash + 转场为变量）、活动列表快速滚动无掉帧
- 无障碍：`contentDescription` 全覆盖（返回、图标按钮、图表）、`touchTarget ≥ 48dp`、正文对比度 ≥ 4.5:1
