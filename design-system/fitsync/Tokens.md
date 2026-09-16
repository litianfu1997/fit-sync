# FitSync 视觉 Token（人读版）

> **唯一事实来源是 Kotlin 代码：**
> `app/src/main/java/com/fitsync/app/ui/theme/Color.kt` · `Type.kt` · `Shape.kt` · `Spacing.kt`
> 本文件是这些 Token 的可读镜像；两者冲突时以 Kotlin 代码为准。

三条贯穿原则：

1. **数字是主角，装饰是配角。** 彩色图标方块、大圆角、居中空状态一律降级；数据的字号/字重/对齐升为中心。
2. **硬朗几何：小圆角 + 1dp 描边 + 零阴影。** 圆角是「消费级」视觉编码，直角与细线是「仪表/工具」编码。
3. **层级靠尺度与对比，不靠颜色。** 单屏高饱和色 ≤ 3 个，其余用 `onSurface / onSurfaceVariant` 明度差。

---

## 颜色

| 角色 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| primary | `#C2410C` | `#FF8A3D` | 品牌 + 主动作 + 待处理 |
| secondary | `#15803D` | `#4ADE80` | 成功 |
| tertiary | `#1D4ED8` | `#60A5FA` | 距离 / 信息 |
| error | `#B91C1C` | `#F87171` | 失败 |

**亮色 / 可读两档**：橙色 `#F97316`、绿色 `#16A34A`、蓝色 `#2563EB`、红色 `#DC2626` 是品牌亮档，
白底上只有 2.4–3.3:1，**只能用于大色块**（Hero 渐变、大号数字、状态点）；
浅色主题的 scheme 一律用上面的「可读档」（Tailwind 700），白底文字与白字底都 ≥5:1。
深色主题的 scheme 本身就是亮档（深底上够亮），无需区分。
Hero 这类「浅色主题下的深色容器」由 `LocalContentColor` 判定：状态徽标、平台头像会自动切亮档。
| background | `#F1F3F5` | `#0A0E13` | 页面底 |
| surface | `#FFFFFF` | `#121820` | 卡片 |
| surfaceContainerLow | `#F7F8FA` | `#171E27` | 次级区块 / 空状态 |
| surfaceContainer | `#EEF1F4` | `#1C242E` | 分组 |
| surfaceContainerHigh | `#E6EAEF` | `#232C37` | 填充式输入框、骨架 |
| surfaceContainerHighest | `#DFE4EA` | `#2A3441` | 强调内嵌块 |
| onSurface | `#0F141A` | `#E8ECF1` | 主文本 |
| onSurfaceVariant | `#5A6673` | `#97A3B1` | 次级文本 / 图标 |
| outline / outlineVariant | `#C6CDD6` / `#E3E7EC` | `#38424F` / `#212A34` | 描边 / 分割线 |
| surfaceTint | `Transparent` | `Transparent` | 禁用 M3 自动染色 |

**色不过三**：primary 橙 = 品牌 + 主动作 + 待处理；tertiary 蓝 = 距离/信息；secondary 绿 = 成功；error 红 = 失败；
**其余统计与图标默认 `onSurfaceVariant`**。平台品牌色只允许出现在 `PlatformAvatar` 内。

**描边优先，不用阴影**：静态容器 `BorderStroke(1.dp, outlineVariant)` + `shadowElevation = 0`；
仅 Dialog / BottomSheet / Snackbar / Menu 使用 `tonalElevation`。

**Hero 渐变**（`FitGradients.hero`）：`#0E141B → #1A1712 → #3A1D07`，通过 `Modifier.fitHero()` 使用；
Hero 内前景色由 `LocalContentColor` 下发，`onHeroSecondary = White 62%`。

---

## 字体

单字族 **MiSans**（子集化三字重，`res/font/misans_{regular,medium,bold}.ttf`）。
不使用可下载字体：本地优先、用户可能无网且无 GMS。

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

**数字样式集（`FitType`，均启用 `tnum` 等宽数字）**

| 名称 | 字号 / 行高 / 字重 | 用途 |
|---|---|---|
| numericHero | 42 / 42 / Bold | 首页主数字 |
| numericXl | 30 / 32 / Bold | 大号统计 |
| numericMd | 20 / 24 / Medium | 列表主数字（距离） |
| numericSm | 15 / 20 / Medium | 次级数据（时长） |
| dataLabel | 11 / 14 / Medium, +0.6 | 区块标题、字段标签 |

