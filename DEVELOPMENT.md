# Emby Lite Android 开发文档

## 1. 项目概述

Emby Lite 是一个面向内部局域网使用的轻量 Android Emby 消费端。应用不包含内置视频解码器，负责登录 Emby Server、展示媒体、管理收藏与合集，并通过 Android Intent 调用 MX Player、VLC 等外部播放器。

当前版本：`2.3.0`（`versionCode 13`）。

主要功能：

- 连接 Emby Server 并保存登录状态
- 展示 `Movie` 与 `MusicVideo`
- 两列海报网格和视频详情页
- 外部播放器播放及随机播放
- 本地最近播放记录和升序、降序排列
- Emby 收藏管理
- 查看合集并将视频加入已有合集
- 删除服务器媒体文件
- Android Keystore 加密保存密码

## 2. 技术栈与运行要求

| 项目 | 当前配置 |
| --- | --- |
| 开发语言 | Java |
| UI | Android 原生 View，代码式布局 |
| 构建系统 | Gradle 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Java | JDK 17 |
| compileSdk | 35 |
| targetSdk | 35 |
| minSdk | 26（Android 8.0） |
| 外部依赖 | 无 |

应用只申请 `android.permission.INTERNET`。由于内部 Emby 地址使用 HTTP，Manifest 设置了 `android:usesCleartextTraffic="true"`。

## 3. 工程结构

```text
emby_android/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/embylite/
│       │   ├── MainActivity.java
│       │   ├── EmbyClient.java
│       │   ├── Movie.java
│       │   ├── MovieAdapter.java
│       │   ├── CredentialStore.java
│       │   └── RecentStore.java
│       └── res/values/
│           ├── strings.xml
│           └── styles.xml
├── gradle/wrapper/
├── build.gradle
├── settings.gradle
└── EmbyLite-debug.apk
```

各文件职责：

- `MainActivity.java`：页面构建、导航状态、用户操作和异步任务调度。
- `EmbyClient.java`：Emby REST API、图片下载和播放 URL 生成。
- `Movie.java`：影片及合集的轻量数据模型。
- `MovieAdapter.java`：两列海报网格、图片异步加载和内存缓存。
- `CredentialStore.java`：使用 Android Keystore 和 AES/GCM 加密密码。
- `RecentStore.java`：本地记录最近播放时间，最多保存 100 项。

## 4. 构建和安装

### 4.1 准备环境

安装 JDK 17 和 Android SDK，并确认 SDK 包含：

- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools

设置 `JAVA_HOME` 和 `ANDROID_HOME`，或者直接使用 Android Studio 内置 JDK 与 SDK。

### 4.2 编译 Debug APK

Linux/macOS：

```bash
./gradlew assembleDebug
```

Windows：

