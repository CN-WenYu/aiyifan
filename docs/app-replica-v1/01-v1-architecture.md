# V1 架构与工程约定

## 目标

用 Kotlin + XML View 复刻爱壹帆的第一版视频主链路。v1 需要让用户完成“打开 App -> 浏览首页 -> 搜索/分类 -> 打开视频详情 -> 播放 -> 记录历史/收藏”的闭环。

## 推荐工程结构

```text
app/src/main/
  AndroidManifest.xml
  java/com/aiyifan/app/
    AiyifanApp.kt
    core/
      network/
      database/
      navigation/
      ui/
      player/
    feature/
      splash/
      main/
      home/
      category/
      search/
      video/
      history/
      collection/
      auth/
      mine/
  res/
    layout/
    drawable/
    mipmap/
    values/
```

## 技术选型

- 语言：Kotlin。
- UI：XML + ViewBinding。
- 页面：Activity + Fragment，主框架使用单 `MainActivity` + 多 Fragment。
- 网络：Retrofit + OkHttp + Kotlin Coroutines。
- 图片：Glide。
- 播放器：Media3 ExoPlayer。
- 本地数据：Room，保存搜索历史、播放历史、收藏、登录会话缓存。
- 下拉刷新/加载更多：可选 SmartRefreshLayout 或自研轻量组件。

## 主导航

在线主界面的底部标签依次为首页、短剧、下载、我的。下载标签直接展示可离线播放的片单。

- 已完成的剧集按影片归集，显示封面、片名、已下载集数和占用空间。点影片选集，点播放按钮离线观看。点“编辑”或长按影片进入多选，长按时选中该影片。编辑期间隐藏任务入口，底部共用任务页的全选和删除栏。确认删除后移除所选影片已完成的剧集。
- 有未完成任务时，片单上方显示一个下载任务入口，包括排队、暂停、等待网络和失败的任务。仅有任务时显示片名、集数和当前状态。首集完成后自动出现在片单中。
- 没有任务时隐藏任务入口。任务与片单都为空时提供返回首页的入口。
- 下载通知及选集弹窗的“查看下载”直接进入任务队列，可暂停、继续、重试或删除任务。已下载剧集在影片选集面板中管理。

下载 Tab 内容区与下载任务页共用页面背景色。任务页标题居中。任务行用细线分隔，首行并列显示片名和集数，空间不足时优先省略片名。第二行显示状态，“排队中”使用黄色。正在下载时，有进度则显示百分比和进度条，只有已下载大小则显示字节量。轻点任务卡片暂停、继续或重试，长按进入编辑并选中该任务。导航栏到“全部开始”和“同时缓存”按钮的可见间距与任务列表的 16dp 留白一致。“全部开始”按用户选择的并发数调度，支持 1 至 3 个任务，默认 2 个。所有可操作任务均已开始或排队时显示“全部暂停”，点击后暂停这些任务。有待恢复或重试的任务时显示“全部开始”。“编辑”期间隐藏返回按钮，可单选、全选或批量删除。底部“全选 / 取消全选”和“删除”使用纯文字操作，左右等宽，中间以竖线分隔。“完成”退出编辑。删除确认框与底部弹窗共用面板底色、面板圆角和按钮圆角。删除前用居中确认框说明将移除的任务和本机缓存，点“删除”后执行。底部安全区域内显示离线视频缓存实际字节数及所在存储卷的可用空间。

视频页沿用选集和添加结果面板。结果面板按内容自适应高度，长错误详情可滚动。仅移动网络下，未授权的剧集先加入队列并等待 Wi-Fi。“知道了”关闭面板并保持等待；“允许移动网络”在原面板中展开流量选择。从离线片单补集、单项继续或全部开始时复用下载底部面板询问网络选择。流量提示仅统计本次任务的剩余字节，优先使用下载器返回的总大小；已解析的 HLS 可按所选清晰度的码率和时长估算。无法估算的任务单独说明，首次流量授权前不联网探测大小。三个网络选项等宽等高，两个允许选项使用相同的背景圆角。“始终允许”保存为全局设置，“仅本次允许”只授权本次任务，“仅 Wi-Fi 下载”让任务等待 Wi-Fi。取消弹窗不会启动本次操作。用户主动暂停的任务不会因网络恢复而自行开始。