**中文层级构造法**：字号差 ≥ 1.35×；字重差 ≥ 2 档；颜色明度差 ≥ 30%；单位小 1–2 档 + `onSurfaceVariant`。
数字与单位用 `AnnotatedString` 在同一 `Text` 内混排，保证基线与换行正确。

---

## 间距（`FitSpacing`）

标尺：`xxs 2 / xs 4 / sm 8 / md 12 / lg 16 / xl 20 / xxl 24 / xxxl 32 / huge 48`

语义别名（屏级布局只用别名）：

| 别名 | 值 | 用途 |
|---|---|---|
| screenGutter | 16 | 屏幕左右留白 |
| sectionGap | 24 | 大区块间距 |
| cardPadding / cardPaddingCompact | 16 / 12 | 卡片内边距 |
| rowPaddingH / rowPaddingV | 16 / 12 | 列表行内边距 |
| rowMinHeight | 64 | 列表行最小高度 |
| touchTarget | 48 | 最小可点击尺寸 |
| iconSm / iconMd / iconLg | 18 / 20 / 24 | 图标尺寸 |

---

## 圆角（`FitShape`）

原 `10/14/20/28/36` → 现 `2/4/8/12/16`

| 名称 | 值 | 用途 |
|---|---|---|
| indicator | 2 | 状态徽标、数据条 |
| control / badge | 4 | 输入框、chip、IconBadge、PlatformAvatar |
| card | 8 | 卡片 |
| hero | 12 | Hero、底部导航 |
| sheet | 16 | BottomSheet、Dialog |
| pill | 50% | 仅少量胶囊元素 |

---

## 组件

位于 `app/src/main/java/com/fitsync/app/ui/components/`，共 13 个文件。
新增/修改界面时，**优先复用这些组件而不是再写一次性布局**。

### API 速查

| 组件 | 签名要点 | 默认值 |
|---|---|---|
| `FitCard` | `tone: FitCardTone` / `shape` / `contentPadding` / `onClick` / `onLongClick` / `selected` / `border` / `enabled` | `Surface` / `FitShape.card` / `cardPadding` / 全 null / `false` / `true` / `true` |
| `IconBadge` | `icon` / `size: IconBadgeSize` / `tone: IconBadgeTone` / `contentDescription` | — / `Md`(36/20) / `Neutral` / `null` |
| `StatTile` | `value` / `label` / `unit` / `size: StatSize` / `tone: StatTone` / `onClick` | — / — / `null` / `Md` / `Default` / `null` |
| `StatGrid` | `stats: List<StatTileSpec>` / `columns: Int` / `onStatClick` | — / `2` / `null` |
| `HeroStatStrip` | `stats: List<StatTileSpec>` | — |
| `FitListRow` | `title` / `subtitle` / `supporting` / `leading` / `trailing` / `meta` / `onClick` / `onLongClick` / `showChevron` / `selected` / `enabled` / `minHeight` | — / 全 null / `false` / `false` / `true` / `rowMinHeight` |
| `SettingsRow` | `title` / `subtitle` / `icon` / `onClick` / `checked` / `onCheckedChange` / `trailing` / `enabled` / `showChevron` | — / 全 null / `true` / `true` |
| `SettingsGroup` | `title: String?` / `content` | `null` / — |
| `FitTopBar` | `title` / `subtitle` / `onBack` / `backContentDescription` / `actions: RowScope` | — / `null` / `null` / `"返回"` / `{}` |
| `FitEmptyState` | `title` / `body` / `variant: EmptyStateVariant` / `icon` / `steps` / `actionLabel` / `onAction` | — / — / `FirstRun` / `null` / 空 / `null` / `null` |
| `StatusPill` | `text` / `tone: StatusTone` / `compact` | — / `NEUTRAL` / `false` |
| `PlatformAvatar` | `platform` / `size: Dp` | — / `40.dp` |
| `PlatformRouteAvatar` | `source` / `destination` / `size: Dp` | — / — / `28.dp` |
| `TrackPreview` | `points: List<LatLng>` / `color` / `strokeWidth` | — / `onSurfaceVariant` / `1.5dp` |
| `TrackThumbnail` | `points` / `size` | — / `44dp` |
| `SeriesChart` | `values: List<Float>` / `color` / `strokeWidth` | — / `primary` / `1.5dp` |
| `FitSkeleton` | `modifier` / `shape` | — / `FitShape.control` |
| `FitPagingFooter` | `append: LoadState` / `itemCount: Int` / `onRetry` | — / — / — |

### 各组件要点

