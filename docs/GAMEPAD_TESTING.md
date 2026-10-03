# 手柄映射与测试指南

> 面向两类读者：**玩家**（手柄不灵时怎么调）与**维护者**（新增手柄类型时改哪里）。

---

## 一、输入链路总览

```
物理手柄            Android 系统              本 App                          网页游戏
USB/BT 手柄  ──►  InputDevice 事件   ──►  InputDispatcher 状态机   ──┬──► GameBridge ──► 合成 KeyboardEvent（默认）
(标准/山寨/         KEYCODE_BUTTON_*        物理键→GameButton            └──► WebView 原生派发（直通模式）
 手柄/遥控器)       KEYCODE_DPAD_*          （映射表 KeySettings）
```

- **两层映射**：物理 keycode →（第 1 层）逻辑键 `GameButton` →（第 2 层）网页键盘码 `WebKey(code, key, keyCode)`。
  - 第 1 层解决「不同手柄 A/B 反接、肩键布局各异」；
  - 第 2 层解决「同一逻辑键在不同站点模拟器的默认键位不同」。
- 两层都可在 **设置 → 按键映射** 里用手柄直接改，即时生效。

## 二、默认映射表

### 2.1 逻辑键 → 网页键盘码（第 2 层默认值，`GameButton.kt`）

| 手柄逻辑键 | WebView 注入的网页按键 | 典型 NES 语义 |
|---|---|---|
| 十字键 ↑↓←→ | ArrowUp/Down/Left/Right | 方向 |
| A | `KeyX`（x） | B 跳跃 |
| B | `KeyZ`（z） | A 射击 |
| X | `KeyA`（a） | （街机）射击2 |
| Y | `KeyS`（s） | （街机）跳跃2 |
| L / R | `Q` / `W` | 街机 LB/RB |
| L3 / R3 | `E` / `T` | 少见功能 |
| SELECT | `ShiftLeft` | 投币/Select |
| START | `Enter` | 开始/暂停 |

> 站点 pclib 实测比较的是 `e.code`（`'KeyZ'` 等），因此注入必须同时给 `code`+`keyCode`，见 YIKM_SITE_STRUCTURE.md §3。

### 2.2 物理键 → 逻辑键（第 1 层默认值，`KeySettings.DEFAULT_PHYSICAL`）

| 物理 Android keycode | 逻辑键 | 常见设备叫法 |
|---|---|---|
| BUTTON_A | A | 右菱 A/cross |
| BUTTON_B | B | 菱下 B/circle |
| BUTTON_X / BUTTON_Y | X / Y | |
| BUTTON_L1/R1 | L / R | 上肩键 |
| BUTTON_L2/R2 | L2 / R2（未注入默认直通） | 下扳机 |
| BUTTON_THUMBL/R3 | L3 / R3 | 摇杆按压 |
| BUTTON_START | START | |
| BUTTON_SELECT | SELECT（注意：同时是菜单热键） | |
| DPAD_UP/DOWN/LEFT/RIGHT | 方向键 | |
| 键盘 W/A/S/D + Enter/Tab/Space | 方向/确认 | 蓝牙键盘兜底 |
| 小键盘 1..9 | 方向/ABXY | 山寨蓝牙手柄常见上报 |

### 2.3 特殊键约定

| 键 | 行为 |
|---|---|
| **SELECT**（默认菜单键） | 游戏页按 = 呼出/收起**原生工具条**（开始/暂停/重置/存档/手柄/全屏/退出） |
| **MENU**（遥控器 ≡ 键） | 同上，遥控器用户专用 |
| **START 长按**（≈0.8s） | 呼出工具条（逃生口）；**短按永远注入游戏**（Enter=NES 开始）。原方案让短按 START 弹菜单，与 NES 游戏的 Start 功能冲突，已修正 |
| **BACK** | 依次：关站点弹窗 → 收工具条 → 弹退出确认 |

菜单键可在 **设置 → 菜单呼出键** 改成任意物理键（点行→按键即捕获，BACK 取消）。若你的手柄 SELECT 与游戏功能冲突（如街机投币），建议改成 MENU 或 L3。

## 三、两种注入模式（设置 → 按键注入方式）

| | 合成模式（默认） | 直通模式 |
|---|---|---|
| 原理 | 原生拦截手柄键 → JS 合成 KeyboardEvent 派发给页面 | 手柄键直接交给 WebView，等同 USB 键盘 |
| 优点 | 与站点自己的键位约定一致；不依赖站点的手柄支持；工具条/焦点永不被网页抢走 | 零 JS 参与，延迟最低 |
| 缺点 | 依赖页面脚本正常加载（极少数 H5 游戏不适用） | 手柄键与站点游戏键约定必须吻合；WebView 焦点管理偶有兼容问题 |
| 适用 | 默认首选；FC/SFC/街机/GBA 模拟器 | 站点已识别手柄（右上角徽标显示「站点手柄支持√」）或合成模式失灵时 |

> 站点本身支持 `getGamepads()`（`window.hassetpad`），其内部键位表存在 localStorage `ggamepadkeys`。直通模式等于把控制权交还给站点自己的手柄逻辑。

## 四、手柄热插拔

