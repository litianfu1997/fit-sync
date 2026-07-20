# 骑行记录搬家（FitSync）

纯 **Android** 应用：把骑行活动在迈金 / 行者 / Garmin 中国站 / iGPSPORT / COROS / Strava 之间搬家与补齐。

**无自建服务器。** 活动中台、去重、同步任务、原始 FIT 全部在本机。

## 核心能力

1. 通过系统分享或登录平台网页收集 FIT  
2. 保存 WebView 登录 Cookie；需要持久化的平台令牌与账号密码使用 Android Keystore 加密
3. 六个平台均可参与后台双向同步；Strava 活动列表与上传走官方 OAuth API，原始文件下载依赖实验性的网页 `export_original` 会话
4. 各平台按其实际机制适配认证：Strava 使用用户自备开发者应用的 OAuth + 独立网页 Cookie；迈金/行者复用 WebView Cookie 与令牌；Garmin 中国站抓取 JWT_WEB + CSRF；iGPSPORT/COROS 走账号密码 API 登录
5. 本地四级去重，PlatformCopy + 幂等任务阻止重复与循环同步  
6. 配置“来源平台 → 多个目标平台”规则，并支持单次补发  

## 技术栈

- Kotlin · Jetpack Compose · Material 3  
- Room（SQLite）  
- WorkManager（15 分钟增量同步与投递任务）  
- 本地 FIT 解析引擎（轻量实现，可替换为 Garmin FIT SDK）  

## 工程结构

```text
app/src/main/java/com/fitsync/app/
  data/       Room、文件存储、导入服务
  domain/     去重、领域枚举
  fit/        FIT 校验与解析
  connectors/ 平台读写连接器与运行时能力
  web/        安全 WebView、Cookie 会话
  sync/       规则规划、投递编排、WorkManager
  share/      系统分享接收
  ui/         Compose 界面
```

## 构建

要求：

- JDK 17+（推荐 21）  
- Android SDK Platform 34、Build-Tools 34  

```bash
# Windows PowerShell 示例
$env:JAVA_HOME = "D:\jdk-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:test
```

## 使用

1. 安装 Debug APK  
2. 在“平台”页面登录账号：Strava 先录入个人开发者应用的 Client ID / Client Secret 并完成 OAuth，再按需登录网页启用原文件拉取；迈金/行者/Garmin 使用内置 WebView；iGPSPORT/COROS 直接填写账号密码
3. 在“规则”页面选择来源和目标；启用规则即授权自动上传  
4. 点击首页“立即同步”，或等待系统后台增量同步  
5. 在「设置 → 后台运行保护」允许不受电池优化限制，并按机型说明放开自启动（部分手机智能后台会推迟或杀掉同步）  
6. 仍可从任意平台将 FIT 分享到本应用，作为“本地导入”来源  

## 隐私

- 数据仅存本机  
- 迈金 / 行者 / Garmin：主要保存 WebView Cookie 与会话令牌（Keystore 加密敏感字段）  
- iGPSPORT / COROS：账号密码经 Android Keystore 加密保存在本机，**仅用于** access token 失效时自动重登  
- Strava：Client Secret、OAuth token 与 state 经 Android Keystore 加密；纯客户端模式无法把个人 Client Secret 变成真正的服务器秘密。网页 Cookie 仅在 WebView 沙箱内使用，不复制浏览器 Cookie
- Android 自动备份已关闭，Cookie、活动库和 FIT 仅保存在应用沙箱  
- 默认同步成功 7 天后可清理原始 FIT（设置中可改：长期保留 / 成功后删除）；清理只删文件，活动元数据保留  
- 换机不会自动同步库（后续提供导出备份）  

## 文档

- [平台能力矩阵](docs/platform-capabilities.md)  
- [Strava 双向接入说明](docs/strava-integration-plan.md)
