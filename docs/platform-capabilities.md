# 六平台能力矩阵（纯 Android 本地版）

> 应用不经过自建服务器。用户在 App 内 WebView 自行登录；运行时验证失败时会降级为前台网页或手动分享，不绕过验证码和二次认证。

| 平台 | 活动发现/原始 FIT | FIT 上传 | 当前执行模式 |
|------|------------------|----------|--------------|
| 迈金 ONELAP | 已实现 | 已实现 | 登录有效时后台；令牌失效先刷新，失败后要求重新登录 |
| 行者 | 已实现：`/api/v1/pgworkout/`、`/api/v1/workout/{id}/fit/` | 已实现：`/api/v1/fit/upload/` | Cookie 后台请求优先，网页上传兜底 |
| Garmin Connect 中国站 | 已实现：`gc-api/activitylist-service` | 已实现：`gc-api/download-service/files/activity/{id}/fit`、`gc-api/upload-service/upload` | 登录有效时后台双向同步；JWT_WEB/CSRF 失效先静默续期，SSO 也失效才要求重新登录（实验性） |
| COROS 高驰 | 已实现：`activity/query` + `activity/detail/download` | 已实现：OSS 直传 + `activity/fit/import` | 账号密码 API 登录，后台 list→download→upload（实验性，仅中国站） |
| iGPSPORT | 已实现：`web-analyze/activity/queryMyActivity` + OSS 公共读直链 | 已实现：OSS 直传 + `uploadByOss` | 账号密码 API 登录，后台 list→download→upload（实验性） |
| Strava | 官方 `/athlete/activities` + 网页 `/activities/{id}/export_original` | 官方 `/uploads` + 状态轮询 | OAuth 有效时列表/上传后台执行；网页 Cookie 有效时原文件后台下载（读取侧实验性） |

## 运行时能力

- `BACKGROUND`：Worker 可在有效登录状态下直接拉取或投递。
- `FOREGROUND_WEB`：创建 `WAITING_USER` 任务，通知用户打开平台网页；文件选择器可直接提供待上传 FIT。
- `MANUAL`：提示用户下载后通过 Android 系统分享导入。
- `UNSUPPORTED`：UI 不允许把该方向当作自动同步能力。

## 默认规则

- 每个平台只绑定一个账号。
- 首次拉取最近 90 天，后续使用 48 小时重叠窗口；WorkManager 周期为 15 分钟，但实际时间由 Android 系统决定。
- 默认只投递规则启用后的新活动；历史回填必须单独勾选。
- “本地导入”表示已进入 FitSync 本地活动库的全部活动（无论最初来自分享还是云平台），可单独配置目标平台。

## 上传确认与失败恢复

- 目标平台始终先拉取、再生成或执行投递任务，避免本地缺少 `PlatformCopy` 时重复上传远端已有活动。
- 只有平台返回真实活动 ID，或目标列表出现唯一高置信匹配（精确文件标识，或唯一的时间 + 距离/标题匹配）后，才写入 `PlatformCopy`；不会把 ossId、MD5、upload ID 等临时值伪装成活动 ID。
- 迈金、行者和 Garmin 在“请求已受理但没有活动 ID”时进入带 30 分钟超时的确认状态；iGPSPORT、COROS 也使用同一确认机制。Garmin 有状态地址时继续轮询真实状态接口，期间不会重新 POST FIT。
- Strava 使用官方 upload ID 轮询；重复响应即使只在错误文本中包含原活动 ID，也会提取真实 ID 并记录远端副本。
- 单次 Worker 会连续排空可立即执行的任务；遇到平台处理等待或网络退避会持久化续跑。目标平台临时拉取失败且仍有活跃任务时，15 分钟后继续尝试，即使自动同步开关已关闭也不会让已提交任务静默搁置。

## 后台同步与系统限制

- 周期任务由 WorkManager 调度，**目标间隔 ≥15 分钟**；Doze、待机桶与厂商「智能后台」可能显著推迟执行。
- 同步 Worker 运行期间会提升为 `dataSync` 前台服务并显示低打扰进度通知；Android 13+ 需授予通知权限，否则进度与「需要操作」提醒可能静默失败。
- 应用会检测「电池优化 / 后台限制 / 通知权限」：设置页提供「后台运行保护」引导，首页在受限时显示可关闭横幅；侧载场景可一键申请忽略电池优化。
- 小米、华为、荣耀、OPPO/一加/realme、vivo/iQOO、三星等提供品牌相关说明，并尝试跳转自启动/省电页；跳转失败则打开应用详情。
- 开机与覆盖安装后会补注册周期任务，与冷启动调度互补；**不能**保证在被厂商强杀后仍按时运行，长期无「最近成功同步」更新时请检查系统白名单。

