# 小霸王 TV（com.xbw.tv）

在 Android TV / 电视盒子上，用**手柄**玩 [yikm.net](https://www.yikm.net) 的在线红白机/街机/GBA 游戏。

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
| 搜索（GET /search?name=，单页无分页） | 大厅右上 | `SearchActivity` |
| 收藏（卡片长按/菜单键 + 「我的收藏」页签） | 大厅 | `FavoriteEntity` / `FavoriteDao` / `GameRepository.toggleFavorite` |
| 游戏运行（libretro 原生核心：FC=fceumm 街机=fbneo） | 点卡片 | `NativeGameActivity` / `RetroCore` / `RomProvider` / `libxbwcore.so` + `libfceumm.so` + `libfbneo.so` |
| 金手指（FC 专用，站点 /cheat?id= 实时抓取） | 游戏内工具条→金手指 | `CheatParser` / `CheatAdapter` / `RetroCore.setCheats`（仿真线程经 retro_cheat_reset/set 下发） |
| 原生工具条（暂停/重置/存档/读档/金手指/退出） | 游戏页按 SELECT/MENU/MODE | `NativeGameActivity.wireToolbar` |
| 输入映射（物理键→GameButton→retro 位图） | — | `KeySettings` / `MotionKeyBridge` / `NativeGameActivity` |
| 按键双层映射 + 手柄直改 | 设置→按键映射 | `KeySettings` / `KeyMappingActivity` |
| 手柄热插拔 | 设置→手柄状态 | `GamepadManager` |
| 站点结构诊断 | 设置→诊断 | `DiagnoseActivity` |

## 目录结构

```
app/src/main/java/com/xbw/tv/
  XbwApplication.kt        # 网络缓存目录 / KeySettings / Room 单例仓库
  data/
    model/    GameItem · GameCategory（FC/街机/GBA/MD/SFC/DOS/NDS/Java/Flash/我的收藏/最近玩过）
    net/      SiteConfig（★ 选择器唯一维护点）· YikmParser · HttpFetcher（重试+磁盘缓存）· CheatParser（/cheat?id= 解析）
    local/    Room：游戏列表缓存 / 最近玩过 / 收藏（favorites，v2 迁移保留） / 两层按键映射表
    repo/     GameRepository（缓存优先→后台强刷；同分类串行防轰炸；金手指 LRU 缓存）
  input/
    GameButton · KeySettings（两层映射+菜单热键）· MotionKeyBridge（轴→方向键）· GamepadManager（热插拔）
  ui/
    lobby/ search/ settings/ game/ diagnose/ ｜ common（焦点动效/导航工具）
  core/                     # ★ 原生引擎层（见 docs/YIKM_SITE_STRUCTURE.md §2.6）
    RetroCore.kt            # JNI 宿主封装：生命周期/AudioTrack/位图输入/金手指下发
    RomProvider.kt          # yikm id → ROM 文件（gromname 直链下载 + ZIP 解包 + 缓存）
app/src/main/cpp/xbw_core.c # 通用 libretro 宿主：dlopen 核心 + 软件渲染 + SRAM + 存档 + 金手指队列
fceumm/                     # libretro-fceumm 上游源码编译模块 → libfceumm.so（FC）
fbneo/                      # libretro-fbneo 编译模块 → libfbneo.so（街机；cheat 为上游空实现）
vendor/                     # libretro-common / libretro-fceumm（上游源码，勿手改）
docs/
  YIKM_SITE_STRUCTURE.md   # ★ 站点结构 + 选择器维护 SOP（改版必读）
  GAMEPAD_TESTING.md       # ★ 手柄映射说明 + 15 分钟验收测试矩阵
tools/
  SelectorCheck.java       # 离线选择器校验夹具（tools/*.html 为真实页面样本）
```

## 核心设计一页纸

- **数据层**：缓存只是加速手段。进任何分类：先渲染 Room 缓存 → 立刻发强制刷新 → 成功则替换，失败保留缓存并提示。缓存永不导致"看不到新游戏"。
- **全原生引擎**：游戏运行只有一条路 —— libretro 核心（FC=FCEUmm，街机=FBNeo），C 层直出 RGB 帧 + AudioTrack 阻塞写自动限速，实测 Mali-450 老盒子 60fps 稳定（早期 WebView+JS 引擎因老盒子合成栈掉帧已整体移除）。ROM 经 `RomProvider` 从 `file.1990i.com` 直链抓（gromname 规则见 §2.6），缓存后离线秒开。手柄未映射键一律吞掉（修复 USB 手柄杂键触发系统行为的顽疾）。
- **金手指线程铁律**：`retro_cheat_set` 会改核心内部读处理器（FCEUmm: FCEUI_AddCheat），**只能在仿真线程调用**。UI 线程只往 native 队列塞字符串（`RetroCore.setCheats` → `g_cheat_dirty`），emu 循环每帧检查并整表重放；读档后自动重新下发。街机 FBNeo 的 cheat 是上游空实现，面板对非 FC 核心直接提示「暂不支持」。
- **输入模型**：物理 keyCode →（KeySettings）→ GameButton → retro 位图；摇杆 AXIS 折算方向位。菜单热键默认 SELECT，另留 MENU / 长按 START / 手柄 MODE(HOME) 呼出工具条。
- **焦点即生命**：所有可操作元素 focusable + 焦点放大 + 阴影抬升；错误页焦点强制落在重试；工具条弹出焦点先给「暂停」。⚠️ 实测坑：RecyclerView 的 item 增删/移动动画与 TvFocusAnimator 的 `bringChildToFront` 冲突会在动画结束回收时崩溃（"Scrapped or attached views may not be recycled"）——所有游戏卡片/金手指列表一律 `itemAnimator = null`。

## 已知问题排查

| 症状 | 处理 |
|---|---|
| 大厅空白/报"站点结构可能已变更" | 设置→站点结构诊断，按 `docs/YIKM_SITE_STRUCTURE.md` §4 更新 `SiteConfig` |
| 进入游戏提示"无法准备 ROM（该平台暂无原生核心）" | H5/GBA/MD/SFC 等平台暂未编译核心（当前仅 FC/街机）；按返回键退出 |
| 游戏黑屏（FC） | 抓 logcat `XbwCore`：确认 `libfceumm.so` 加载与 ROM 下载日志 |
| 手柄按键进游戏无效 | 按 `docs/GAMEPAD_TESTING.md` §5 逐步排查；未映射的手柄键会被吞掉属预期 |
| SELECT 键冲突（街机投币） | 设置→菜单呼出键 改为 MENU/L3；或长按 START / 手柄 MODE 键呼菜单 |
| 金手指开启后无效 | 确认是 FC 游戏（街机核心不支持）；读档后需重开面板确认（读档自动重放已开列表） |
| 列表切换时卡片错乱/崩溃 | 已修复（禁用 itemAnimator）；若复现请抓 logcat 搜 "Scrapped or attached" |

## 免责声明

本工程为技术学习与演示用途：不内置、不存储、不转发任何游戏 ROM；所有游戏内容归 yikm.net 与原作者所有。请尊重原站，控制抓取频率，勿用于商业分发。
