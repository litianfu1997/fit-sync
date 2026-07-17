# 五平台能力矩阵（纯 Android 本地版）

> 应用不经过自建服务器。用户在 App 内 WebView 自行登录；运行时验证失败时会降级为前台网页或手动分享，不绕过验证码和二次认证。

| 平台 | 活动发现/原始 FIT | FIT 上传 | 当前执行模式 |
|------|------------------|----------|--------------|
| 迈金 ONELAP | 已实现 | 已实现 | 登录有效时后台；令牌失效先刷新，失败后要求重新登录 |
| 行者 | 已实现：`/api/v1/pgworkout/`、`/api/v1/workout/{id}/fit/` | 已实现：`/api/v1/fit/upload/` | Cookie 后台请求优先，网页上传兜底 |
| Garmin Connect 中国站 | 已实现：`gc-api/activitylist-service` | 已实现：`gc-api/download-service/files/activity/{id}/fit`、`gc-api/upload-service/upload` | 登录有效时后台双向同步；JWT_WEB/CSRF 失效要求重新登录（实验性） |
| COROS 高驰 | 网页/邮件导出 | 网页导入 | 前台网页或手动确认 |
| iGPSPORT | 未验证，保留手动分享 | 网页导入 | 前台网页任务 |

## 运行时能力

- `BACKGROUND`：Worker 可在有效登录状态下直接拉取或投递。
- `FOREGROUND_WEB`：创建 `WAITING_USER` 任务，通知用户打开平台网页；文件选择器可直接提供待上传 FIT。
- `MANUAL`：提示用户下载后通过 Android 系统分享导入。
- `UNSUPPORTED`：UI 不允许把该方向当作自动同步能力。

## 默认规则

- 每个平台只绑定一个账号。
- 首次拉取最近 90 天，后续使用 48 小时重叠窗口；WorkManager 周期为 15 分钟，但实际时间由 Android 系统决定。
- 默认只投递规则启用后的新活动；历史回填必须单独勾选。
- “本地导入”是独立来源，可单独配置目标平台。

## Garmin 同步说明

- 范围：仅支持 Garmin 中国站。登录页面固定为 `connect.garmin.cn`，WebView 不放行 Garmin 国际站。
- 认证：用户在 WebView 完成登录后点「验证并保存登录」，应用从 `connect.garmin.cn` 抓取 `JWT_WEB` 会话 Cookie + `<meta name="csrf-token">` 里的 `Connect-Csrf-Token`，连同登录域、UA 一并 Keystore 加密持久化，用于后续后台请求。
- 执行方式：三个方向都是 `BACKGROUND`，全部走中国站网页端实际使用的同源代理 `connect.garmin.cn/gc-api/*`，统一带 `Cookie` + `NK: NT` + `Connect-Csrf-Token` + `X-Requested-With`：
  - 列表：`GET gc-api/activitylist-service/activities/search/activities?start=&limit=`（offset 分页，客户端按增量窗口过滤）。
  - 下载：`GET gc-api/download-service/files/activity/{id}/fit`（裸 `.FIT`；注意不带 `/fit` 后缀返回的是 ZIP）。
  - 上传：`POST gc-api/upload-service/upload`（multipart 字段 `file`）。
- 上传响应：同步返回 `detailedImportResult`——`successes[].internalId` 为新活动 ID；重复返回 HTTP 409，`failures[].messages[].code=202` 视为已存在。
- 失效处理：`JWT_WEB`/CSRF 过期（HTTP 401/403）时任务转 `WAITING_USER`、账号标 `AUTH_EXPIRED` 并通知重新登录；重新登录后旧 Garmin 任务自动续跑。
- 实验性：依赖逆向的私有接口，可能随 Garmin 调整而失效；失效时降级为「重新登录」提示，不会静默丢任务。