- `GamepadManager` 注册 `InputManager.InputDeviceListener`，插拔即时刷新 **设置 → 手柄连接状态**。
- 识别规则：`SOURCE_GAMEPAD`，或 `SOURCE_JOYSTICK` 且具备 BUTTON_A/B（山寨/老驱动手柄通常走这条）。
- 拔掉手柄期间，遥控器方向键/确认键与蓝牙键盘（WASD/方向/Enter/空格）依然可以完成全部 UI 操作与大部分游戏。
- 支持的振动反馈会在按钮点击时触发（驱动不支持则静默）。

## 五、验收测试矩阵（每台新手柄 15 分钟）

准备：TV/盒子 + 手柄 + 安装 `app-debug.apk`。

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 插上手柄 → 设置页 | 连接状态出现手柄名 |
| 2 | 大厅：方向键走格子，A 进游戏 | 焦点放大高亮，跟手 |
| 3 | 游戏加载完按 SELECT | 工具条弹出，焦点在「开始」，方向键可移到「退出」 |
| 4 | 焦点在「开始」按 A | 游戏开始；工具条收起，焦点回游戏 |
| 5 | 游戏内：十字键/ABXY | 角色移动、X=跳 Z=射（默认键位） |
| 6 | 长按 START 0.8s | 工具条弹出 |
| 7 | BACK | 工具条收起（再按一次=退出确认，默认焦点=继续玩） |
| 8 | 工具条→「暂停」，再进工具条→「开始」 | 画面冻结/恢复 |
| 9 | 工具条→「重置」 | 游戏回到初始状态 |
| 10 | 工具条→「存档」 | 站点存档弹窗出现；方向键可浏览；BACK 关闭 |
| 11 | 设置→按键映射：把 A 改成 `KeyC` | 游戏里 A 键变成 C 功能；重启 App 仍生效 |
| 12 | 设置→菜单呼出键改成 L3 | 手柄 L3 弹菜单 |
| 13 | 切换直通模式，重复 5 | 游戏正常（该模式依赖站点键位，个别键不同属正常） |
| 14 | 游戏进行中拔出手柄 | 无崩溃；遥控器可收工具条退出游戏 |
| 15 | 连续挂机 30 分钟再操作 | 按键不粘连（重点观察长按后方向键） |

不通过项的定位顺序：先看是否**合成/直通**选错 → 「按键映射」里确认该逻辑键的网页码与站点实际键位一致（用 WebView 调试或打开站点 PC 版观察）→ 仍异常抓 logcat `GameBridge`/`GamepadManager` 标签。

## 六、已知边界

- 部分安卓电视盒子的蓝牙栈对 HID 游戏手柄兼容性差（表现为只识别出键盘源）：用 **2.2 表末尾的小键盘/WASD 兜底映射**可玩大部分键位。
- 街机模拟器把 SELECT 用作投币，与默认菜单热键冲突：改设置或长按 START 呼菜单。
- `#max_screen`（全屏）在 WebView 内由系统决定视口，部分盒子无视觉效果，工具条常驻顶栏不受影响。
- 手柄方向键「长按连续移动」依赖 JS 端按下态去重；若某些 H5 游戏把 repeat 当作新键处理，切直通模式即可。

## 七、原生引擎模式（FC，v2 架构）

老盒子（如 Amlogic gxl + Mali-450，Android 7）上 WebView+JS 模拟器会帧率崩坏。
开启「设置 → 原生引擎」后，FC 游戏改由 **libretro FCEUmm 核心**直跑（`NativeGameActivity`），
输入链路完全不同，测试点也随之变化：

| 项 | WebView 模式 | 原生模式 |
|---|---|---|
| 按键路径 | 物理键 → 合成 KeyboardEvent → 站点混淆 JS | 物理键 → `KeySettings.logicalOf` → retro JOYPAD 位图 → 核心 |
| 方向轴 | 只有 DPAD 键码 | DPAD 键码 **+ 摇杆/HAT 轴**（`onGenericMotionEvent`，死区 0.45）都折算方向 |
| 未映射手柄键 | 交给系统（可能乱跳焦点） | **一律吞掉**，绝不让 USB 手柄杂键触发系统行为 |
| 菜单呼出 | 菜单热键 / 长按 START | 同：菜单热键 / MENU / 长按 START 8 次 |
| 验证标签 | logcat `GameBridge` | logcat `XbwCore`（loaded ok）、`NativeInput`（bits=0x…） |

原生模式快速回归（无需手柄，`adb input` 即可，实测全通过）：
```bash
adb -s <ip>:5555 shell am start -n com.xbw.tv.debug/com.xbw.tv.ui.game.NativeGameActivity \
    --es extra_id 4137 --es extra_name Contra      # 4137=魂斗罗美版，已知好样本
# 期望：logcat XbwCore "loaded ok: base=256x240 fps=60.100"；进程不崩
adb -s <ip>:5555 shell input keyevent 82            # MENU → 工具条出现（uiautomator 可见 5 键）
adb -s <ip>:5555 shell input keyevent 4             # BACK → 收起
adb -s <ip>:5555 shell input keyevent 96            # BUTTON_A → logcat NativeInput bits=0x100
# 全键位覆盖 19/20/21/22/23（方向+确认）+ 96/97（AB）+ 67/68（START/SELECT）+ 104~108（LR 等）
# 每位对应 retro JOYPAD 位：bit0=B bit1=Y bit2=SELECT bit3=START bit4..7=UDLR bit8=A …
```

非 FC 游戏（gromname 前缀不认）进 `NativeGameActivity` 会**自动降级跳 WebView 版 GameActivity**，
前台 Activity 名即验证点（logcat `RomProvider: gromname not found` / `not native-supported yet`）。

