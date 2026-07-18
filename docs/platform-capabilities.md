# 五平台能力矩阵（纯 Android 本地版）

> 应用不经过自建服务器。用户在 App 内 WebView 自行登录；运行时验证失败时会降级为前台网页或手动分享，不绕过验证码和二次认证。

| 平台 | 活动发现/原始 FIT | FIT 上传 | 当前执行模式 |
|------|------------------|----------|--------------|
| 迈金 ONELAP | 已实现 | 已实现 | 登录有效时后台；令牌失效先刷新，失败后要求重新登录 |
| 行者 | 已实现：`/api/v1/pgworkout/`、`/api/v1/workout/{id}/fit/` | 已实现：`/api/v1/fit/upload/` | Cookie 后台请求优先，网页上传兜底 |
| Garmin Connect 中国站 | 已实现：`gc-api/activitylist-service` | 已实现：`gc-api/download-service/files/activity/{id}/fit`、`gc-api/upload-service/upload` | 登录有效时后台双向同步；JWT_WEB/CSRF 失效要求重新登录（实验性） |
| COROS 高驰 | 已实现：`activity/query` + `activity/detail/download` | 已实现：OSS 直传 + `activity/fit/import` | 账号密码 API 登录，后台 list→download→upload（实验性，仅中国站） |
| iGPSPORT | 已实现：`web-analyze/activity/queryMyActivity` + OSS 公共读直链 | 已实现：OSS 直传 + `uploadByOss` | 账号密码 API 登录，后台 list→download→upload（实验性） |

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
- 执行方式：三个方向都是 `BACKGROUND`，全部走中国站网页端实际使用的同源代理 `connect.garmin.cn/gc-api/*`，统一带 `Cookie` + `NK: NT` + `DI-Backend: connectapi.garmin.cn` + `Connect-Csrf-Token` + `X-Requested-With`：
  - 列表：`GET gc-api/activitylist-service/activities/search/activities?start=&limit=`（offset 分页，客户端按增量窗口过滤）。
  - 下载：`GET gc-api/download-service/files/activity/{id}/fit`（裸 `.FIT`；注意不带 `/fit` 后缀返回的是 ZIP）。
  - 上传：`POST gc-api/upload-service/upload`（multipart 字段 `file`）。
- 上传响应：同步返回 `detailedImportResult`——`successes[].internalId` 为新活动 ID；重复返回 HTTP 409，`failures[].messages[].code=202` 视为已存在。
- 失效处理：仅 HTTP 401 或明确跳回登录页时把账号标为 `AUTH_EXPIRED`；含风控、CSRF、代理拒绝等多种语义的 HTTP 403 按可重试失败处理，不再误报登录失效。平台页“重新验证”会用中国站活动列表做真实只读探活，而不是只检查本地是否保存过 JWT。
- 实验性：依赖逆向的私有接口，可能随 Garmin 调整而失效；失效时降级为「重新登录」提示，不会静默丢任务。

## iGPSPORT 同步说明

- 范围：仅支持 iGPSPORT 中国站（后端 `prod.zh.igpsport.com`，前端 `app.igpsport.cn`）。
- 认证：**纯 API 账号密码登录**（不走 WebView）。`POST /service/auth/account/login` body `{appId:"igpsport-web", username, password}` 返回 JWT `access_token`（7 天有效，payload 含 `memberid`）。账号密码经 Android Keystore 加密持久化；JWT 过期且无刷新接口，故 token 失效时用持久化的账号密码自动重登（`IgpsportApiConnector.withRelogin`），实现全自动。
- 执行方式：list/download/upload 均为 `BACKGROUND`：
  - 列表：`GET /service/web-gateway/web-analyze/activity/queryMyActivity?reqType=1&sportType=0&pageNo=&pageSize=&sort=1`（pageNo 分页，客户端按增量窗口过滤）。
  - 下载：列表行 `fitUrl`（或回退 `queryActivityDetail/{rideId}` 的 `fitOssPath`）是阿里云 OSS **公共读**直链 `igp-zh.oss-cn-hangzhou.aliyuncs.com/{uuid}.fit`，匿名 GET。
  - 上传：`getSignedUrl?fileExtension=.fit` 拿 OSS 直传地址 → `PUT` FIT 字节到签名 URL（匿名且不附带 `Content-Type`，与中国站网页端保持一致）→ `POST uploadByOss {fileName, ossName:ossId}` 创建活动（异步解析，响应 data 多为 null，用 ossId 作临时 remoteActivityId，下次 list 按 sha256 对齐）。
- 失效处理：仅中国站业务 API 的 401/403 触发账号密码静默重登；OSS 直传失败按投递失败重试，不会把账号误标为 `AUTH_EXPIRED`。
- 实验性：依赖逆向的私有接口，可能随 iGPSPORT 调整而失效。

## COROS 同步说明

- 范围：仅支持 COROS 中国站（后端 `teamcnapi.coros.com`，区域 regionId=2）。非中国站账号登录会被拒绝。
- 网页入口：`https://t.coros.com/admin/views/activities`，活动列表页同时提供“导入数据”和“导出数据”；平台页在账号有效时的“打开平台”会直接进入这里。
- 认证：**纯 API 账号密码登录**（不走 WebView）。`POST /account/login` body `{account, accountType:2, pwd:MD5(明文)}` 返回 `accessToken + userId + regionId`。accessToken **无刷新接口**，且网页端登录会顶掉 API token，故登录时一并把账号密码（Keystore 加密）持久化，token 失效时用持久化账号密码自动重登（`CorosApiConnector.withRelogin`）。后续业务请求带小写 `accesstoken` 头（**非** Authorization Bearer）。
- 执行方式：list/download/upload 均为 `BACKGROUND`：
  - 列表：`GET /activity/query?size=&pageNumber=`（活动 ID = `labelId`，分页看 `totalPage`，客户端按增量窗口过滤；`sportType` 写入 metadata 供下载用）。
  - 下载：`POST /activity/detail/download?labelId=&sportType=&fileType=4`（fileType=4=FIT）拿 `data.fileUrl`，再匿名 GET（阿里云 OSS 直链 `coros-oss.oss-cn-beijing.aliyuncs.com`）。
  - 上传：① `GET faq.coros.com/openapi/oss/sts`（固定 sign/app_id）拿 STS 临时凭证——盐串污染的 base64 解码 ② FIT 打 zip ③ 用 STS 凭证按阿里云 OSS **V1 签名（HMAC-SHA1）** PUT 到 `coros-oss/fit_zip/{userId}/{md5}.zip` ④ `POST /activity/fit/import`（multipart 字段 `jsonParameter`，`serviceName="oss"`，`md5`=原始 FIT 的、`size`=zip 的）。import 异步，用 fitMd5 作临时 remoteActivityId，下次 list 按 sha256 对齐。
- 失效处理：业务 401 触发账号密码静默重登；OSS/STS 失败按可重试失败处理，不把账号误标 `AUTH_EXPIRED`（STS 失效重拿即可）。
- 实验性：依赖逆向的私有接口与硬编码常量（sign/app_id/盐），可能随 COROS 调整失效；失效时降级为失败任务并提示，不会静默丢任务。中国站访问不要走 VPN/代理。
