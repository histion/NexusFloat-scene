# NexusFloat 项目长期备忘

## 构建环境（本机 = Histion-8745h / E:\workbuddy\工作空间\NexusFloat-main）

**这台机器原本没有任何安卓工具链**（无 JDK、无 Android SDK），
`local.properties` 里那句 `sdk.dir=C:\Users\iamdog\...` 是**另一台机器**留下的，本机无效。
2026-09-26 已在本机搭好一套，位置固定：

| 组件 | 路径 |
|---|---|
| JDK | `C:\Users\Histion-8745h\android-build\jdk21`（Temurin 21.0.12.1） |
| Gradle | `C:\Users\Histion-8745h\android-build\gradle-9.5.0`（另已灌进 wrapper 缓存，见下） |
| SDK | `C:\Users\Histion-8745h\android-build\sdk`（platform-tools 37.0.1 / platforms android-37.0 / build-tools 37.0.0） |

构建命令（**必须带这两个环境变量和那个 -P 参数**）：

```bash
export JAVA_HOME="C:/Users/Histion-8745h/android-build/jdk21"
export ANDROID_HOME="C:/Users/Histion-8745h/android-build/sdk"
./gradlew :app:assembleRelease --console=plain -Pandroid.overridePathCheck=true
```

- `-Pandroid.overridePathCheck=true` 是**必需**的：项目路径含中文（`工作空间`），
  AGP 会直接 fail 拒绝构建。走命令行参数，不去改 `gradle.properties`。
- Gradle wrapper 的 dist 已缓存到
  `~/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/`，
  所以 `./gradlew` 不会再联网下载（`services.gradle.org` 在本机代理下会 502，
  **镜像用 `https://mirrors.cloud.tencent.com/gradle/`**）。
- 产物在 `app/build/outputs/apk/release/app-release.apk`，
  按惯例复制成根目录 `NexusFloat-<versionName>.apk`（同名覆盖）。

## 出包后的四步校验（沿用历史惯例）

1. `aapt2 dump badging` → 核对 versionCode / versionName / minSdk / targetSdk
2. `java -jar build-tools/37.0.0/lib/apksigner.jar verify --print-certs` →
   V2 签名 SHA-256 必须是 `98c70c3508a2fed96d624caa3349d5b1e5d433c13ae7ae493ab938a0b4e39a01`
   （不一致就不能覆盖安装）
3. `aapt2 dump resources` → **release 包资源名被混淆**（`mipmap/ic_launcher` 会变成 `res/o-.png` 之类），
   只能靠资源表反查；`aapt2 dump xmltree --file res/xx.xml` 看编译后的 XML
4. 比对 APK 内外资源要**逐像素**比，不能比 md5（`optimizeReleaseResources` 会重新编码 PNG，
   md5 必然不同、像素完全一致）

## 版本与形态约定

- `versionCode` / `versionName` 由 `app/build.gradle.kts` 手工维护
  （当前 9000006 / 9.0.0.6）；用户常要求「版本号不变、只覆盖打包」，此时**不改这两个值、
  也不动 CHANGELOG.md**（用户在需要时会明确说「更新日志」）。
- 项目**不是 git 仓库**，没有版本回滚。回滚只能靠 `../<版本>备份/` 快照目录 + 手工改源码。
- `keystore.properties` + `keystore/nexusfloat-release.jks` 不入库，口令 `nexusfloat`。

## 图标资源结构

`minSdk = 26` → **自适应图标对所有目标设备生效**，`mipmap-*dpi` 下的栅格图只是兜底。

- `mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`：
  background = `@color/ic_launcher_background`（纯色 `#EEF2F9`），
  foreground = `@mipmap/ic_launcher_foreground`（透明底角色立绘，铺到 108dp 的 80/108 居中），
  monochrome = `@drawable/ic_launcher_monochrome`（仍是柱状图矢量，主题化图标用）
- 未来若要改图标：源图最好是**透明底**立绘；先确认内容 bbox 中心，别直接整图铺满 108dp。