```bat
gradlew.bat assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

项目根目录的 `EmbyLite-debug.apk` 是人工复制的便捷分发文件，Gradle 不会自动更新它。完成构建后需要执行：

```bash
cp app/build/outputs/apk/debug/app-debug.apk EmbyLite-debug.apk
```

### 4.3 安装到模拟器或设备

```bash
adb install -r EmbyLite-debug.apk
```

也可以把 APK 直接拖入 Android Studio 模拟器窗口。

当前工程只配置了 Debug 签名。若要正式分发，需要增加独立 Release keystore 和 `signingConfig`。

## 5. 默认连接配置

内部默认配置定义在 `MainActivity.java`：

```java
private static final String DEFAULT_SERVER = "http://192.168.5.3:8096";
private static final String DEFAULT_USERNAME = "NL";
private static final String DEFAULT_PASSWORD = "NL";
```

首次安装或没有已保存凭据时，登录页会自动填充这些值，但用户仍可修改。

注意：硬编码到 APK 的地址、用户名和密码可以被反编译读取，不能视为机密。该配置只适合受控内部网络，不应直接用于公开发行版本。

## 6. 页面与交互

### 6.1 登录页

登录页包含服务器地址、用户名、密码和“记住账号和密码”选项。

启动逻辑：

1. 从 `session` SharedPreferences 读取服务器、Token 和 UserId。
2. 存在完整会话时直接进入媒体库。
3. 没有会话时显示登录页，并优先填充已保存凭据或内部默认值。
4. 登录成功后保存服务器、Token、UserId；勾选记住密码时，通过 Keystore 加密密码。

### 6.2 媒体库首页

首页使用自适应列数、2:3 比例的圆角海报卡片，并提供可记忆的深色/浅色主题。分类包括：

- 全部
- 最近播放
- 收藏
- 合集
- 合集内容

点击影片进入详情页。点击合集进入合集内容列表。

底部按钮：

- 左侧：随机播放当前列表中的一个可播放项目
- 右侧：播放当前选中项目；未手动选择时默认使用列表第一项

登录页、媒体库和详情页右上角均提供主题切换按钮。主题偏好存储在 `session`
SharedPreferences 中，应用下次启动时继续使用上次选择。

### 6.3 视频详情页

详情页显示：

- 主图或缩略图
- 视频名称
- 文件大小
- 播放
- 收藏或取消收藏
- 添加到已有合集
- 删除

删除按钮会先显示二次确认。确认后调用 Emby 删除接口，该操作可能同时删除服务器文件，无法撤销。

### 6.4 最近播放

由于播放由外部播放器完成，应用无法可靠获得完整的播放进度。当前实现把“成功唤起外部播放器”的时间记录为最近播放时间。

记录存储于 SharedPreferences：

```json
{
  "item-id-1": 1784800000000,
  "item-id-2": 1784790000000
}
```

最多保留 100 项，支持最新优先和最早优先。此记录是本地数据，不等同于 Emby Server 的观看历史。

## 7. Emby API

`EmbyClient` 会去除服务器地址末尾 `/`，如果地址不以 `/emby` 结尾则自动追加。

每个请求携带：

```http
X-Emby-Authorization: Emby Client="Emby Lite", Device="Android", DeviceId="...", Version="1.0.0"
X-Emby-Token: <access-token>
```

### 7.1 用户认证

```http
POST /emby/Users/AuthenticateByName
Content-Type: application/json

{
  "Username": "...",
  "Pw": "..."
}
```

保存返回的 `AccessToken` 和 `User.Id`。

### 7.2 查询电影和音乐视频

```http
GET /emby/Users/{UserId}/Items
    ?Recursive=true
    &IncludeItemTypes=Movie,MusicVideo
    &Fields=MediaSources,Overview
    &SortBy=SortName
    &SortOrder=Ascending
```

收藏分类额外增加：

```text
Filters=IsFavorite
```

### 7.3 查询合集

```http
GET /emby/Users/{UserId}/Items
    ?Recursive=true
    &IncludeItemTypes=BoxSet
```

查询合集内容：

```http
GET /emby/Users/{UserId}/Items
    ?ParentId={CollectionId}
    &Recursive=true
    &IncludeItemTypes=Movie,MusicVideo
```

添加视频到合集：

```http
POST /emby/Collections/{CollectionId}/Items?Ids={ItemId}
```

### 7.4 收藏

添加收藏：

```http
POST /emby/Users/{UserId}/FavoriteItems/{ItemId}
```

取消收藏：

```http
DELETE /emby/Users/{UserId}/FavoriteItems/{ItemId}
```

### 7.5 图片

图片标记兼容两种响应格式：

- `PrimaryImageTag` / `ThumbImageTag`
- `ImageTags.Primary` / `ImageTags.Thumb`

加载顺序：

1. `/emby/Items/{Id}/Images/Primary`
2. Primary 不存在时请求 `/emby/Items/{Id}/Images/Thumb`
3. 两者都不存在时保留深色占位图

图片使用 `LruCache` 做内存缓存，目前没有磁盘缓存。

### 7.6 播放

播放 URL：

```http
GET /emby/Videos/{Id}/{FriendlyFileName}
    ?static=true
    &api_key={Token}
    &PlaySessionId={RandomId}
    &MediaSourceId={MediaSourceId}
