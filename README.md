# 小霸王 TV（com.xbw.tv）

在 Android TV / 电视盒子上，用**手柄**玩 [yikm.net](https://www.yikm.net) 的在线红白机/街机/SFC/GBA/MD 游戏。

三条铁律贯穿全部代码：

1. **不内置任何游戏数据** —— 列表、封面、ROM、模拟器核心全部实时来自 yikm.net 与自编译 libretro 核心；
2. **交互全部原生、引擎全原生** —— 大厅/搜索/设置/工具条是原生控件，游戏画面由 libretro 核心 C 层直出（无 WebView）；
3. **一切皆可手柄操作** —— 方向键 + 确认走天下，遥控器与键盘作为兜底。

---

## 快速开始

### 环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | AGP 8.5 要求 |
| Android SDK | platform 34 + build-tools 34.0.0 | `local.properties` 写 `sdk.dir=...` |
| Gradle | 8.9 | 仓库自带 wrapper（腾讯镜像分发地址） |

### 编译

```powershell
# 方式一：Android Studio 直接打开工程根目录
# 方式二：命令行
$env:JAVA_HOME='G:\jdk17'
& .\gradlew.bat :app:assembleDebug --console=plain
# 产物：app\build\outputs\apk\debug\app-debug.apk （约 6.3 MB）
```

### 安装到电视/盒子

```powershell
adb connect 192.168.x.x:5555        # 网络调试的盒子
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p com.xbw.tv.debug 1   # 或从 TV 桌面「小霸王 TV」图标进入
```

minSdk 21（覆盖 Android 5.0+ 的绝大多数盒子）。debug 包名带 `.debug` 后缀，与正式签名包共存。

## 功能地图

| 模块 | 入口 | 实现 |
|---|---|---|
| 游戏大厅（分类芯片 + 网格 + 分页按钮） | 启动页 | `LobbyActivity` / `LobbyViewModel` / `GameCardAdapter` |
| 实时抓取 + 缓存（Room + LruCache + OkHttp） | — | `GameRepository` / `YikmParser` / `SiteConfig` |
| 搜索（GET /search?name=…&page=1，只显示有原生核心的平台） | 大厅右上 | `SearchActivity` / `CoreRouter` / `GameRepository.filterPlayable` + Room `game_platform` |
| 拼音首字母搜索（本地索引：站点不支持拼音，见 docs §2.9） | 搜索页输字母 | `PinyinSearchIndexer` / `Pinyin` / Room `search_index` |
| 收藏（卡片长按/菜单键/游戏内工具条「收藏」按钮 + 「我的收藏」页签） | 大厅 | `FavoriteEntity` / `FavoriteDao` / `GameRepository.toggleFavorite` |
| 游戏运行（libretro 原生核心：FC=fceumm 街机=fbneo SFC=snes9x GBA=mgba MD=genesis_plus_gx） | 点卡片 | `NativeGameActivity` / `RetroCore` / `CoreRouter` / `RomProvider` / `libxbwcore.so` + 各核心 `.so` |
| 金手指（FC 专用，站点 /cheat?id= 实时抓取） | 游戏内工具条→金手指 | `CheatParser` / `CheatAdapter` / `RetroCore.setCheats`（仿真线程经 retro_cheat_reset/set 下发） |
| 原生工具条（暂停/重置/存档/读档/金手指/收藏/退出） | 游戏页按 SELECT/MENU/MODE | `NativeGameActivity.wireToolbar` |
| 输入映射（物理键→GameButton→retro 位图） | — | `KeySettings` / `MotionKeyBridge` / `NativeGameActivity` |
| 按键双层映射 + 手柄直改 | 设置→按键映射 | `KeySettings` / `KeyMappingActivity` |
| 手柄热插拔 | 设置→手柄状态 | `GamepadManager` |
| 自动升级（进游戏静默检测 + 设置→立即检查更新；GitHub Release vN 自动挑最快下载源） | 设置→自动升级 | `UpdateChecker` / `UpdatePrompt` / `UpdateSettings` / FileProvider |
| 站点结构诊断 | 设置→诊断 | `DiagnoseActivity` |

## 目录结构

```
app/src/main/java/com/xbw/tv/
  XbwApplication.kt        # 网络缓存目录 / KeySettings / Room 单例仓库
  data/
    model/    GameItem · GameCategory（FC/街机/SFC/GBA/MD/我的收藏/最近玩过 —— 只有这 5 类有原生核心）
    net/      SiteConfig（★ 选择器与 URL 唯一维护点）· YikmParser · HttpFetcher（重试+磁盘缓存+HEAD 探活）· CheatParser（/cheat?id= 解析）
    local/    Room：游戏列表缓存 / 最近玩过 / 收藏（favorites，v2） / 两层按键映射表 / 拼音索引（v3） / 平台探测缓存 game_platform（v4）
    repo/     GameRepository（缓存优先→后台强刷；同分类串行防轰炸；金手指 LRU；搜索可玩性过滤）
    update/   UpdateChecker（GitHub Release vN 检测 + 下载源探测 + FileProvider 安装）· UpdatePrompt · UpdateSettings
  input/
    GameButton · KeySettings（两层映射+菜单热键）· MotionKeyBridge（轴→方向键）· GamepadManager（热插拔）
  ui/
    lobby/ search/ settings/ game/ diagnose/ ｜ common（焦点动效/导航工具）
  core/                     # ★ 原生引擎层（见 docs/YIKM_SITE_STRUCTURE.md §2.6）
    CoreRouter.kt           # ★ 平台 ↔ 核心 唯一映射（play 页字段解析 + 搜索过滤判定）
    RetroCore.kt            # JNI 宿主封装：生命周期/AudioTrack/位图输入/金手指下发
    RomProvider.kt          # yikm id → ROM 文件（gromname 直链下载 + ZIP/7z 解包 + 缓存），失败分「无核心/下载失败」两类
app/src/main/cpp/xbw_core.c # 通用 libretro 宿主：dlopen 核心 + 软件渲染 + SRAM + 存档 + 金手指队列
fceumm/                     # libretro-fceumm 上游源码编译模块 → libfceumm.so（FC）
fbneo/                      # libretro-fbneo 编译模块 → libfbneo.so（街机；cheat 为上游空实现）
snes9x/                     # libretro-snes9x 编译模块 → libsnes9x.so（SFC，7z ROM）
mgba/                       # mGBA 上游 CMake 桥模块 → libmgba.so（GBA）
genesis_plus_gx/            # Genesis-Plus-GX 编译模块 → libgenesis_plus_gx.so（MD）
vendor/                     # libretro-common / 各核心上游源码（独立 git 仓库，勿手改）
docs/
  YIKM_SITE_STRUCTURE.md   # ★ 站点结构 + 选择器维护 SOP（改版必读）
  GAMEPAD_TESTING.md       # ★ 手柄映射说明 + 15 分钟验收测试矩阵
tools/
  SelectorCheck.java       # 离线选择器校验夹具（tools/*.html 为真实页面样本）
```

## 核心设计一页纸

- **数据层**：缓存只是加速手段。进任何分类：先渲染 Room 缓存 → 立刻发强制刷新 → 成功则替换，失败保留缓存并提示。缓存永不导致"看不到新游戏"。
- **搜索只给"玩得动的"**：站点搜索是全站检索，会返回 Java / NDS / DOS / Flash 等没有原生核心的平台。搜索结果先按 `search_index.categoryKey` 与 `game_platform` 缓存判定，未知项才现查 play 页（并发 4、单次最多 24 个）并缓存；抓不到时保守放行，绝不误伤。搜索 URL 必须带 `page=1`（缺省 page 的搜索请求站点会返回空列表）。
- **平台 ↔ 核心只有一张表**：`CoreRouter` 依据 play 页的 `gameType/gromname/gsystem` 判定 FC→fceumm、街机→fbneo、SFC→snes9x、GBA→mgba、MD→genesis_plus_gx；ROM 准备与搜索过滤共用，杜绝两处规则漂移。ROM 失败分「该平台无核心」「下载/解包失败（可重试）」两类提示，不再一律报"暂无核心"。
- **全原生引擎**：游戏运行只有一条路 —— libretro 核心（FC=FCEUmm，街机=FBNeo），C 层直出 RGB 帧 + AudioTrack 阻塞写自动限速，实测 Mali-450 老盒子 60fps 稳定（早期 WebView+JS 引擎因老盒子合成栈掉帧已整体移除）。ROM 经 `RomProvider` 从 `file.1990i.com` 直链抓（gromname 规则见 §2.6），缓存后离线秒开。手柄未映射键一律吞掉（修复 USB 手柄杂键触发系统行为的顽疾）。
- **FC 核心选型结论（2026-10 评估）**：保留 **FCEUmm**，不引入 Nestopia UE。Nestopia 精度更高（NESdev 测试套件过得多），但 FCEUmm 更快、mapper 覆盖更宽（专门为非正版/改版卡带做过扩充），RetroPie 与 libretro 官方 core info 都把 FCEUmm 标为"低资源、移动/嵌入式首选"；Nestopia 在 Pi 级设备上就有掉帧记录，Mali-450 老盒子风险更高，且体积更大（内嵌 5.9MB 数据库）、需要 C++ STL、还没有 Game Genie。Mesen 无 Android libretro 版本。
- **金手指线程铁律**：`retro_cheat_set` 会改核心内部读处理器（FCEUmm: FCEUI_AddCheat），**只能在仿真线程调用**。UI 线程只往 native 队列塞字符串（`RetroCore.setCheats` → `g_cheat_dirty`），emu 循环每帧检查并整表重放；读档后自动重新下发。街机 FBNeo 的 cheat 是上游空实现，面板对非 FC 核心直接提示「暂不支持」。
- **输入模型**：物理 keyCode →（KeySettings）→ GameButton → retro 位图；摇杆 AXIS 折算方向位。菜单热键默认 SELECT，另留 MENU / 长按 START / 手柄 MODE(HOME) 呼出工具条。
- **焦点即生命**：所有可操作元素 focusable + 焦点放大 + 阴影抬升；错误页焦点强制落在重试；工具条弹出焦点先给「暂停」。⚠️ 实测坑：RecyclerView 的 item 增删/移动动画与 TvFocusAnimator 的 `bringChildToFront` 冲突会在动画结束回收时崩溃（"Scrapped or attached views may not be recycled"）——所有游戏卡片/金手指列表一律 `itemAnimator = null`。

## 已知问题排查

| 症状 | 处理 |
|---|---|
| 大厅空白/报"站点结构可能已变更" | 设置→站点结构诊断，按 `docs/YIKM_SITE_STRUCTURE.md` §4 更新 `SiteConfig` |
| 搜索不到某款游戏 | 搜索只显示有原生核心的平台（NDS/DOS/Java/Flash 等被过滤）；确认它属于 FC/街机/SFC/GBA/MD，再重建拼音索引 |
| 进入游戏提示"XX 暂无原生核心" | 该游戏平台没有编译核心（当前仅 FC/街机/SFC/GBA/MD）；按返回键退出 |
| 进入游戏提示"ROM 下载失败/解包失败" | 网络或 CDN 临时问题，按返回重进即可；站点路径若已变更见 `SiteConfig` 与 docs §2.6 |
| 检查更新失败 | 需要能访问 GitHub（api.github.com 或 github.com 不通时会失败，App 会明确提示"检查失败"而不是谎报已是最新）；换个网络或稍后再试 |
| 升级装不上 | Android 8+ 首次需在系统设置里允许本应用"安装未知应用" |
| 游戏黑屏（FC） | 抓 logcat `XbwCore`：确认 `libfceumm.so` 加载与 ROM 下载日志 |
| 手柄按键进游戏无效 | 按 `docs/GAMEPAD_TESTING.md` §5 逐步排查；未映射的手柄键会被吞掉属预期 |
| SELECT 键冲突（街机投币） | 设置→菜单呼出键 改为 MENU/L3；或长按 START / 手柄 MODE 键呼菜单 |
| 金手指开启后无效 | 确认是 FC 游戏（街机核心不支持）；读档后需重开面板确认（读档自动重放已开列表） |
| 列表切换时卡片错乱/崩溃 | 已修复（禁用 itemAnimator）；若复现请抓 logcat 搜 "Scrapped or attached" |

## 自动构建与发布

推 `master` 即自动出包（也可 Actions 页手动触发）：

1. `.github/workflows/build.yml` 按 `vendor/*` 里锁定的 commit 精确还原 6 个上游仓库；
2. JDK 17 + NDK 27.2 + CMake 3.22.1 编 debug/release 两个 APK；
3. 发布到 GitHub Release，**tag = `v<major>.<minor>.<patch>`**：首个正式版 `v0.0.1`，
   之后每次自动 patch+1（从上一个 Release tag 推导，不需要提交回仓库）；
4. Gradle 通过 `-PappVersion=0.0.N` 把版本号同时写进 `versionName` 和派生的 `versionCode`
   （`0.0.1→1`、`0.0.14→14`、`1.2.3→1002003`），App 内 `UpdateChecker` 用同一套算法比大小。

App 侧：设置→进游戏时自动升级（默认开，进游戏静默检测，6 小时一次）、
设置→立即检查更新。下载同一份 Release 资产时会**并发 HEAD 探活 GitHub 直连 + 4 个镜像**，
按当次实测响应时间排序、最快的先下（不写死偏好，因为哪个 CDN 快完全取决于用户当前网络）；
下载失败顺位换源重试，落盘还会校验确实是 APK（防止镜像用 200 返回错误页），
完成后经 FileProvider 拉起系统安装器。

## 免责声明

本工程为技术学习与演示用途：不内置、不存储、不转发任何游戏 ROM；所有游戏内容归 yikm.net 与原作者所有。请尊重原站，控制抓取频率，勿用于商业分发。

## 致谢

- **[yikm.net（小霸王，其乐无穷）](https://www.yikm.net)** —— 本应用全部游戏列表、封面、ROM 与金手指数据均来自该站，是它让电视盒子上的怀旧游戏体验成为可能。感谢站长的长期维护，请大家控制访问频率、尊重原站。
- **libretro / FCEUmm / FBNeo** —— 本应用所使用的模拟器核心，向后端团队致以敬意。
- AGPL-3.0 开源许可证下的所有上游依赖。
