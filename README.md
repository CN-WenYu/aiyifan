# 爱壹帆

爱壹帆是一个视频客户端项目，支持在线浏览与播放，也可以管理离线下载和设备上的本地视频。仓库以原生 Android 应用为主，同时包含独立的 HarmonyOS NEXT 工程。

## Android 功能

- **发现视频** — 浏览首页推荐、分类、热门和短剧，按关键词搜索视频。
- **追番** — 在首页「追番」搜索 Bangumi 动画条目，使用 Animeko 订阅中的稀饭动漫 Next 片源在应用内播放正片。收藏和观看进度保存在本机。
- **观看视频** — 查看影片详情、选集和全屏播放，支持手势操作、画中画、悬浮播放、定时关闭和自动跳过片头片尾。
- **接着看** — 首页提供继续观看入口，观看历史、播放进度和收藏保存在本机。
- **离线观看** — 下载剧集后离线播放，也可浏览并播放设备上的本地视频。
- **主题切换** — 可选择浅色、深色或跟随系统。

## 快速开始

准备 Android Studio、JDK 17 和 Android SDK Platform 36。应用最低支持 Android 7.0（API 24），可在 ARM 真机或 ARM 系统镜像的模拟器上运行。

1. 在 Android Studio 中打开仓库根目录，选择 JDK 17 作为 Gradle JDK，并等待 Gradle 同步。
2. 连接真机或启动模拟器，选择 `app` 运行配置后点击 **Run**。
3. 如需单独生成调试 APK，在仓库根目录运行下列命令。

   ```powershell
   .\gradlew.bat assembleDebug
   ```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。首次同步需要访问项目配置的 Maven 仓库，包括 JitPack。在线目录和播放依赖网络及上游服务。

## 构建配置与检查

Android 使用 Kotlin、XML 布局和 View Binding。播放和离线下载基于 AndroidX Media3，图片加载使用 Glide。

追番入口默认启用，可用 Gradle 参数 `-PenableAnime=false` 构建关闭追番的版本。Android 7 的 Bangumi API 和封面 HTTPS 请求使用 [ISRG Root X1 公共根证书](https://letsencrypt.org/certificates/) 补足系统信任链。

构建优先读取 `app-config.gradle`，文件缺失时使用 [示例配置](app-config.gradle.example)。调试构建使用默认调试签名。如需自定义包名、版本或构建发布包，可在本机编辑 `app-config.gradle`。发布构建需要填写 `STORE_PATH`、`STORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD`，请将签名文件和凭据留在本机。

| 检查 | Windows PowerShell 命令 |
| --- | --- |
| 本地单元测试 | `.\gradlew.bat test` |
| Android Lint | `.\gradlew.bat lint` |
| 设备或模拟器测试 | `.\gradlew.bat connectedDebugAndroidTest` |

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/com/aiyifan/app/feature/` | 首页、搜索、播放器、离线下载等功能 |
| `app/src/main/java/com/aiyifan/app/core/` | 目录数据、数据模型、下载和共享 UI |
| `app/src/main/res/` | Android 布局、文字、图标与主题 |
| `app/src/test/`、`app/src/androidTest/` | 单元测试和设备测试 |
| [`HarmonyOS/`](HarmonyOS/) | 独立的 HarmonyOS NEXT 工程 |
| [`docs/`](docs/) | 功能设计、路线图与验证记录 |

## HarmonyOS NEXT

HarmonyOS 客户端使用 ArkTS 和 ArkUI，已实现目录浏览和搜索、选集播放、本地收藏及观看记录。在 DevEco Studio 中打开 `HarmonyOS/`，同步后运行 `entry` 模块。Windows 也可以通过仓库内的 [构建脚本](HarmonyOS/build.ps1) 生成 HAP。

## 许可证

项目许可条款见 [LICENSE](LICENSE)。Material Symbols 图标的授权信息见 [第三方声明](third_party/material-symbols-NOTICE.md)。
