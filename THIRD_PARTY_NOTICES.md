# 第三方组件与来源说明

## 移植的代码：DKVideoPlayer（Apache License 2.0）

视频滤镜功能移植自 [Doikki/DKVideoPlayer](https://github.com/Doikki/DKVideoPlayer)（Apache-2.0）：

- `app/src/main/java/com/open115/pad/player/FilterViewportView.kt` 里的片元着色器：
  处理顺序（磨皮 → 亮度 → 对比度 → 饱和度 → 色温/色调 → 分离色调 → gamma → 褪色 →
  单色 → 暗角）与各参数的语义、取值范围，对应上游 `dkplayer-sample` 的
  `.../widget/render/gl2/filter/` 滤镜集（GlBilateralFilter、GlBrightnessFilter、
  GlContrastFilter、GlSaturationFilter、GlHazeFilter、GlHighlightShadowFilter、
  GlGammaFilter、GlMonochromeFilter、GlVignetteFilter 等）
- `app/src/main/java/com/open115/pad/player/VideoFilter.kt` 里的 8 个内置风格预设

本项目所做的修改：把上述滤镜合并成单个着色器，并新增「画面旋转」的坐标反算与取样步长
（`uTexelSize`）两处；渲染走本工程自有的 GL 视窗（与 VR 视窗同一套路数），
未使用上游的播放器 / 渲染框架代码。

Apache License 2.0 全文见 [licenses/Apache-2.0.txt](./licenses/Apache-2.0.txt)，
或 https://www.apache.org/licenses/LICENSE-2.0 。

## 依赖库

均由 Gradle 引入、按各自许可证分发（本仓库不含其源码）：

| 组件 | 许可证 |
| --- | --- |
| AndroidX（core-ktx / activity-compose / lifecycle / navigation / datastore / documentfile） | Apache-2.0 |
| Jetpack Compose（ui / material3 / material-icons-extended / window-size-class） | Apache-2.0 |
| Media3 ExoPlayer（exoplayer / hls / ui） | Apache-2.0 |
| Room（runtime / ktx） | Apache-2.0 |
| Kotlin、kotlinx.coroutines、kotlinx.serialization | Apache-2.0 |
| OkHttp、Retrofit、retrofit2-kotlinx-serialization-converter | Apache-2.0 |
| Coil（coil-compose） | Apache-2.0 |
| ZXing core | Apache-2.0 |
| QuickJS（quickjs-android，插件化 JS 元数据源引擎） | Apache-2.0 |
| JUnit 4（仅测试） | EPL-1.0 |
| kxml2（仅测试，提供 XmlPullParser 实现） | BSD-3-Clause |

## 对接的第三方服务与数据源

以下均为运行期按需远程访问，本仓库不含其代码或数据；地址可在设置里改或关闭：

| 名称 | 用途 | 说明 |
| --- | --- | --- |
| HTTP 服务源 | 元数据刮削 | 按固定的 REST 形态（`/v1/movies/search`、`/v1/movies/{provider}/{id}`）实现客户端，可接自建或第三方的兼容服务 |
| TMDB API | 元数据刮削 | 用户自备 API Key，用于主流影片与剧集 |
| [gfriends](https://github.com/xinxin8816/gfriends) 演员头像库 | 演员头像 | 默认经 jsDelivr 读取其公开索引与图片，只对库里没有本地头像的演员补图 |
| 纸鸢磁力（MCP） | 磁力搜索 | Streamable HTTP MCP 端点，用户自备 Token |
| 迅雷字幕搜索接口 | 在线字幕 | 按片名检索外挂字幕 |
