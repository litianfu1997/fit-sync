# 骑行记录搬家（FitSync）

纯 **Android** 应用：把骑行活动在迈金 / 行者 / Garmin 中国站 / iGPSPORT / COROS 之间搬家与补齐。

**无自建服务器。** 活动中台、去重、同步任务、原始 FIT 全部在本机。

## 核心能力

1. 通过系统分享或登录平台网页收集 FIT  
2. 保存 WebView 登录 Cookie；需要持久化的平台令牌与账号密码使用 Android Keystore 加密
3. 迈金、行者、Garmin 中国站、iGPSPORT、COROS 均支持后台活动发现、原始 FIT 下载与上传；其中行者/Garmin/iGPSPORT/COROS 依赖逆向接口，标记为实验性，可能随平台调整失效  
4. 各平台按其网页端实际机制适配认证：迈金/行者复用 WebView Cookie 与令牌；Garmin 中国站抓取 JWT_WEB + Connect-Csrf-Token 走同源 `gc-api` 代理；iGPSPORT/COROS 走账号密码 API 登录，访问令牌失效时用加密凭据自动重登
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
2. 在“平台”页面登录账号：迈金/行者/Garmin 在内置 WebView 完成登录后点“验证并保存登录”；iGPSPORT/COROS 直接填写账号密码（Keystore 加密保存）  
3. 在“规则”页面选择来源和目标；启用规则即授权自动上传  
4. 点击首页“立即同步”，或等待系统后台增量同步  
5. 仍可从任意平台将 FIT 分享到本应用，作为“本地导入”来源  

## 隐私

- 数据仅存本机  
- 迈金 / 行者 / Garmin：主要保存 WebView Cookie 与会话令牌（Keystore 加密敏感字段）  
- iGPSPORT / COROS：账号密码经 Android Keystore 加密保存在本机，**仅用于** access token 失效时自动重登  
- Android 自动备份已关闭，Cookie、活动库和 FIT 仅保存在应用沙箱  
- 默认同步成功 7 天后可清理原始 FIT（设置中可改：长期保留 / 成功后删除）；清理只删文件，活动元数据保留  
- 换机不会自动同步库（后续提供导出备份）  

## 文档

- [平台能力矩阵](docs/platform-capabilities.md)  
