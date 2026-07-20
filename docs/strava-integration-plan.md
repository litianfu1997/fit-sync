# Strava 双向接入说明

## 接入边界

FitSync 保持纯 Android、无自建服务器。Strava 首版支持后台双向同步：活动列表和 FIT 上传使用官方 OAuth API；原始活动文件使用网页登录 Cookie 请求 `GET /activities/{id}/export_original`。网页能力属于实验性路径，失效时只暂停下载，不影响 OAuth 上传。

Strava 原始导出会保持最初上传格式，可能为 FIT、FIT.GZ、TCX 或 GPX。FitSync 仅自动导入 FIT/FIT.GZ，不转换 TCX/GPX，也不通过 streams 重建 FIT。

## 用户配置

1. 在 [Strava API 设置](https://www.strava.com/settings/api)创建个人开发者应用。
2. Callback Domain 填 `localhost`。
3. 在 FitSync 平台页录入 Client ID 和 Client Secret。
4. OAuth 固定回调为 `com.fitsync.app://localhost/strava/oauth`，请求 scope 为 `read,activity:read_all,activity:write`。
5. OAuth 完成后可继续登录 Strava 网页，以启用后台原文件拉取；跳过网页登录时仍可上传。

Client Secret、access token、refresh token、scope、state、过期时间和 athlete ID 使用 Android Keystore 加密保存，不写日志、Room、BuildConfig 或版本库。纯客户端应用只能保护静态存储，不能把用户自己的 Client Secret 变成真正的服务器秘密。

## 读取流程

- 列表：`GET https://www.strava.com/api/v3/athlete/activities`，使用 `after`、`before`、`page` 和 `per_page=100`；沿用首次 90 天、增量 48 小时重叠窗口。
- 下载：`GET https://www.strava.com/activities/{activityId}/export_original`，仅携带 WebView Cookie，不携带 OAuth token。
- 安全：最多 3 次 HTTPS 跳转；跨域不转发会话头；压缩前后各限制 50 MB；结合 Content-Disposition 与文件魔数识别内容。
- 导入：FIT 直接进入统一导入服务；FIT.GZ 安全解压后进入同一服务；HTML/JSON、TCX、GPX 和未知格式不会保存为 FIT。
- 防重：导入成功写入真实 Strava activity ID 对应的 PlatformCopy。`NO_ORIGINAL_FILE` 和 `UNSUPPORTED_ORIGINAL_FORMAT` 写入 `remote_activity_skip`，临时网络、Cookie、429 和 5xx 错误不写永久跳过表。

用户在活动页手工点击“导出原来的活动”时，WebView DownloadListener 会接管下载，并走相同的导入、PlatformCopy 和 DeliveryPlanner 流水线。

## 上传流程

- `POST /api/v3/uploads` multipart 字段包含 `file`、`data_type=fit` 和 `external_id=fitsync-{实际文件sha256}.fit`。
- 有本地标题时传 `name`；室内骑行传 `trainer=true`；不修改描述、通勤或隐私设置。
- 接受上传后把 upload ID 保存到 DeliveryJob，状态改为 `WAITING_REMOTE_PROCESS`。
- 后续只调用 `GET /uploads/{uploadId}`，成功后以真实 activity ID 写入 PlatformCopy；处理中每 2 秒查询，临时失败退避但保留 upload ID。
- 401 只刷新并重试一次；403 要求重新授权；408、429、5xx 可重试；明确格式错误永久失败；重复映射为 `DUPLICATE_REMOTE`。

## 动态能力

| 登录态 | 活动列表 | 原文件下载 | FIT 上传 |
|---|---|---|---|
| OAuth + Web Cookie | 后台 | 后台 | 后台 |
| 仅 OAuth | 后台 | 手工网页 | 后台 |
| OAuth 失效 | 暂停 | 暂停 | 暂停 |

Strava 可作为来源和目标。网页 Cookie 失效时账号保持 ACTIVE 并标记需要处理，已有来源规则与上传任务继续保留。

当前 API Base URL 集中为 `https://www.strava.com/api/v3`。Strava 公告的新地址 `https://api-v3.strava.com` 在 2027-01-04 可用后，应单独验证并替换单一常量。