## Strava 同步说明

- 账号：每位用户自行创建 Strava 开发者应用，在 FitSync 中录入 Client ID / Client Secret。开发者后台的 Callback Domain 填 `localhost`，固定回调 URI 为 `com.fitsync.app://localhost/strava/oauth`。
- OAuth：请求 `read,activity:read_all,activity:write`，access token 到期前 5 分钟自动刷新；refresh token 轮换时原子保存最新值。OAuth 失效只要求重新授权。
- 读取：活动列表使用官方 API；原始文件使用 Strava 网页已提供的 `export_original` 路由和 WebView Cookie。支持 FIT/FIT.GZ；TCX、GPX 与未知格式不转换，并写入永久跳过记录，避免周期同步反复失败。
- 会话隔离：网页 Cookie 失效时账号仍保持可上传，已有规则和 Strava 上传任务不删除；用户重新登录网页后恢复来源拉取。
- 下载安全：初始请求固定 `https://www.strava.com`，最多跟随 3 次 HTTPS 跳转；跨域跳转不携带 Cookie 或 OAuth 头；压缩前后各限制 50 MB；HTML/JSON 错误页不会进入 FIT 导入。
- 上传：官方 Upload API 使用稳定的 `external_id=fitsync-{sha256}.fit`。任务保存 upload ID，App 或 Worker 中断后只恢复轮询，不会重新 POST 文件；兼容官方 `id_str` 64 位标识与错误文本中的重复活动 ID。
- 边界：`export_original` 是未公开网页能力，整体连接标记为实验性。接口变化时降级为打开活动页，让用户点“导出原来的活动”，不会用 streams 重建或伪造 FIT。

## Garmin 同步说明

- 范围：仅支持 Garmin 中国站。登录页面固定为 `connect.garmin.cn`，WebView 不放行 Garmin 国际站。
- 认证：用户在 WebView 完成登录后点「验证并保存登录」，应用从 `connect.garmin.cn` 抓取 `JWT_WEB` 会话 Cookie + `<meta name="csrf-token">` 里的 `Connect-Csrf-Token`，并单独保存 `sso.garmin.cn` 的 `CASTGC` 长会话；凭据、登录域和 UA 均经 Keystore 加密持久化，用于后台请求与静默续期。
- 执行方式：三个方向都是 `BACKGROUND`，全部走中国站网页端实际使用的同源代理 `connect.garmin.cn/gc-api/*`，统一带 `Cookie` + `NK: NT` + `DI-Backend: connectapi.garmin.cn` + `Connect-Csrf-Token` + `X-Requested-With`：
  - 列表：`GET gc-api/activitylist-service/activities/search/activities?start=&limit=`（offset 分页，客户端按增量窗口过滤）。
  - 下载：`GET gc-api/download-service/files/activity/{id}/fit`（裸 `.FIT`；注意不带 `/fit` 后缀返回的是 ZIP）。
  - 上传：`POST gc-api/upload-service/upload`（multipart 字段 `file`）。
- 上传响应：同步或状态轮询返回 `detailedImportResult`——`successes[].internalId` 为新活动 ID；重复返回 HTTP 409，`failures[].messages[].code=202` 视为已存在。中国站状态代理可能用 HTTP 5xx 包装合法业务结果，应用会优先解析正文，不会因外层状态码丢失成功/重复/拒绝结果。
- 失效处理：活动列表、FIT 下载、上传和上传状态轮询首次收到 403 时，会先用 `CASTGC` 静默换发 `JWT_WEB`/CSRF，并只重试原 HTTP 请求一次；刷新失败或重试仍为普通 403 时保持账号有效并按可重试失败处理。仅刷新后仍为 HTTP 401 或明确跳回登录页时把账号标为 `AUTH_EXPIRED`。平台页“重新验证”会用中国站活动列表做真实只读探活，而不是只检查本地是否保存过 JWT。
- 实验性：依赖逆向的私有接口，可能随 Garmin 调整而失效；失效时降级为「重新登录」提示，不会静默丢任务。