- **`FitCard`**：基于 `Surface` 而非 `Card`，保证形状与描边同源，从结构上杜绝「卡片圆角 ≠ 描边圆角」。
  `FitCardTone = Surface / Muted / Primary / Error`；**刻意不暴露 elevation 参数**（强制零阴影）。
- **`IconBadge`**：尺寸五套收敛为三档 Sm 28 / Md 36 / Lg 44，默认 `Neutral` 中性色抑制糖果感。
- **`StatTile` / `StatGrid` / `HeroStatStrip`**：统计面板默认无图标，value 用 `FitType.numeric*`（`tnum`），
  label 用 `dataLabel`，格子间仅 1dp 分隔线。
- **`FitListRow`**：活动 / 任务 / 平台三类行的同质化骨架，`meta` 用 ` · ` 连接数据条，不做等宽 label-value 表格。
- **`SettingsRow`**：同一组件覆盖「开关行 / 跳转行 / 自定义尾部」三种形态；20dp 裸线性图标 + 行高 64 + 1dp 分割线（缩进 16）。
- **`FitTopBar`**：容器色与页面背景一致，不加分割线。
- **`FitEmptyState`**：四变体差异化——`FirstRun` 左对齐编号步骤 / `NoResults` 单行 + 清除 / `Error` 描边 + 重试 / `AllDone` 对勾 + 链接。
- **`FitSkeleton` / `FitSkeletonListRow` / `FitPagingFooter`**：呼吸骨架（alpha 0.35→0.6）与分页尾部，不引入 shimmer 库。
- **`StatusPill`**：2dp 小圆角 + 10% 底 + 25% 描边，「遥测徽标」质感。
- **`PlatformAvatar`**：平台品牌色**只允许出现在这里**，其他位置一律中性色。
- **`TrackPreview` / `TrackThumbnail`**：经纬度归一化折线，**不引入地图 SDK**——要的是「路线长什么样」，不是地理精度。
  经度按 `cos(纬度)` 修正（否则高纬度轨迹被横向拉长），等比缩放居中、正北朝上。
  点数 < 2 时什么都不画，由调用方退回运动图标；列表行的两种形态共用同一套尺寸语汇。
- **`TrackMap`（osmdroid 例外）**：活动详情卡片与全屏地图页用 osmdroid + OSM 栅格瓦片画真实地理轨迹，
  是「列表缩略图不引入地图 SDK」这条原则的唯一例外——列表行**继续用 Canvas**（离线即时、零网络），
  地图只在详情/全屏出现。离线时背景保持中性色，轨迹线与起终点标记照常绘制。
- **`SeriesChart`**：速度 / 海拔曲线，面积 + 折线、无坐标轴无刻度；数据按时间等距抽样，横轴直接用下标。
  曲线颜色分工：轨迹 = primary，速度 = tertiary，海拔 = onSurfaceVariant，单屏高饱和色仍 ≤ 3。

---

## 无障碍约定

| 项 | 规则 |
|---|---|
| 触控区 | 最小 `FitSpacing.touchTarget = 48.dp`；**不要用 `Modifier.size()` 覆写 `IconButton`**，会覆盖默认最小尺寸 |
| 图标描述 | 独立语义的图标必须给 `contentDescription`；**按钮内已有可见文字的装饰性图标传 `null`**，避免 TalkBack 重复朗读 |
| 开关行 | 用 `Modifier.toggleable(role = Role.Switch)` 承载整行，`Switch(onCheckedChange = null)` 只作视觉呈现——否则会读成「可点击行 + 内部开关」两个节点 |
| 分割线 | `SettingsDivider(startInset)` 默认 16dp，对齐文本起点 |
| 对比度 | 正文 ≥ 4.5:1；次级文本用 `onSurfaceVariant` 而非降低透明度 |

---

## 预览与测试

- **预览**：`ui/components/ComponentPreviews.kt`，**只预览组件不预览 Screen**（Screen 依赖 `FitSyncApp.instance` 与数据库）。
  每个组件加浅色 / 深色双预览（深色用 `uiMode = 0x20`）。
- **测试**：`app/src/androidTest/java/com/fitsync/app/ui/components/FitComponentsTest.kt`，
  只覆盖无状态组件，不触碰 ViewModel / 数据库 / WorkManager。

```bash
export JAVA_HOME="D:/jdk-21"
./gradlew :app:connectedAndroidTest      # 需连接设备或模拟器
```

> **注意**：断言「点击后 UI 变化」时，状态必须用 `remember { mutableStateOf() }`，
> 普通局部变量不会触发重组，断言切换后的语义状态会失败。
