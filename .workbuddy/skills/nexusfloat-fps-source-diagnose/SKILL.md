---
name: nexusfloat-fps-source-diagnose
description: 诊断并修复 NexusFloat 的「面板 measured_fps」等 FPS 来源在某机型上取不到数。当用户反馈帧率来源无效/恒 0/只显示 x，或要改动 Constants.Fps、SysfsReader、PerformanceCollector 的取数链时使用。含五道闸门定位模型、现场采集命令、内核三代报文格式、以及一次失败尝试（v8.8.9.0）留下的教训。
agent_created: true
---

# NexusFloat FPS 来源诊断

## ⚠️ 先读这一段：v8.8.9.0 的修复尝试已被回滚

2026-09-23 曾做过一版修复（补齐候选表 + 修静默丢弃 + 放宽活跃判据 + 失败细分 p1/p2/p3
+ 新增「面板节点自检」按钮），打包成 `NexusFloat-8.8.9.0.apk` 后被用户以「有问题」为由
**要求全部回滚**，回滚时**没有拿到具体症状**。

所以当前代码状态 = **8.8.8.9**，下面「已知缺陷」列的问题**全部还在**。

再次动手前必须先问清楚：上一次到底坏在哪？可能是：
- 放宽 `isActive()` 让某个 crtc 的乱报值被当成有效读数（最可疑：这是唯一会**改变已能工作机型
  读数**的改动，其余几项只扩大覆盖面）
- 新增候选节点让扫描代价上升，采集线程被拖慢
- `PROBE_FPS_DEBUG` 加宽一字符导致监视条布局变化
- 自检按钮起了 su 卡住界面

**教训：改动取数链时，把「只扩大覆盖面、不改变已有判定」和「改变既有判定」两类改动
分开交付**，否则一旦出问题无法定位是哪一类导致的。

## 何时用

- 用户说「FPS 来源 XX 无效 / 取不到 / 一直 0 / 一直 -- / 只显示 x」
- 要改 `Constants.Fps`（候选表、阈值）、`SysfsReader`（取数链、解析）、
  `PerformanceCollector.collectFpsChain`

## 第 0 步：先看诊断字母，分清「没出数」还是「被顶掉」

面板来源排全链第 1 位，成功即 `return`。让用户打开「FPS 诊断」开关
（`NexusBridge.isFpsDebugEnabled`），看帧率后面的来源字母：

| 字母 | 含义 | 下一步 |
|---|---|---|
| `p` | 面板命中（正常） | 若数值不对，查 `readFps()` 的 vsync 兜底语义 |
| `t` `b` `r` `f` `g` | 面板没出数，被后面的来源顶替 | 走闸门排查 |
| `x` | 全链失败 | 走闸门排查 |

**已知短板**：当前代码里 `x` **没有任何细分**。面板失败时不留 `zeroTag`，
所以只勾面板来源时链尾必然是光秃秃一个 `x`——分不出是节点不存在、没权限还是判成静止。
（v8.8.9.0 加过 `xp1/xp2/xp3` 细分，已随回滚撤销。）

## 第 1 步：五道闸门模型

要出数必须**同时**过大五道闸门，任一条挂了就是「无效」：

| 闸门 | 代码位置 | 挂了的表现 |
|---|---|---|
| G1 通配展开 | `Channel.expandWildcard()`（direct）/ `RootShell.expandGlobs()`（root） | direct 会**静默丢候选**（见下方缺陷） |
| G2 节点存在 | `Constants.Fps.SYSFS_PATHS` 候选表 | `find` 找不到 → **模块侧无解** |
| G3 有权限读 | `readFirstLine()`：direct 用 `FileReader`，root 走 su | 值为 null，direct 失败可由 root 兜住 |
| G4 内容可解析 | `parseFpsWithFrames()` | 返回 `FpsResult.INACTIVE` |
| G5 判活跃 | `FpsResult.isActive()`：`frames >= FPS_ACTIVE_MIN_FRAMES(2)` | 解析出 fps 但判 0 |

## 已知缺陷（**当前代码里仍然存在**）

