# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目概览

FitSync 是一个**纯 Android 应用**（无自建服务器），用于在迈金(ONELAP)、行者(Xingzhe)、Garmin Connect、COROS、iGPSPORT 之间搬运和补齐骑行活动的 FIT 文件。活动中台、去重、同步任务、原始 FIT 全部存放在本机应用沙箱。

技术栈：Kotlin · Jetpack Compose + Material 3 · Room(SQLite) · WorkManager · OkHttp · 自实现的轻量 FIT 解析引擎。单模块工程（`settings.gradle.kts` 只 include `:app`）。

## 构建与测试

**JDK 要求（重要）**：构建需要 JDK 17+。本机 `JAVA_HOME` 默认指向 JDK 8，必须显式覆盖：

```bash
export JAVA_HOME="D:/jdk-21"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"   # 或对应的 SDK 路径
./gradlew :app:assembleDebug
```

依赖通过阿里云 maven 镜像解析（见 `settings.gradle.kts`），`repositoriesMode = FAIL_ON_PROJECT_REPOS`，模块级仓库会被拒绝。

```bash
./gradlew :app:test                                       # 全部 JVM 单测
./gradlew :app:test --tests "com.fitsync.app.fit.FitEngineTest"                          # 单个测试类
./gradlew :app:test --tests "com.fitsync.app.fit.FitEngineTest.parseValidFit"            # 单个方法
./gradlew :app:connectedAndroidTest                      # 仪表化测试（需连接设备/模拟器）
./gradlew :app:assembleRelease                           # release 需 keystore.properties（见下）
```

测试用 JUnit4 + Google Truth + kotlinx-coroutines-test。Room 迁移测试（`Migration1To2Test`）在 `androidTest` 下。

**Release 签名**：`keystore.properties`（gitignored）提供 `storeFile/storePassword/keyAlias/keyPassword`，由 `app/build.gradle.kts` 在构建期读取；文件缺失会导致 release 构建报错，debug 不受影响。keystore 文件放在仓库根的 `keystore/`（同样被忽略）。

## 架构（必读：跨多文件的大图景）

### 依赖注入：手动单例图，根在 FitSyncApp

**没有使用 Hilt/Dagger。** `FitSyncApp`（Application）是唯一的组装根，在 `onCreate()` 手工 `new` 出整条依赖链并持有为 `lateinit var`。后台代码（`SyncWorker`、`ShareReceiverActivity` 等）通过 `FitSyncApp.instance` 反向获取这些单例。

新增需要被复用的组件时：在 `FitSyncApp.onCreate()` 构造并暴露字段，不要就地 `new`。

### 核心数据模型（Room，version 3）

数据模型围绕「同一骑行的多个来源副本」展开，理解这层关系是修改同步逻辑的前提：

- `CanonicalActivity` — 一条**规范化的真实活动**（去重后的「真相」），承载汇总指标 + `primaryVariantId`。
- `ActivityVariant` — 同一活动从不同平台/设备拿到的**原始 FIT 文件副本**（按 `fileSha256` 唯一），带质量分、设备信息、轨迹摘要。canonical 1 → N variant。
- `PlatformCopy` — 记录「某活动已经存在于某平台账号」，`(canonicalActivityId, platformAccountId)` 与 `(platformAccountId, remoteActivityId)` 双唯一索引。**这是去重和防止同步循环的关键表**。
- `DeliveryJob` — 上传任务队列（状态机 + 幂等键 + 重试退避）。`idempotencyKey = sha256(variant)+destinationAccountId`。
- `SyncRule` — 「来源平台 → 目标账号」规则，启用即授权自动投递。
- `ImportJob` — 单次导入过程的审计记录（COPYING→VALIDATING→DEDUPLICATING→READY/INVALID）。
- `PlatformAccount` — 每平台**最多一个**账号（`platform` 唯一索引，迁移 1→2 时做了去重）。

**Room schema 变更**：改实体必须新增 `Migration` 并加到 `FitSyncDatabase.build()` 的 `.addMigrations(...)`，同时递增 `@Database(version = ...)`。`exportSchema = false`。

### 数据导入流水线（ImportService）

`ImportService.importFromUri()` 是所有 FIT 进入应用的统一入口（系统分享、云端下载、手动都走它）：copy 到沙箱 → 算 sha256 → 命中已有 variant 直接返回 `DuplicateFile` → `FitEngine.parseFile` 校验(CRC/完整性) → `DedupEngine` 与候选比对 → 写 canonical + variant。

`reparseStoredActivitiesIfNeeded()` 在 App 启动时按 `app_settings.fitParserVersion < CURRENT_PARSER_VERSION(=2)` 决定是否用新解析器重算历史活动的元数据。**改 FitEngine 解析逻辑时**：递增 `ImportService.CURRENT_PARSER_VERSION`，否则旧数据不会被刷新。