```

应用通过 `ACTION_VIEW` 调用外部播放器：

```java
Intent intent = new Intent(Intent.ACTION_VIEW);
intent.setDataAndType(Uri.parse(streamUrl), "video/*");
intent.putExtra("title", movie.name);
intent.putExtra("filename", movie.fileName());
intent.putExtra(Intent.EXTRA_TITLE, movie.name);
startActivity(intent);
```

`title` 和 `filename` 用于 MX Player 显示名称及远程字幕匹配。

### 7.7 删除

```http
DELETE /emby/Items?Ids={ItemId}
```

该接口可能删除媒体库条目和物理文件。服务器用户必须具备删除权限，否则会返回 403。

## 8. 数据存储和安全

SharedPreferences 文件名为 `session`。

| 数据 | 保存方式 |
| --- | --- |
| 服务器地址 | SharedPreferences 明文 |
| 用户名 | SharedPreferences 明文 |
| Access Token | SharedPreferences 明文 |
| UserId | SharedPreferences 明文 |
| 密码 | AES/GCM 密文 |
| 密码密钥 | Android Keystore |
| 最近播放 | SharedPreferences JSON |

密码密钥别名为 `emby_lite_password_key`。如果 Keystore 密钥丢失、失效或无法解密，应用会清除保存的用户名和密码。

安全限制：

- 默认凭据硬编码在 APK 中。
- Access Token 当前没有加密。
- HTTP 连接允许明文传输用户名、密码和 Token。
- 没有证书固定或自签名证书处理。
- 播放 URL 通过 `api_key` 查询参数携带 Token，并会暴露给外部播放器。

如果应用离开受控局域网，应优先迁移到 HTTPS、移除默认凭据，并加密 Token。

## 9. 并发与错误处理

网络和图片任务使用：

```java
Executors.newFixedThreadPool(5)
```

UI 更新通过 `runOnUiThread()` 或 View 的 `post()` 执行。HTTP 连接超时：

- 连接超时：12 秒
- 读取超时：25 秒

非 2xx 响应会转换为包含状态码的异常，并通过 Toast 显示。图片加载错误被静默忽略，界面保留占位图。

## 10. 已知限制

- 仅使用媒体条目的第一个 `MediaSource`。
- 不支持在应用内选择音轨、字幕、清晰度或转码配置。
- 外部播放器的进度、暂停、完成状态不会同步回 Emby。
- 最近播放是本地记录，不与其他设备同步。
- 没有分页；大型媒体库会一次加载全部条目。
- 图片只有内存缓存，应用重启后需要重新下载。
- 合集功能仅支持已有 `BoxSet`，暂不支持新建合集。
- 删除和添加合集受 Emby 用户权限控制。
- Token 失效时尚未实现自动回到登录页。
- UI 使用代码式 View，没有 XML 布局、ViewModel 或自动化 UI 测试。

## 11. 调试建议

### 11.1 无法连接服务器

- 确认模拟器能访问 `192.168.5.3`。
- 标准 Android Studio 模拟器访问宿主机服务时可尝试 `10.0.2.2`。
- 确认 Emby 端口为 8096，且防火墙允许访问。
- 使用浏览器打开 `http://服务器地址:8096/emby/System/Info/Public` 检查连通性。

### 11.2 不显示图片

- 检查条目是否具有 Primary 或 Thumb 图片。
- 检查 `/Items/{Id}/Images/Primary` 是否返回 200。
- 确认 Token 有权读取该条目。
- 检查 Logcat 中是否存在网络或 Bitmap 解码错误。

### 11.3 外部播放器无法播放

- 确认已安装 MX Player、VLC 或其他支持 HTTP 视频流的播放器。
- 在浏览器或播放器中直接测试生成的播放 URL。
- 检查 Emby 用户是否有播放权限。
- 检查视频容器扩展名和 `MediaSourceId`。

### 11.4 删除或合集操作返回 403

在 Emby Server 用户设置中确认当前用户具有媒体删除和合集管理权限。

## 12. 发布前检查清单

- 更新 `versionCode` 和 `versionName`
- 确认内部默认服务器与账号配置
- 运行 `./gradlew assembleDebug` 或 Release 构建
- 使用 `apksigner verify` 检查签名
- 在 Android 8.0 和较新系统至少各测试一次
- 测试登录、图片、播放、随机播放和最近播放
- 测试收藏、合集与添加到合集
- 使用无删除权限的账号验证错误提示
- 使用测试文件验证删除确认流程
- 覆盖安装并确认原有本地数据保留