1. **`expandWildcard()` 静默丢候选**：`parent.list()` 返回 null 时 `return new String[0]`，
   整条候选**无声消失**，没有日志；而 root 通道失败会退回原 pattern（行为不一致）。
   `/sys/class/drm/*/device/*/measured_fps` 要 list 两层，任何一层被 SELinux 挡住就整条丢掉。
2. **debugfs 候选路径是错的**：`Constants.Fps.SYSFS_PATHS` 里写的是
   `/sys/kernel/debug/dri/0/measured_fps` 与 `dri/*/measured_fps`，但第一代实现的真实路径是
   `<debugfs 根>/crtc-<N>/fps`——少了 `crtc-*` 一层、文件名也是 `fps` 不是 `measured_fps`。
   **有 root 也读不到。**
3. **`readFps()` 用 0 表示「读不到」，但面板节点静止时本来就报 0**。于是
   `readMeasuredFps()` 会清掉 `fpsHit`、连 3 拍没命中就进退避
   （`SYSFS_MISS_GRACE=3`，之后每 5 拍才重扫一次），动静切换最多要等 5 拍；
   而且会继续用 `vsync_event` 差分兜底，「面板来源」报出来的其实是屏幕刷新率。
4. **`FPS_ACTIVE_MIN_FRAMES = 2` 对「内核 1 秒窗口只报 1 帧」过严**。
5. 「探测面板节点」按钮在 v1.8.0 被删，`PanelProbe` 类不存在，只剩
   `Constants.Fps.PROBE_MAX_RESULTS / PROBE_TIMEOUT_MS` 两个**死常量**。

## 第 2 步：现场采集（一次定根因）

让用户在**出问题的机器**上跑（需要 root）：

```bash
su -c '
echo "== 1. 节点到底在哪 =="
find /sys -name "measured_fps" -o -name "vsync_event" 2>/dev/null
echo "== 2. crtc 拓扑 =="
ls -d /sys/class/drm/*
echo "== 3. 一层路径逐个读（连读两次看会不会变） =="
for f in /sys/class/drm/*/measured_fps; do echo "$f => $(cat "$f" 2>/dev/null)"; done
sleep 1
for f in /sys/class/drm/*/measured_fps; do echo "$f => $(cat "$f" 2>/dev/null)"; done
echo "== 4. 深路径（8Gen3 那种「外部 crtc」） =="
for f in /sys/class/drm/*/device/*/measured_fps; do echo "$f => $(cat "$f" 2>/dev/null)"; done
echo "== 5. periodicity 是否被 HAL 配过 =="
for f in /sys/class/drm/*/fps_periodicity_ms; do echo "$f => $(cat "$f" 2>/dev/null)"; done
echo "== 6. debugfs 侧（第一代只挂这里） =="
ls /sys/kernel/debug/dri/*/crtc-*/ 2>/dev/null
echo "== 7. SELinux 有没有在拦 =="
getenforce; dmesg | grep -i avc | tail -40
'
```

**读数对照**

| 看到什么 | 结论 | 能不能修 |
|---|---|---|
| `find` 什么都没有 | 内核/ROM 没暴露这节点 | ❌ 模块侧无解 |
| 有，`cat` 出 `59.4` 这类纯数字 | 第二代格式，解析没问题 | ✅ 查权限/阈值 |
| 有，`cat` 恒 `0.0` | 节点空转，看第 5 项 periodicity | ⚠️ 多半是 HAL 没启用 |
| 有，`cat` 出巨大值（如 `100000.0`） | 走了 periodicity=0 的瞬时分支 | ✅ 超 `MAX_VALID(1000f)` 被丢，可修 |
| 只有 `/sys/kernel/debug/dri/*/crtc-*/fps` | 第一代实现 | ✅ 补候选表可修 |
| `dmesg` 有 `avc: denied` 指向该节点 | SELinux 拦截 | ✅ 靠 root 兜底 |

## 第 3 步：内核侧的三代实现（**已核实的硬事实，与回滚无关**）

`measured_fps` 不是 AOSP 接口，是高通在 `drivers/gpu/drm/msm/sde/sde_crtc.c` 里自己挂的，
**前后三代路径和报文都不同**：

