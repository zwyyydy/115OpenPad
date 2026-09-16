# 115 Pad

非官方 115 网盘安卓客户端，基于 [115 生活开放平台](https://www.yuque.com/115yun/open) 官方 API 开发，专为平板/大屏设备优化（官方 App 不支持平板模式是本项目的主要动机）。

## 功能（v0.1.0）

- **登录**：两种方式 —— ① OAuth 2.0 + PKCE 设备码授权（需自己的 AppID），应用内展示二维码，用 115 官方 App 扫码；② **导入 Token**（无需 AppID）：粘贴在 [OpenList Token](https://api.oplist.org/) 等工具处获取的 Access Token / Refresh Token，填了 Refresh Token 即可长期自动续期（官方刷新接口不依赖 AppID）
- **文件**：目录浏览（网格/列表切换）、面包屑导航、排序、分类筛选（视频/图片/音乐/文档/压缩/应用/书籍）、星标、文件搜索、缩略图
- **文件操作**：新建文件夹、重命名、移动、复制、删除（进回收站）、多选批量操作
- **下载**：通过系统 DownloadManager 下载到 `下载/115Pad/`，支持批量；可复制直链
- **视频播放**：ExoPlayer/HLS 在线播放，清晰度切换，播放进度云同步（续播），外挂字幕
- **图片**：全屏预览
- **云下载（离线）**：任务列表 + 进度自动刷新、添加 HTTP/FTP/磁力/电驴任务、**BT 任务（本地解析 .torrent 生成磁力链添加）**、删除任务（可选删除源文件）、配额显示
- **回收站**：列表、还原、彻底删除、清空
- **平板自适应**：宽屏下文件页双栏（左侧用户/目录/分类导航 + 右侧内容区）、NavigationRail 导航；手机上为单栏 + 底部导航

## 未实现 / 已知限制

- **文件上传**：开放平台上传流程涉及「上传凭证 + 对象存储回调 + 断点续传」，v1 未实现
- **BT 任务说明**：.torrent 文件在本地解析出 info_hash 后以磁力链方式添加（官方的 `add_task_bt` 接口要求先把种子文件上传到 115，因此"按文件勾选下载"暂不可用；冷门资源若 DHT 拿不到元数据会一直显示"分配中"）
- 4K/原画播放需要 115 会员
- 官方接口有不明频控，请勿高频刷新

## 使用前提（重要）

1. 在 115 生活开放平台完成**开发者入驻**（个人/企业实名认证，审核约 7 个工作日）
2. 创建应用并审核通过后获得 **AppID (client_id)**
3. 在本 App 登录页填入 AppID，然后用 115 官方 App 扫码授权

> 本应用使用 PKCE 设备码模式，**不需要 AppSecret**，适合无后端的纯客户端。

## 构建

要求：JDK 17、Android SDK（platform 35 / build-tools 35）。

```bash
# 项目自带 local.properties 指向 F:/ad/android-sdk，其他环境请修改或删除后设置 ANDROID_HOME
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

Kotlin + Jetpack Compose (Material 3) + material3-window-size-class（平板自适应）、Retrofit + OkHttp + kotlinx.serialization、Coil（缩略图）、Media3 ExoPlayer（HLS 播放）、DataStore（令牌存储）、ZXing（二维码生成）、系统 DownloadManager（下载）。

## 目录

- `app/src/main/java/com/open115/pad/data/` — API 定义、模型、会话/令牌管理
- `app/src/main/java/com/open115/pad/ui/` — Compose 界面（登录/文件/云下载/回收站/设置）
- `app/src/main/java/com/open115/pad/player/` — 视频播放器
- `docs/115-api-notes.md` — 上层 API 笔记（接口路径、参数、响应字段）

## 免责声明

本项目仅供个人学习交流使用，与 115 网盘官方无关。使用请遵守《115生活开放平台开发者协议》及相关法律法规，不得用于多账号共享会员权益等违规用途。