### 去重引擎（DedupEngine）

评分制：相似度 ≥92 自动合并(AUTO_MERGE)、≥75 待人工(NEEDS_REVIEW)、其余建新。维度权重为 开始时间25 / 轨迹25 / 时长15 / 距离15 / 移动时长10 / 传感器10。`filterCandidates` 用 ±10 分钟时间窗 + 距离 8% 容差先缩小候选集。

### 同步编排（SyncCoordinator + WorkManager）

- `SyncWorker`（CoroutineWorker）三态：周期同步(≥15min)、手动立即同步(force)、续跑投递批次(delivery-only)。
- `runSync()` 在单个 `Mutex` 下：遍历活跃账号 → 仅当 `listActivitiesMode == BACKGROUND` 才后台拉取 → 拉(list→download→import)完跑 `processDeliveryJobs()`。其他执行模式降级为通知用户去网页操作。
- 拉取窗口（`calculatePullSince`）：首次/强制 = 最近 `historyDays`(默认90) 天；增量 = 上次同步 - 48h 重叠窗。强制全量会清空 cursor 从第 1 页重拉。
- `processDeliveryJobs()` 按 `ConnectorResult` 分支更新 job 状态：成功/远端重复/需登录(标 AUTH_EXPIRED)/需用户操作(WAITING_USER+通知)/可重试(指数退避 max 6h)/永久失败。
- `DeliveryPlanner` 给每个新 canonical 活动，按命中的启用规则生成 DeliveryJob；`includeHistory` 关闭时只投递规则创建后的新活动。

### 连接器层（connectors/）

`PlatformConnector` 接口 + `ConnectorResult<T>` sealed 类型驱动一切平台交互。**所有平台调用都返回 ConnectorResult，绝不要直接抛业务异常**——上层 `SyncCoordinator` 按分支决定状态机转移。

- `HttpPlatformConnector` 基类（Onelap/Xingzhe）：OkHttp + `WebSessionManager.applyTo()` 注入 Cookie/Token；401/403 降级、429/5xx 可重试、其他永久失败的映射在 `failure()` 里。
- `ForegroundWebConnector`（Garmin/COROS/iGPSPORT）：所有方法返回 `RequiresUserAction`，由 SyncCoordinator 转 `WAITING_USER` 任务 + 通知，引导用户去网页完成。
- 能力矩阵在 `PlatformCapabilityRegistry`：**读取能力 ≠ 写入能力**，不要因为能列出活动就假定能上传。运行时可在校验后降级。

### 安全模型

- 平台 Cookie 存 `WebSessionManager`；迈金(ONELAP)的 token/refreshToken 走 `SecureSessionStore`（Android Keystore 加密）。
- 备份已关：`AndroidManifest.xml` 里 `allowBackup=false` + `fullBackupContent=false`。
- FIT 下载做了 host 白名单校验（`isTrustedFitHost`，仅允许 onelap/aliyuncs/myqcloud 域），且强制 https。
- **不要**在任何地方明文持久化账号密码或 token；新平台敏感凭据同样走 Keystore。

## 常见改动清单

- **新增平台**：`Platform` 枚举 → `PlatformCapabilityRegistry.capabilities()` → `ConnectorRegistry`（注册 connector 或 ForegroundWebConnector）→ `PlatformWebConfigs`（loginUrl/uploadUrl）。平台展示名在 `Platform.displayName`。
- **新增 Room 字段/实体**：改 `Entities.kt` → 写 `Migration` 加进 `FitSyncDatabase` → 递增 version → 覆盖测试（参考 `Migration1To2Test`）。
- **改平台 API 解析**：通用 JSON 字段查找在 `JsonPlatformParser`（多候选 key + 嵌套 `data`/`record` 等对象），按需加 key；解析逻辑尽量放这里复用，并补 `JsonPlatformParserTest`。
- **新增被复用的服务**：在 `FitSyncApp.onCreate()` 构造并暴露，不要在调用处就地实例化。
- **FIT 命名/存储**：规则在 `FitFileNaming`；文件落在 `context.filesDir/fits`，临时文件在 `cacheDir/fit_import`。

## 约定

- 代码注释和用户可见文案为**简体中文**（错误信息、通知文案均是）；保持这一风格。
- `System.currentTimeMillis()` 用于持久化的 epoch ms；测试里需要可控时间时用依赖注入或 `calculatePullSince` 这类纯函数（已有 `SyncWindowTest` 覆盖）。
- UI 主题在 `ui/theme/Theme.kt`。`design-system/fitsync/MASTER.md` 是早期生成的 Web 端设计参考（配色/字体为 CSS 变量），与 Compose 实现不完全对应，作灵感参考即可。
- 文档：`README.md`（总览）、`docs/platform-capabilities.md`（五平台能力矩阵与执行模式语义）。
