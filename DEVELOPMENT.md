# Emby Lite Android 开发文档

## 1. 项目概述

Emby Lite 是一个面向内部局域网使用的轻量 Android Emby 消费端。应用不包含内置视频解码器，负责登录 Emby Server、展示媒体、管理收藏与合集，并通过 Android Intent 调用 MX Player、VLC 等外部播放器。

当前版本：`2.6.4`（`versionCode 20`）。

主要功能：

- 连接 Emby Server 并保存登录状态
- 展示 `Movie` 与 `MusicVideo`
- 两列海报网格和视频详情页
- 媒体分类左右滑动切换
- 顶部一级分类标签长按拖动自定义排序
- 外部播放器播放及随机播放
- 本地最近播放记录和升序、降序排列
- 按加入媒体库时间升序、降序排列
- Emby 收藏管理
- 查看合集并将视频加入已有合集
- 修改影片名称（重命名服务器条目）
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

签名相关约定见 4.4 节。自 2.6.1 起所有 Debug 构建使用固定密钥签名，可直接覆盖安装到任意固定签名旧版本。

### 4.4 固定签名密钥

Android 要求升级包与已安装版本的签名完全一致。Debug 构建默认使用构建机器自动生成的 `~/.android/debug.keystore` 签名，换机器或重建该文件都会导致签名变化，出现"包名相同但签名不同、无法覆盖安装"的问题。因此自 2.6.1 起，所有 Debug 构建改用仓库根目录的固定密钥签名。

两个本地文件（均已列入 `.gitignore`，不入库、不提交）：

| 文件 | 内容 |
| --- | --- |
| `embylite.keystore` | PKCS12 密钥库，别名 `embylite`，RSA 2048，有效期约 100 年 |
| `keystore.properties` | `storeFile` / `storePassword` / `keyAlias` / `keyPassword` |

构建逻辑（`app/build.gradle`）：

- 存在 `keystore.properties` 时，Debug 构建使用该固定签名。
- 文件缺失时回退到机器默认 debug 签名。回退产物无法覆盖安装到任何固定签名版本，只适用于全新环境。**分发用 APK 必须在同时具备这两个文件的机器上构建。**

维护约定：

- 两个文件必须一起备份。密钥一旦丢失，Android 不允许用新密钥覆盖升级，所有设备只能卸载重装：本地最近播放、主题和排序偏好会清空，服务器端观看进度、已看标记和收藏不受影响，重新登录即可恢复。
- 从旧的机器 debug 签名版本升级到固定签名版本属于一次性换签：需先卸载旧版再安装 2.6.1 及以上版本；此后所有新版本均可直接覆盖安装。
- 如确需更换密钥（例如密钥泄露），重新生成并更新 `keystore.properties`，但同样要求全部设备卸载重装：

```bash
keytool -genkeypair -keystore embylite.keystore -alias embylite \
    -keyalg RSA -keysize 2048 -validity 36500 -storetype PKCS12
```

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
- 收藏
- 最近播放
- 最近入库
- 合集
- 合集内容

在海报网格上左右滑动，可按顶部标签的当前顺序切换一级分类。合集内容属于下钻页面，
不参与左右滑动切换。

顶部五个一级分类标签支持长按后拖动调整顺序：拖动经过其他标签时实时交换位置，
“最新优先”“最早优先”等排序标签始终跟随自己的分类标签；标签栏内容超出屏幕宽度时，
拖动到左右边缘会自动滚动。松手后顺序保存到 `session` SharedPreferences 的
`tagOrder` 键，左右滑动切换的顺序也随之变化，下次启动时保持上次排列。

“最近入库”使用 Emby 条目的 `DateCreated` 作为加入媒体库时间，默认最新加入优先，
也可通过排序按钮切换为最早加入优先。

点击影片进入详情页。点击合集进入合集内容列表。

底部操作栏是一条铺满宽度的“随机播放”按钮：

- 随机播放：始终从“全部”媒体库中随机挑选一部可播放影片，与当前所在分类无关；
  处于“全部”分类时直接使用已加载列表，其他分类（含合集内容）下会先向服务器
  请求完整媒体库再随机，加载期间按钮防重复点击，失败时 Toast 提示

首页自 2.6.3 起不再提供“播放所选”按钮，播放影片通过点击海报进入详情页操作。

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
- 重命名
- 删除

重命名按钮弹出对话框并预填当前名称。确认后应用先获取完整条目，替换 `Name`
后提交回服务器；成功后同步更新详情页标题和本地列表数据。名称为空时不允许提交；
与原名称相同时不发起请求。重命名需要服务器端编辑权限，失败时通过 Toast 提示。

删除按钮会先显示二次确认。确认后调用 Emby 删除接口，该操作可能同时删除服务器文件，无法撤销。
删除按钮单独一行展示，与其他管理操作分离。

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

### 7.3 按加入媒体库时间查询

按加入媒体库时间查询使用与普通影片相同的接口，并调整排序参数：

```http
GET /emby/Users/{UserId}/Items
    ?Recursive=true
    &IncludeItemTypes=Movie,MusicVideo
    &Fields=MediaSources,Overview
    &SortBy=DateCreated
    &SortOrder=Descending
```

切换为最早加入优先时，`SortOrder` 使用 `Ascending`。

### 7.4 查询合集

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

### 7.5 收藏

添加收藏：

```http
POST /emby/Users/{UserId}/FavoriteItems/{ItemId}
```

取消收藏：

```http
DELETE /emby/Users/{UserId}/FavoriteItems/{ItemId}
```

### 7.6 图片

图片标记兼容两种响应格式：

- `PrimaryImageTag` / `ThumbImageTag`
- `ImageTags.Primary` / `ImageTags.Thumb`

加载顺序：

1. `/emby/Items/{Id}/Images/Primary`
2. Primary 不存在时请求 `/emby/Items/{Id}/Images/Thumb`
3. 两者都不存在时保留深色占位图

图片使用 `LruCache` 做内存缓存，目前没有磁盘缓存。

### 7.7 播放

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

### 7.8 删除

```http
DELETE /emby/Items?Ids={ItemId}
```

该接口可能删除媒体库条目和物理文件。服务器用户必须具备删除权限，否则会返回 403。

### 7.9 重命名

重命名先读取完整条目，避免覆盖其他元数据：

```http
GET /emby/Users/{UserId}/Items/{ItemId}
```

应用在返回的完整 JSON 中把 `Name` 替换为新名称后整体提交：

```http
POST /emby/Items/{ItemId}
Content-Type: application/json

{ ...原条目字段, "Name": "新名称" }
```

服务器用户必须具备条目编辑权限，否则会返回 403。

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

### 11.4 删除、重命名或合集操作返回 403

在 Emby Server 用户设置中确认当前用户具有媒体删除、条目编辑（重命名）和合集管理权限。

## 12. 发布前检查清单

- 更新 `versionCode` 和 `versionName`
- 确认 `keystore.properties` 与 `embylite.keystore` 存在（缺失时产物为回退 debug 签名，无法覆盖安装）
- 确认内部默认服务器与账号配置
- 运行 `./gradlew assembleDebug` 或 Release 构建
- 使用 `apksigner verify` 检查签名
- 在 Android 8.0 和较新系统至少各测试一次
- 测试登录、图片、播放、随机播放和最近播放
- 测试收藏、合集与添加到合集
- 使用无删除权限的账号验证错误提示
- 使用测试文件验证删除确认流程
- 覆盖安装并确认原有本地数据保留
