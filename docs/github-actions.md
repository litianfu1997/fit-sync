# 在 GitHub 手动构建并发布 APK

工作流：[`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml)。只配置 `workflow_dispatch`，推送代码、提交 PR、创建标签均不会触发构建。

每次点击运行后，GitHub 使用 JDK 21 和项目的 Gradle Wrapper 执行 Release 单元测试、压缩构建与签名校验，然后把正式 APK 和 `SHA256SUMS` 发布到本仓库的 **Releases**。运行期间无需连接手机或模拟器。

## 首次配置

### 1. 将工作流提交到默认分支

工作流文件需要先进入仓库默认分支（本仓库为 `main`），GitHub 才会显示 **Run workflow** 按钮。运行时可以选择其他包含该工作流的分支。

### 2. 配置正式签名 Secrets

打开仓库 **Settings → Secrets and variables → Actions → New repository secret**，创建以下四项 **Repository secrets**：

| Secret 名称 | 内容 | 对应本机 `keystore.properties` |
| --- | --- | --- |
| `FITSYNC_KEYSTORE_BASE64` | 正式签名密钥库文件的 Base64 内容 | `storeFile` 指向的文件内容 |
| `FITSYNC_KEYSTORE_PASSWORD` | 密钥库密码 | `storePassword` |
| `FITSYNC_KEY_ALIAS` | 签名密钥别名 | `keyAlias` |
| `FITSYNC_KEY_PASSWORD` | 签名密钥密码 | `keyPassword` |

如已有正式版安装包，请沿用它的签名密钥。Android 覆盖安装要求签名一致；密钥需要自行备份并保留用于后续版本。

在 Windows PowerShell 中，把实际的密钥库路径替换到以下命令，可将 Base64 内容复制到剪贴板，然后粘贴到 `FITSYNC_KEYSTORE_BASE64`：

```powershell
$signingFile = (Resolve-Path -LiteralPath '.\keystore\fitsync-release.jks').Path
[Convert]::ToBase64String([IO.File]::ReadAllBytes($signingFile)) | Set-Clipboard
```

Base64 只是文件编码，请把它存入 **Secrets**，不要存到 Variables、README 或代码文件中。工作流只在临时目录还原密钥，口令通过环境变量传入 Gradle；运行结束清理密钥文件，不把它作为附件上传。

本机仍可使用原有的 `keystore.properties`。CI 环境变量优先于本机属性，分别为 `FITSYNC_KEYSTORE_FILE`、`FITSYNC_KEYSTORE_PASSWORD`、`FITSYNC_KEY_ALIAS`、`FITSYNC_KEY_PASSWORD`。

### 3. 确认 Actions 可以运行

仓库 **Settings → Actions → General** 需要允许 GitHub Actions，以及工作流中使用的 `actions/checkout`、`actions/setup-java`、`gradle/actions/setup-gradle`。工作流已为发布任务声明 `contents: write`，使用 GitHub 提供的 `GITHUB_TOKEN` 创建 Release，无需另配个人访问令牌。组织策略若限制了此权限，需要管理员允许。

## 每次发布怎么点

1. 打开仓库的 **Actions** 页面。
2. 左侧选择 **手动构建并发布 APK**。
3. 点击 **Run workflow**，选择待发布的分支，通常为 `main`。
4. **版本号**可留空使用项目版本，也可填写 `0.2.1`、`0.3.0-beta.1` 等；需要测试版时勾选 **标记为预发布版本**。
5. 再点击 **Run workflow**，等待任务成功。
6. 打开仓库 **Releases**，在最新发布的 **Assets** 下下载 `FitSync-…apk`。本次任务的 Summary 也会提供下载链接。

正式发布会更新 Releases 的 **Latest**；预发布不会替换正式版的 Latest。

## 版本与文件命名

- 应用展示版本 `versionName` 默认来自 `app/build.gradle.kts`，也可以在运行表单中覆盖。该覆盖只影响本次 APK，不修改源码。
- CI 安装版本号 `versionCode = 100000 + github.run_number`，每次新点击运行递增，便于后续覆盖升级。同一次运行的 **Re-run jobs** 使用相同 versionCode。
- 发布标签包含版本号、运行编号与重跑次数，例如 `v0.2.1-build.12.1`；对应 APK 为 `FitSync-0.2.1-build.12.1.apk`。标签指向本次构建的准确提交，不覆盖历史发布。
- `SHA256SUMS` 包含 APK 的 SHA-256。Linux 可在下载目录执行 `sha256sum -c SHA256SUMS`；Windows 可用 `Get-FileHash -Algorithm SHA256 .\FitSync-0.2.1-build.12.1.apk` 对照校验值。
- 本机未设置 `FITSYNC_VERSION_CODE` / `FITSYNC_VERSION_NAME` 时仍使用源码中的版本值。若要覆盖安装 CI 正式版，本机打包也需要相同签名与不低于已安装版本的 versionCode。

## 常见问题

**看不到 Run workflow 按钮**：先确认工作流文件已提交到默认分支，已登录有写权限的账号，并且仓库启用了 Actions。

**提示缺少 Secret**：检查名称是否完全一致，且放在 Repository secrets；工作流未绑定 Environment，不会读取仅存在于某个 Environment 的 Secret。

**签名失败**：检查 Base64 是否来自正确密钥文件，以及密码和别名是否匹配。不会在云端临时创建新密钥替代正式签名。

**发布提示 `Resource not accessible by integration` / HTTP 403**：检查仓库或组织的 Actions 权限、发布和标签规则，是否允许 `GITHUB_TOKEN` 创建标签及 Release。

**发布失败后留下草稿**：工作流会先创建草稿并上传附件，全部成功才公开。可在 Releases 检查该草稿；重跑会使用新的标签，不会覆盖它。

**构建失败**：查看对应步骤的日志。单元测试失败、签名无效或 Gradle 构建失败时，不会进入发布步骤。

参考：[GitHub 手动运行工作流](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)、[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub CLI 发布命令](https://cli.github.com/manual/gh_release_create)。
