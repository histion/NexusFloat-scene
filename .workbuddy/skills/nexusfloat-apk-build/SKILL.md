---
name: nexusfloat-apk-build
description: 在本机（E:\workbuddy\工作空间\NexusFloat-main）把 NexusFloat 编译成签名 release APK 并做四步校验。当用户要求「打包 / 编译 APK / 出包 / 覆盖安装包 / 换个版本号出包」，或构建报 JAVA_HOME、SDK、非 ASCII 路径、Gradle 下载失败时使用。含工具链位置、必需的 -Pandroid.overridePathCheck、官方下载被墙时的镜像替代方案、以及 release 包资源名混淆后的校验手法。
agent_created: true
---

# NexusFloat 打包与校验

## 0. 先认清这台机器的特殊情况

**本机（Histion-8745h）没有预装 JDK 和 Android SDK。** 项目里的
`local.properties` 写的是 `C:\Users\iamdog\android-build\sdk`，那是**另一台机器**的路径，
在本机无效——别以为构建环境是现成的，直接跑 `./gradlew` 必然失败。

本机已搭好的工具链（2026-09-26 建，位置别乱动）：

| 组件 | 路径 |
|---|---|
| JDK 21 (Temurin) | `C:\Users\Histion-8745h\android-build\jdk21` |
| Gradle 9.5.0 | `C:\Users\Histion-8745h\android-build\gradle-9.5.0` |
| Android SDK | `C:\Users\Histion-8745h\android-build\sdk` |
| 下载缓存/安装包 | `C:\Users\Histion-8745h\android-build\dl` |

`local.properties` 已指向本机 SDK（原文件备份在同目录 `local.properties.bak`）。

## 1. 构建命令（照抄即可）

```bash
cd "E:/workbuddy/工作空间/NexusFloat-main"
export JAVA_HOME="C:/Users/Histion-8745h/android-build/jdk21"
export ANDROID_HOME="C:/Users/Histion-8745h/android-build/sdk"
./gradlew :app:assembleRelease --console=plain -Pandroid.overridePathCheck=true
```

三个必须项，缺一不可：

1. **`JAVA_HOME`** —— 本机 PATH 里没有 java，不设就是
   `ERROR: JAVA_HOME is not set`。
2. **`-Pandroid.overridePathCheck=true`** —— 项目路径带中文（`工作空间`），
   AGP 会直接 fail：*"Your project path contains non-ASCII characters"*。
   用命令行参数绕过，**不要**去改项目的 `gradle.properties`（那是入库文件）。
3. **`--console=plain`** —— 纯文本日志，便于 tail / 抓错。

首次全量构建约 **13 分钟**（含编译 Kotlin + Compose），别以为是卡死了。
完整构建用 `run_in_background`，输出用 `| tail -70` 收尾（否则刷屏）。

## 2. 遇到下载失败时的替代方案（本机代理很挑）

| 目标 | 现象 | 怎么办 |
|---|---|---|
| `services.gradle.org` | `curl: (56) CONNECT tunnel failed, response 502`；wrapper 报 SocketException | 换镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-9.5.0-bin.zip`（华为镜像 `mirrors.huaweicloud.com/gradle/` 也可） |
| Gradle wrapper 缓存 | 下载失败后会留下 `~/.gradle/wrapper/dists/gradle-9.5.0-bin/<hash>/` 里有 `.part`/`.lck` | 手动把发行包解压进去 + `touch <hash>/gradle-9.5.0-bin.zip.ok`，`./gradlew` 就会直接用（当前 hash = `bvnork1r7n8i6kp5cnkibsc9q`） |
| `sdkmanager` | 新版 CLI **废弃了 `;` 分隔**，写成 `"platforms;android-37.0"` 会被拆成 4 个包名报 not found | 用 `/`：`"platforms/android-37.0"`、`"build-tools/37.0.0"` |
| `sdkmanager` 解包 | 报 `Failed to extract package archive ... -> platforms/android-37.0/xxx`，目标目录空 | 包其实已解到 `.sdk/unzips/<hash>/`，**自己 `cp -r` 过去**即可。build-tools 同理：从 `dl.google.com/android/repository/build-tools_r37_windows.zip` 下载，压缩包根目录是 `android-37.0`，内容要摊平到 `build-tools/37.0.0/` |

手动装完 platform 后 AGP 可能报
`Observed package id 'platforms;android-37.0' in inconsistent location '...android-37.0-2'`
——只是 warning，不影响构建成功。

解压一律用 Python（本机没有 `unzip`，Git Bash 跑 `.bat` 容易踩 MSYS 转义坑）：

```bash
"C:/Users/Histion-8745h/.workbuddy/binaries/python/envs/default/Scripts/python.exe" \
  -c "import zipfile; zipfile.ZipFile('x.zip').extractall('.')"