原 APK 中“资讯/发现、音乐、VIP”不进入 v1 主链路。

## 关键数据模型

### Category

```kotlin
data class Category(
    val id: String,
    val name: String,
    val type: Int,
    val styleType: Int,
    val url: String?
)
```

对应 APK 线索：`NavigationBarBean` 包含 `categoryId`、`name`、`type`、`styleType`、`url`。

### VideoSummary

```kotlin
data class VideoSummary(
    val mediaKey: String,
    val title: String,
    val coverUrl: String,
    val videoType: Int,
    val contentType: String?,
    val score: String?,
    val playCount: Int,
    val updateStatus: String?,
    val duration: String?,
    val year: String?,
    val area: String?
)
```

### VideoDetail

```kotlin
data class VideoDetail(
    val mediaKey: String,
    val title: String,
    val coverUrl: String,
    val typeName: String?,
    val director: String?,
    val actor: String?,
    val introduce: String?,
    val playCount: Int,
    val comments: Int,
    val shareCount: Int,
    val publishTime: String?,
    val updateMsg: String?,
    val commentEnabled: Boolean,
    val episodes: List<Episode>,
    val qualities: List<PlaybackQuality>,
    val languages: List<PlaybackLanguage>,
    val related: List<VideoSummary>
)
```

### Episode / Playback

```kotlin
data class Episode(
    val episodeKey: String,
    val episodeTitle: String,
    val uniqueId: Int,
    val mediaUrl: String?,
    val resolution: String?,
    val lang: String?,
    val duration: String?,
    val watchProgressMs: Long
)

data class PlaybackQuality(
    val resolution: String,
    val description: String,
    val mediaUrl: String,
    val isDefault: Boolean
)

data class PlaybackLanguage(
    val mediaKey: String,
    val name: String
)
```

## 网络接口

v1 自定义接口，不绑定原 APK 的 `ppt.{Host}` 动态域名。

```text
GET /app/config
GET /categories
GET /home?categoryId={id}&page={page}
GET /category/{id}/filters
GET /videos?categoryId={id}&filters=...&page={page}
GET /search/suggest?keyword={keyword}
GET /search/hot
GET /search/videos?keyword={keyword}&page={page}
GET /videos/{mediaKey}
GET /videos/{mediaKey}/comments?page={page}
POST /videos/{mediaKey}/like
POST /videos/{mediaKey}/dislike
POST /videos/{mediaKey}/favorite
DELETE /videos/{mediaKey}/favorite
```

## 本地数据库

Room 表：

- `search_history`：keyword、createdAt。
- `watch_history`：mediaKey、episodeKey、title、coverUrl、videoType、progressMs、durationMs、updatedAt。
- `favorite_video`：mediaKey、title、coverUrl、videoType、createdAt。
- `app_cache`：首页分类、配置缓存。
- `auth_session`：token、userId、nickname、avatarUrl，可空。

## 登录策略

- 游客可以浏览首页、分类、搜索、详情和播放。
- 收藏、点赞、评论需要登录；未登录时跳转 `LoginActivity`。
- v1 可以只实现账号密码登录表单和登录态保存；注册、找回密码、验证码页面先做空壳或隐藏入口。

## 付费策略

- 所有支付/VIP 页面不实现。
- 原 APK 中带 `isVip`、`goldOpenNumber`、`adGold` 的字段在 v1 只作为展示参考，不阻断播放。
- 如果接口返回付费内容，v1 显示“该内容暂未开放”，不发起购买流程。