| 代 | 位置 | 输出 | 依赖 |
|---|---|---|---|
| 一 | **只有 debugfs**：`debugfs_create_file("fps", 0400, sde_crtc->debugfs_root, ...)` → `<debugfs 根>/crtc-<N>/fps` | `fps: %d.%d` | 无 |
| 二 | sysfs：`DEVICE_ATTR(measured_fps, 0444, ...)` 挂在 `sde_crtc->sysfs_dev` | `scnprintf("%d.%d\n")` —— **纯数字，没有 duration / frame_count** | `fps_periodicity_ms`（0644）**需显示 HAL 主动写** |
| 三 | 同第二代 | `fps: 22.7 duration:1000000 frame_count:23` | 无 |

- 第二代的 `fps_periodicity_ms` 没被写过（值为 0）时会走「距上一帧的 µs 差」分支，
  打印巨大或抖动的值 → 被 `parseFpsToken` 的 `fps < MAX_VALID(1000f)` 判 0。
- `vsync_event` 与 `measured_fps` 在第二代是**同一个属性组**（`sde_crtc_dev_attrs`）注册的，
  所以两者存在性高度相关；部分内核额外给 SF 提供 `vsync_event_sf`。
- `parseFpsWithFrames()` 目前三种格式都能吃（纯数字按 fps 推 frames），**格式不是首要嫌疑**。

**术语坑**：注释里的「骁龙 8 Elite」是 SM8750；用户报的「8 Elite Gen 5」是 SM8850，
是新平台新驱动分支，不能用那句「8 Gen 3 / 8 Elite 上正常」去推断。

## 第 4 步：修复分档（**先定档再动手，并注意分类交付**）

1. **补候选表**（只扩大覆盖面，风险最低）：修 debugfs 路径为 `dri/*/crtc-*/fps`，
   补 `/sys/class/graphics/*/fps`、`dri/*/crtc-*/measured_fps`、`vsync_event_sf`
2. **修 `expandWildcard` 的静默丢弃**（只扩覆盖面）：退回原 pattern，与 root 通道对齐。
   ⚠️ 必须用 `parent.isDirectory()` 区分「目录不存在」和「存在但列不出来」，
   否则会把「节点不存在」全报成「目录被拒」
3. **放宽 G5 活跃判据**（⚠️ **会改变既有判定，风险最高**）：
   `isActive()` 加一条 `fps ∈ [3, 240]` 的兜底。下界挡锁屏 0.7fps，上界挡 periodicity 乱报。
   **单独交付这一步**，它是上一次回滚最可疑的元凶
4. **失败细分**（纯观测，无风险）：`x` 后面跟 `p1`（没展开出路径）/`p2`（读不到）/
   `p3`（读到但不可用）
5. **节点真不存在时不要假装能修**：改 UI 说明 + 引导用 SF TimeStats

## 硬约束（上一次踩过的坑，务必遵守）

- **Javadoc 里绝对不能出现 `*/`**：写含通配符的路径（如 `dri/*/measured_fps`）会直接
  终止块注释，javac 报一片「非法字符」「需要';'」。注释里一律用文字描述路径。
  自查命令：`rg '^\s*\*.*\*/.+'`
- **诊断标记长度受 `Constants.Ui.PROBE_FPS_DEBUG` 的字符数限制**：
  `MonitorView.clip()` 按探针长度截断，标记加长必须同步加宽探针（`" 99.9xx"` 只够
  1 位数字后缀 + 1 位字母；两位标记要改成 `" 99.9xxx"`），否则最后一位被吃掉。
- **面板失败不占 `zeroTag`**（0 分不出「静止」和「读不到」），断点只能靠
  `PerformanceCollector` 单独带出来。
- **深路径通配要设上限**，`/sys/devices/platform/soc/*` 下面条目很多，
  不限制会让一轮采集被展开拖到几十毫秒。

## 收尾

- 改完必须重新打包（走 `nexusfloat-apk-build` skill），文件名 = 软件名 + 版本号
- 判断有没有重新打包**只看 md5**，字节数会撞（已出现四次都是 12043081 字节）