```

> 需要重装工具链时：JDK 用
> `https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse`；
> cmdline-tools 用 `https://dl.google.com/android/repository/commandlinetools-win-<build>_latest.zip`
> （版本号从 `repository2-3.xml` 里 grep）。

## 3. 出包后的四步校验（历史惯例，一步都别省）

```bash
BT="C:/Users/Histion-8745h/android-build/sdk/build-tools/37.0.0"

# ① 版本号
"$BT/aapt2.exe" dump badging app/build/outputs/apk/release/app-release.apk | head -3

# ② 签名（必须能覆盖安装）
"C:/Users/Histion-8745h/android-build/jdk21/bin/java.exe" -jar "$BT/lib/apksigner.jar" \
  verify --print-certs -v app/build/outputs/apk/release/app-release.apk | head -12

# ③ 资源表（release 包资源名已混淆，只能这样反查）
"$BT/aapt2.exe" dump resources app/build/outputs/apk/release/app-release.apk \
  | grep -A 8 "mipmap/ic_launcher"

# ④ 逐像素比对（可选）
```

**要点：**

- V2 签名 SHA-256 必须是
  `98c70c3508a2fed96d624caa3349d5b1e5d433c13ae7ae493ab938a0b4e39a01`
  （对应 `keystore/nexusfloat-release.jks`）。不一致说明 keystore.properties 没被读到，
  装上去会签名冲突。
- **release 包的资源名会被 `optimizeReleaseResources` 混淆**：
  `mipmap/ic_launcher` → `res/o-.png` 这种两字符名。所以：
  - 别在 APK 里 grep `ic_launcher`，找不到是正常的；
  - 要看编译后的自适应图标 XML，先用资源表反查出实际文件名，再
    `aapt2 dump xmltree <apk> --file res/xx.xml`（直接 `unzip | cat` 会解码失败，
    它是编译过的二进制 XML）。
- **比对 APK 内资源一律比像素，不比 md5**：AGP 会重新编码 PNG，md5 必然不同。
  用 Pillow `ImageChops.difference(...).getbbox() is None` 判 identical。
- 同字节数 ≠ 同文件（历史上出现过 7 次同字节数），**版本判定只认 md5**。

## 4. 交付形态

- 根目录的 `NexusFloat-<versionName>.apk` 是给用户的交付物：把
  `app/build/outputs/apk/release/app-release.apk` **同名覆盖**过去。
- 覆盖前确认旧包有备份（`../<版本>备份/NexusFloat-<版本>.apk`，比 md5）。
- **用户说「版本号保持不变」时**：不动 `app/build.gradle.kts` 的 versionCode/versionName，
  也**不动 CHANGELOG.md**——只改功能 + 出包。（用户会自己说要不要写更新日志。）
- 项目不是 git 仓库，没有 `git checkout` 可回滚；要留退路就先把旧 APK / 被替换的源文件
  挪到 `.workbuddy/` 下备份，而不是删掉。

## 5. 改完 UI 怎么先看一眼（本机没有 Android Studio，跑不了真机截图）

装机前想确认排版，用「光栅化素材 + Pillow 拼卡片」出预览图，比盲改快得多：

1. SVG 素材 → PNG：**用 `@resvg/resvg-js`**（Node，npm 会下预编译 binary），
   已装在 `C:\Users\Histion-8745h\.workbuddy\binaries\node\workspace`。
   跑法：`NODE_PATH=<该目录>/node_modules node render.js`。
   模拟深色主题时在 SVG 文本里做颜色替换：`#000000`/`#333` → `#F0F3F9`（MdThemeOnSurface）、
   `#FFF` → `#15171E`（ToggleOffContainer）。
   **`cairosvg` 和 `svglib` 在这台机器上都装不通**（缺 cairo 原生库），别浪费时间。
2. 用量化过的尺寸把卡片画出来（3 倍密度），关键常量在
   `ui/theme/Theme.kt` 的 `DarkColors`：surface `#0F1115`、cardSurface `#1B1E26`、
   cardStroke `#2B2F3D`、toggleOffContainer `#15171E`、onSurface `#F0F3F9`、
   onSurfaceVariant `#9FA5B5`。
3. **紧凑格的实际可用宽度**（411dp 屏，单格 ≈113dp）：数值栏今天只有约 **97dp**，
   「SM-S9280」这类机型号几乎占满；再加任何左侧元素都会把它推向省略号。
   改 `MetricTile` 前先按这个宽度算一遍，别只看代码不看数。
