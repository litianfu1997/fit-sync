# 五平台能力矩阵（纯 Android 本地版）

> 应用不经过自建服务器。用户在 App 内 WebView 自行登录；运行时验证失败时会降级为前台网页或手动分享，不绕过验证码和二次认证。

| 平台 | 活动发现/原始 FIT | FIT 上传 | 当前执行模式 |
|------|------------------|----------|--------------|
| 迈金 ONELAP | 已实现 | 已实现 | 登录有效时后台；令牌失效先刷新，失败后要求重新登录 |
| 行者 | 已实现：`/api/v1/pgworkout/`、`/api/v1/workout/{id}/fit/` | 已实现：`/api/v1/fit/upload/` | Cookie 后台请求优先，网页上传兜底 |
| Garmin Connect | 网页导出 | 已实现：`connectapi` 的 `/upload-service/upload` + 兼容状态轮询 | 登录有效时后台上传；临时失败自动重试，不降级为手动上传 |
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

## Garmin 上传说明

- 会话校验：WebView 同源 profile API 验证成功后，提取 `JWT_WEB` 并由 Android Keystore 加密保存；普通 SSO/追踪 Cookie 不再被误判为可同步登录态。
- 后台上传：携带 `JWT_WEB`、`DI-Backend`、`Origin` 和 `Referer` 直连 `connectapi.garmin.com` / `connectapi.garmin.cn`，POST `/upload-service/upload`。
- CSRF：现行 API 默认不要求；若首个请求返回 403 且 WebView 捕获过 CSRF，会携带当前 token 重试一次。
- 服务端直接返回导入结果时立即记录活动 ID；返回异步状态地址时继续轮询，且只接受当前 Garmin 区域的可信 HTTPS 地址。
- 网络、限流、服务端异常和处理超时进入自动退避重试；明确的文件拒绝标记失败。只有授权真正失效时要求重新登录，登录恢复后等待任务会自动续跑，不提供手动上传 FIT 兜底。