## iGPSPORT 同步说明

- 范围：仅支持 iGPSPORT 中国站（后端 `prod.zh.igpsport.com`，前端 `app.igpsport.cn`）。
- 认证：**纯 API 账号密码登录**（不走 WebView）。`POST /service/auth/account/login` body `{appId:"igpsport-web", username, password}` 返回 JWT `access_token`（7 天有效，payload 含 `memberid`）。账号密码经 Android Keystore 加密持久化；JWT 过期且无刷新接口，故 token 失效时用持久化的账号密码自动重登（`IgpsportApiConnector.withRelogin`），实现全自动。
- 执行方式：list/download/upload 均为 `BACKGROUND`：
  - 列表：`GET /service/web-gateway/web-analyze/activity/queryMyActivity?reqType=1&sportType=0&pageNo=&pageSize=&sort=1`（pageNo 分页，客户端按增量窗口过滤）。
  - 下载：列表行 `fitUrl`（或回退 `queryActivityDetail/{rideId}` 的 `fitOssPath`）是阿里云 OSS **公共读**直链 `igp-zh.oss-cn-hangzhou.aliyuncs.com/{uuid}.fit`，匿名 GET。
  - 上传：`getSignedUrl?fileExtension=.fit` 拿 OSS 直传地址 → `PUT` FIT 字节到签名 URL（匿名且不附带 `Content-Type`，与中国站网页端保持一致）→ `POST uploadByOss {fileName, ossName:ossId}` 创建活动。响应只表示异步受理，ossId 仅保存在投递确认令牌中；下次 list 取得真实 rideId 后才写入平台副本。
- 失效处理：仅中国站业务 API 的 401/403 触发账号密码静默重登；OSS 直传失败按投递失败重试，不会把账号误标为 `AUTH_EXPIRED`。
- 实验性：依赖逆向的私有接口，可能随 iGPSPORT 调整而失效。

## COROS 同步说明

- 范围：仅支持 COROS 中国站（后端 `teamcnapi.coros.com`，区域 regionId=2）。非中国站账号登录会被拒绝。
- 网页入口：`https://t.coros.com/admin/views/activities`，活动列表页同时提供“导入数据”和“导出数据”；平台页在账号有效时的“打开平台”会直接进入这里。
- 认证：**纯 API 账号密码登录**（不走 WebView）。`POST /account/login` body `{account, accountType:2, pwd:MD5(明文)}` 返回 `accessToken + userId + regionId`。accessToken **无刷新接口**，且网页端登录会顶掉 API token，故登录时一并把账号密码（Keystore 加密）持久化，token 失效时用持久化账号密码自动重登（`CorosApiConnector.withRelogin`）。后续业务请求带小写 `accesstoken` 头（**非** Authorization Bearer）。
- 执行方式：list/download/upload 均为 `BACKGROUND`：
  - 列表：`GET /activity/query?size=&pageNumber=`（活动 ID = `labelId`，分页看 `totalPage`，客户端按增量窗口过滤；`sportType` 写入 metadata 供下载用）。
  - 下载：`POST /activity/detail/download?labelId=&sportType=&fileType=4`（fileType=4=FIT）拿 `data.fileUrl`，再匿名 GET（阿里云 OSS 直链 `coros-oss.oss-cn-beijing.aliyuncs.com`）。
  - 上传：① `GET faq.coros.com/openapi/oss/sts`（固定 sign/app_id）拿 STS 临时凭证——盐串污染的 base64 解码 ② FIT 打 zip ③ 用 STS 凭证按阿里云 OSS **V1 签名（HMAC-SHA1）** PUT 到 `coros-oss/fit_zip/{userId}/{md5}.zip` ④ `POST /activity/fit/import`（multipart 字段 `jsonParameter`，`serviceName="oss"`，`md5`=原始 FIT 的、`size`=zip 的）。import 只表示异步受理，fitMd5 仅作为确认标识；下次 list 取得真实 labelId 后才写入平台副本。
- 失效处理：业务 401 触发账号密码静默重登；OSS/STS 失败按可重试失败处理，不把账号误标 `AUTH_EXPIRED`（STS 失效重拿即可）。
- 实验性：依赖逆向的私有接口与硬编码常量（sign/app_id/盐），可能随 COROS 调整失效；失效时降级为失败任务并提示，不会静默丢任务。中国站访问不要走 VPN/代理。
