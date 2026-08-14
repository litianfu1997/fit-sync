# Strava 网页同步说明

## 接入边界

FitSync 不使用 Strava API，不要求用户创建开发者应用，也不保存或刷新 OAuth token。用户在应用内 WebView 登录一次后，同步任务复用该会话自动读取活动、下载原始 FIT，并提交本地 FIT。

## 登录

1. 在 FitSync 的“平台”页面打开 Strava。
2. 在 WebView 中完成 Strava 网页登录。
3. 点击“验证并保存登录”。FitSync 使用当前 Cookie 访问 `https://www.strava.com/upload/select` 做只读探活。

网页登录 Cookie 只保存在应用 WebView 沙箱。Cookie 失效时重新登录，不涉及 Client ID、Client Secret、access token 或 refresh token。

## 拉取流程

1. 使用 WebView Cookie 请求 `GET /athlete/training_activities`，参数包含 `page`、`per_page=20` 和网页要求的空筛选字段。
2. JSON 返回 `models/page/perPage/total`；使用 `id_str`、`start_date_local_raw`、`distance_raw`、`sport_type` 和 `activity_url` 构造远端活动摘要。
3. 按 FitSync 首次 90 天或增量 48 小时重叠窗口分页；遇到早于窗口的活动立即停止翻页。
4. 对尚未导入的活动请求 `GET /activities/{id}/export_original`。FIT 直接导入，FIT.GZ 限流解压后导入；TCX、GPX 与未知格式不会伪装为 FIT。
5. 导入、去重、PlatformCopy 和后续 DeliveryPlanner 继续走现有统一流水线。

## 上传流程

1. 同步规则命中 Strava 目标后，DeliveryJob 以 `BACKGROUND` 模式执行。
2. 连接器携带 WebView Cookie 读取 `GET https://www.strava.com/upload/select`，从官方表单取得 Rails `authenticity_token`。
3. FitSync 校验本地 FIT 存在、非空且不超过 25 MB，以 multipart 字段 `_method=post`、`authenticity_token`、`files[]` 提交到 `POST /upload/files`。
4. 初始响应中的 upload ID 保存到 DeliveryJob，任务进入远端处理等待；后续只轮询 `GET /upload/progress.json?ids[]={uploadId}`，不会重新提交文件。
5. `workflow=success` 且响应带 `activity.id` 后，以真实活动 ID 和 `/activities/{id}` 地址写入 PlatformCopy。明确重复时记录原活动；格式错误等明确失败进入永久失败。

## 页面约束

- 官方页面表单为 `multipart/form-data`，提交到 `/upload/files`，文件字段为 `files[]`。
- 页面支持 FIT、TCX、GPX，单文件最大 25 MB、一次最多 15 个；FitSync 每个任务只提供一个 FIT。
- FitSync 只在用户已完成正常网页登录后复用 Cookie 和页面 CSRF token；不绕过验证码、二次验证或登录验证。
- 上传创建活动后沿用 Strava 从 FIT 推导出的默认标题、类型、装备与隐私设置；FitSync 不再额外调用元数据保存接口。
- 列表、导出和上传均是未公开的网页请求链路，整体标记为实验性。页面或响应结构变化时同步会停止并提示重新验证，不会退回开发者 API。
