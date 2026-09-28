# 115 OpenPad

基于 [115 生活开放平台 API](https://www.yuque.com/115yun/open) 的第三方 115 网盘客户端，适配 Android 平板与手机。

## 功能

- **登录**：扫码授权 / 导入 Token，自动续期
- **文件浏览**：列表 / 小图标 / 大图标、搜索、星标、批量操作、快捷目录、分类整理、文本预览
- **媒体库**：海报墙与详情页（简介 / 演员 / 剧照 / 技术参数），剧集归成系列卡；排序 / 筛选 / 全局搜索 / 观影历史；快速 / 增量 / 全量扫描，支持外部刮削流水线信号联动（见 [PAD_SIGNAL.md](./115pad/PAD_SIGNAL.md)）
- **视频播放器**：HLS 流播、清晰度切换、在线字幕、手势操作、倍速、HDR 转 SDR、画面旋转、截图
  - **VR 观看**：180° / 360° 实时反投影
  - **实验室**：视频滤镜、弹幕（兼容弹弹play 协议源，默认关）
- **上传 / 下载**：大文件分片断点续传、文件夹上传；本机下载管理；云下载（磁力 / 电驴 / HTTP / FTP）
- **批量重命名**：查找替换 / 插入 / 自动编号，队列后台执行
- **图片浏览**：大图画廊、超大图分块解码、缓存复用
- **其他**：外部链接与分享唤起、回收站、过滤规则、黑暗模式 + 壁纸、后台保活

## 安装

从 [Releases](../../releases) 下载 APK（Android 8.0+）。

## 更新日志

各版本变更见 [CHANGELOG.md](./CHANGELOG.md)。

## 构建

```bash
git clone https://github.com/zwyyydy/115OpenPad.git
cd 115OpenPad/115pad
./gradlew assembleDebug
```

需要 Android SDK（platform 35）+ JDK 17。

## 已知限制

- BT 任务以磁力链方式提交，不能按文件勾选
- 弹幕需自备弹弹play 协议源，应用不预置地址
- 4K / 原画播放需要 115 会员
- 开放平台接口有频控，请勿高频刷新

## 凭据说明

- **扫码授权**：需在 [115 开放平台](https://pro.115.com/) 创建应用获取 AppID
- **导入 Token**：从 [OpenList Token 工具](https://oplist.org/) 获取即可

## 许可证

[PolyForm Noncommercial 1.0.0](./LICENSE)：个人学习、研究可自由使用，未经授权不得商用。第三方组件见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。

## 免责声明

个人学习项目，与 115 官方无任何关联；使用风险自负，请勿用于商业或违法违规用途。
