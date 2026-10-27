# 文档配图

这些资源用于 [项目 README](../../README.md) 和 [GitHub Pages 主页](../index.html)，随仓库提供，采用根目录的 [MIT 许可证](../../LICENSE)。平台名称仅用于说明兼容对象。

## 矢量图

| 文件 | 用途 |
| --- | --- |
| [fitsync-banner.svg](fitsync-banner.svg) | 项目首页封面，沿用应用图标的橙色折线与起终点元素 |
| [sync-architecture.svg](sync-architecture.svg) | 本机导入、活动去重、任务投递与平台副本确认的架构示意 |
| [fitsync-mark.svg](fitsync-mark.svg) | 网页品牌标记与 favicon，沿用应用图标元素 |

SVG 不依赖外部字体文件、图片或脚本，可直接修改源文件。架构中的代码实体对应当前仓库实现；详细关系与时序图见 [架构文档](../architecture.md)。

## 界面截图

截图于 2026-10-27 使用当前 Debug 构建，在独立 Android 14（API 34）模拟器中采集，分辨率 1080 × 2400，亮色主题。

| 文件 | 页面 |
| --- | --- |
| [overview.png](screenshots/overview.png) | 概览、活动数量与投递结果 |
| [activities.png](screenshots/activities.png) | 活动列表与类型筛选 |
| [activity-detail.png](screenshots/activity-detail.png) | 活动详情与运动指标 |
| [manual-sync.png](screenshots/manual-sync.png) | 一次性同步的来源、目标与类型选择 |
| [automation.png](screenshots/automation.png) | 来源到目标的自动化路径 |
| [platforms.png](screenshots/platforms.png) | 六个平台的连接入口 |

所有活动、账号状态和任务均为合成演示数据，模拟器关闭网络，未登录任何真实平台；轨迹缩略图也是生成的示例曲线。PNG 由 Android `screencap` 直接输出，未修改页面中的文字或同步状态。

截图用于展示界面，不作为六个平台均已完成真实同步验证的证据。更新截图时应继续使用独立演示环境，避免把真实账号、Cookie、活动地点或路线写入公开仓库。

## 网页轻量配图

`site/` 中的 `overview.webp`、`activities.webp`、`manual-sync.webp`、`automation.webp` 由上述对应 PNG 缩放至 720 × 1600 并压缩得到，用于网页首屏和交互预览。四张图片合计约 184 KiB，比对应 PNG 小约 81%；演示内容未改动，README 使用的原始 PNG 保留。
