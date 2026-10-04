# yikm.net 站点结构与选择器维护指南

> 本文档是「小霸王 TV」的**选择器唯一维护入口**。
> 本站所有数据均为实时抓取，应用内**不内置任何游戏数据**。
> 一旦站点改版导致抓取失败，按本文更新 `app/src/main/java/com/xbw/tv/data/net/SiteConfig.kt` 即可，无需改动业务代码。

---

## 一、实测基本信息（验证方式：真实 HTML + Jsoup 离线夹具，夹具保存在 `tools/*.html`）

| 项目 | 结论 |
|---|---|
| 站点首页 | `https://www.yikm.net/` |
| 列表页 | `https://www.yikm.net/nes?page=1&tag=0`（FC）等，见 §2 |
| 分页形态 | 每页 **20** 个卡片；pager 里最大数字页码 = 总页数（无显式总数字段） |
| 游戏页 | `https://www.yikm.net/play?id=<数字ID>` |
| 搜索 | `GET https://www.yikm.net/search?name=<关键词>`（UTF-8 直接百分号编码；**无分页**，一页返回全部） |
| 图片 CDN | `https://img.1990i.com/...`；封面走懒加载属性 `data-src` |
| 是否需要 Cookie/Referer | 否。普通桌面 UA 的 GET 即可；无 JS 挑战 |
| 反爬信号 | 无签名参数、无验证码；UA 为移动端时会返回另一套移动版页面（**务必用桌面 UA**） |

> 桌面 UA（`HttpFetcher` 与 `GameWebConfig` 都用这个）：
> `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36`

## 二、页面 → URL → 选择器 总表（对应 SiteConfig.kt 常量）

### 2.1 列表/分类页

```
GET /nes?page={页码}&tag={tag}&e={e}
```

| 分类 | 参数 | 说明 |
|---|---|---|
| 全部 | `page=1`（tag/e 留空） | 默认列表 |
| FC | `tag=0` | 红白机 |
| 街机 | `tag=9` | |
| GBA | `tag=11` | |
| MD | `tag=13` | 世嘉五代 |
| H5 | `tag=1` | |
| SFC | `e=5` | |
| DOS | `e=6` | |
| NDS | `e=7` | |
| Java | `e=8` | |
| Flash | `e=9` | |

实测返回示例（第 1 页 FC）：HTTP 200、20 张卡片、pager 最大页 500+。

### 2.2 列表页 DOM（实测结构，fixture：`tools/*.html`）

```html
<div class="card-blog">                          <!-- ★ 卡片根：SiteConfig.CARD_SELECTOR -->
  <a href="/play?id=4137">
    <div class="card-image">
      <img data-src="https://img.1990i.com/images/rom/xxx.jpg" ...>   <!-- ★ 封面：data-src 优先 -->
    </div>
  </a>
  <h4 class="card-caption">
    <a href="/play?id=4137">超级魂斗罗</a>        <!-- ★ 标题+链接：TITLE_SELECTOR -->
  </h4>
  <span class="label">FC</span>                   <!-- ★ 标签：LABEL_SELECTOR -->
  <span class="label">动作</span>
</div>
...
<ul class="pager">                               <!-- ★ 分页：PAGER_SELECTOR（不是 bootstrap 的 ul.pagination！） -->
  <li class="disabled"><a>1</a></li>
  <li><a href="/nes?page=2&tag=0">2</a></li>
  <li><a href="/nes?page=2&tag=0">下一页</a></li>
</ul>
```

要点：
- `data-src` 是懒加载真实地址，`src` 常常是占位图 → **先 `data-src` 再回退 `src`**（Parser 已做）。
- 占位图 `nopic.png` 会被过滤为「无封面」，UI 显示内置手柄占位图。
- `li.disabled`=当前页；`下一页` 文案存在即有下一页。
- 卡片里可能混入站外推广（页游广告链接），`BLOCKED_LINK_PATTERNS` 会过滤；凡 href 不含 `/play?id=` 的一律丢弃。

### 2.3 搜索页

`GET /search?name=魂斗罗` → 同样的 `div.card-blog` 卡片结构，**没有 pager**（返回条数即全部）。实测中文关键词可直接 URL-encode，无加密参数。

### 2.4 游戏页（/play?id=N）

游戏页**没有 iframe**，模拟器 canvas 直接在文档里。关键元素（原生工具条就是靠点这些按钮工作的）：

| 选择器 | 功能 | 备注 |
|---|---|---|
| `#canvas` | 模拟器画面 | WebGL/canvas 渲染 |
| `#GamePauseOrPlay` | **⇄ 开始/暂停切换**（同一个按钮） | `input` 按钮，#play_path 可见=未运行 |
| `#resetButton` | 重置 | 取不到时应用回退为 reload 页面 |
| `#max_screen` | 全屏 | 在 WebView 内效果有限，保留 |
| `#SaveListButton` | 打开存档列表 | 站点自己的弹窗 → 我们的 WEB_OVERLAY 模式 |
| `#quickGameSave` | 快速存档 | |
| `#gameControllerButton` | 站点手柄设置 | 打开 localStorage `gkeys`/`ggamepadkeys` 面板 |
| `#videoSettingButton` | 视频滤镜设置 | |
| `#SwitchShaderButton` | 切换滤镜 | |
| `#loading-screen` | 加载遮罩 | 隐藏 = 游戏就绪 |
| `#play_path` | 「点击开始」遮罩 | 可见 = 游戏未开始 |

内联变量（`__XBW.meta()` 读取）：`gameType` / `gsystem`（nes/arcade…）/ `gromname` / `gameid` / `gname`，`window.hassetpad` 表示站点检测到原生手柄。

页面有 5 处 `adsbygoogle` 广告位；`XbwWebViewClient.blockedHosts` + `__XBW.removeAds()` 双层屏蔽。

### 2.5 模拟器脚本（只读参考，不要依赖内部函数）

`https://file.yikm.net/libs/pclib.js`（约 660KB，javascript-obfuscator 混淆）：

- **按键判定比较的是 `KeyboardEvent.code` 字符串**（`'KeyZ'`/`'KeyX'`/`'KeyS'`…），不是 keyCode → 这是合成事件必须带 `code` 的原因。
- 站点自身有 `navigator.getGamepads()` 支持（`window.hassetpad`），**所以「直通模式」是存在的备选路线**。
- ⚠ 方案最初设想调用 `window.nes.pauseGame()` 等函数 —— 实测该 API 在混淆包中**不存在或不稳定**，已改为「点击站点自己的按钮 + 合成标准事件」，两条路径都不依赖内部符号。

### 2.6 ROM 直链与原生核心路线（v2 架构）

老盒子（Mali-450 / Android 7）上 WebView + JS 模拟器帧率崩坏（98% jank），
因此 FC 分类改走**原生 libretro 核心**（FCEUmm），绕开浏览器合成栈。ROM 来源规则（2025-10 实测）：

1. play 页内嵌 JS 全局变量 `gameType`（`fc`/`arcade`）+ `gsystem`（街机机型目录，如 `capcom-cps-1`）
   + `gromname`。原生路线直接正则抓：
   - FC 的 `gromname="/fcrom/目录/文件.nes"` —— 自带路径；
   - 街机的 `gromname="dino.zip"` —— **只是文件名**，多版本用 `$` 分隔（`a.zip$b.zip`）取第一个；
2. 直链 = `https://file.1990i.com` + 路径（**只有 1990i 镜像服务 ROM**，
   `file.yikm.net` 同路径 404，它只放 libs / `arcadelib.js`）；
   - FC：`/fcrom/…`（原样拼接）；
   - **街机：`/roms/fbneo/<gsystem>/<文件名>`** —— 例如
     `https://file.1990i.com/roms/fbneo/capcom-cps-1/dino.zip`。
     这条路径是抓浏览器 Network（`https://www.yikm.net/play?id=5334`）实测拿到的，
     猜 `/fbneo/`、`/arcaderom/`、`/roms/<name>` 等十几种前缀全是 404，别再猜；
3. 路径必须**逐段 URL 转义**（空格→`%20`、`[!].nes`→`%5B!%5D.nes`，nginx 拒裸字符）；
4. 文件壳：FC = 裸 iNES（`4E 45 53 1A`）；街机 = **整套 ROM 的 ZIP**（`PK\x03\x04`），
   必须原样喂给 FBNeo（它靠 zip 内 rom 名反查机型表），**不能解包取单文件**；
5. 其它资源（可选，缺失不影响启动）：`https://file.1990i.com/cheat/<rom名>.ini`；
   实测 CPS1 与 NeoGeo 均无需额外 BIOS 就能起机（FBNeo 自己会在 system 目录下建
   `fbneo/{samples,cheats,ips,romdata,…}`）。

分流实现：`RomProvider.prepare()` 抓 `gameType`/`gsystem`/`gromname` →
`coreFor(gameType)`（`fc`→fceumm，`arcade`→fbneo）→ 不认的类型返回 null。
缓存 `<filesDir>/roms/<id>/.romspec`（行1=核心名，行2=ROM 绝对路径），二次进入离线秒开。

核心构建：`vendor/` 放 libretro-common + libretro-fceumm + libretro-fbneo（上游源码），
`fceumm/CMakeLists.txt` / `fbneo/CMakeLists.txt` 编出 `libfceumm.so`、`libfbneo.so`
（AGP library 模块，`link.T` 只导 `retro_*`）；`app/src/main/cpp/xbw_core.c` 是通用宿主
（dlopen+dlsym 全部 retro_* 符号），产出 `libxbwcore.so`。
**生命周期铁律**：`retro_set_environment` 必须先于 `retro_init`
（FCEUmm 的 init 里立刻 `environ_cb(GET_LOG_INTERFACE)`，顺序反了 blr NULL 段错误）。

#### 2.6.1 SFC / GBA / MD（2026-10 实测扩展）

这三个系统的 play 页 **没有** `gameType`/`gsystem`，路由只能靠 `gromname` 前缀：

| 系统 | gromname 形态 | 直链 | 壳 | 核心 |
|------|--------------|------|----|------|
| GBA | `/gbarom/0060.zip` | 原样拼接 | ZIP，内含单个 `.gba`，**要解包** | `libmgba.so` |
| MD | `/mdrom/md1.zip` | 原样拼接 | ZIP，内含单个 `.md`，**要解包** | `libgenesis_plus_gx.so` |
| SFC | `/sfc/Seiken Densetsu 2 (Japan).7z` | 原样拼接（空格要转义） | **7z**，内含单个 `.sfc` | `libsnes9x.so` |

- 站点 zip 内文件名是 **GBK 编码**（中文游戏名），`java.util.zip` 直接抛
  `MALFORMED`——统一改用 commons-compress `ZipArchiveInputStream(..., "GBK")`；
- 7z 解包用 commons-compress `SevenZFile`（1.21，自带 LZMA2），入口套
  `SeekableInMemoryByteChannel`（站点 7z 仅 1~3MB）；`SevenZFile(File)` 会走
  `File.toPath()`（API 26+）崩 API 25，必须避开；脱糖依赖相应换成
  `desugar_jdk_libs_nio`（commons-compress 内部引用 `java.nio.file`）；
- 实测基线（p230 / arm64）：SFC 圣剑传说2 256×224@60.1、GBA 炎之斗士 240×160@59.7、
  MD 三国战记 256×192@59.9，全部满速；
- 核心模块：`snes9x/`（vendor/snes9x，C++14+c++_static）、`mgba/`（复用上游
  CMake，`BUILD_LIBRETRO=ON`+前端全关，目标 `mgba_libretro` 需改 `PREFIX=lib`、
  `OUTPUT_NAME=mgba` 才会被 AGP 打包）、`genesis_plus_gx/`（纯 C，不编 CHD，
  静态 tremor + 自带 zlib 6 文件，libretro-common 用仓库自带的）。
- 仍未覆盖：NDS/Java/DOS/Flash（无轻量 libretro 核心或站点走 ruffle/独立 wasm）。

### 2.7 帧率：为什么必须把仿真和 present 解耦（实测数据）

在仿真线程里直接 `ANativeWindow_lock` + `unlockAndPost` 看着简单，实测**帧率被显示管线锁死**：

| | 改之前 | 改之后 |
|---|---|---|
| FC 实测 fps | **50.00**（目标 60.10） | 59.8 ~ 60.9 |
| CPS1 实测 fps | **50.00**（目标 59.63） | 58.2 ~ 60.3 |
| 音频帧/秒 | 39,900 / 48,000（**喂不饱**） | 47,900 / 48,000 |
| 每帧耗时 | run 19.8ms / **vid 14.2ms** | run 4~7ms / vid 0.1ms |

`vid` 那 14ms 不是 memcpy，是 `ANativeWindow_lock` 等 SurfaceFlinger 放行 BufferQueue。
于是循环被压到 ~50Hz → 音频每秒少产 8000 样本 → **游戏慢 17%、音调偏低**，
而 `retro_get_system_av_info()` 报的 60.10 是**目标值不是实测值**，只看它会误判"帧率正常"。

改法：仿真线程只把帧 memcpy 进 3 槽环形交接池就返回（0.1ms），
独立渲染线程取**最新**一帧 present、积压的全丢；音频 WRITE_BLOCKING 重新成为唯一主时钟。
代价：这台盒子 SurfaceFlinger 只能吃 ~50 帧/秒，所以每秒丢 ~10~25 帧视频
（游戏速度/音调正确，画面掉帧）—— 这是显示管线的硬上限，不是核心跑不动。

`logcat -s XbwCore:I` 每秒一行可直接核对：
`fps measured=.. target=.. frames/s=../48000 run=.. vid=.. aud=.. drop=..` 与 `render blits/s=..`。

### 2.8 金手指接口（/cheat?id=，App 内 CheatParser.kt 实现）

端点：`GET https://www.yikm.net/cheat?id=<游戏id>`，响应体是**纯文本**（非 JSON/HTML），
逗号分隔的条目列表，空响应 = 该游戏没有金手指（App 侧按空列表处理，不算错误）：

```
0590-01-B0$1P血槽,00B3-04-9A9A9A9A$生命无限,7A00-01-FF$1P$HP
```

每段 `ADDR-TYPE-VAL$名称` 的实测结论：

| 字段 | 含义 | 坑 |
|---|---|---|
| ADDR | 十六进制起始地址 | FC 地址空间 0x0000-0xFFFF，越界数据直接丢弃 |
| TYPE | 站点自己的分类字段 | **与字节数无关**（实测 `007F-14-1E1E1E1E` TYPE=14 但 VAL 只有 4 字节），解析时忽略 |
| VAL | 十六进制数据 | `长度/2` = 字节数，逐字节写到 ADDR、ADDR+1 …（多字节必须自己展开） |
| 名称 | `$` 后的展示文本 | **只按第一个 `$` 切**——存在 `7A00-01-FF$1P$HP` 这种名字里带 `$` 的数据 |

libretro 核心只认 `AAAA:VV` 形式（FCEUmm 的 `retro_cheat_set` 以 `+,;._ ` 做 strtok，
每段必须恰好 7 字符），所以多字节码要展开成 `00B3:9A,00B4:9A,…` 用逗号连接后
整串喂一次 `retro_cheat_set`。解析与单测见 `CheatParser.kt` / `CheatParserTest.kt`。

**平台限制（上游结论，勿再踩）**：街机 FBNeo 的 `retro_cheat_reset/set` 在
`vendor/fbneo/src/burner/libretro/libretro.cpp` 是**空函数**，且上游目录不宜手改
→ 金手指功能**只对 FC（fceumm）生效**，App 在非 FC 核心上直接提示「该平台暂不支持金手指」。

**线程铁律**：`retro_cheat_set` 会替换核心的全局读处理器（FCEUmm: `FCEUI_AddCheat`），
**只能从仿真线程调用**；UI 线程经 `RetroCore.setCheats()` 排队（`xbw_core.c` 的
`g_cheat_codes + g_cheat_dirty + mutex`），emu 循环每帧检查 dirty 后整表重放
（`retro_cheat_reset()` 清空 → 逐条 set，避免反复切换叠加）。读档会重建核心内存，
`RetroCore.loadState()` 成功后自动重放当前已开启列表。

## 三、注入通道为什么长这样（排错先读这节）

`GameBridge` 的 boot 脚本（随页面注入，暴露 `window.__XBW`）合成事件的方式：

```js
var e = new KeyboardEvent(type, { key:key, code:code, location:0, bubbles:true, cancelable:true });
Object.defineProperty(e, 'keyCode', { get: function(){ return kc; } });
Object.defineProperty(e, 'which',   { get: function(){ return kc; } });
```

三条铁律（均为实测结论）：
1. **`new KeyboardEvent('keydown', {keyCode:88})` 里的 keyCode 会被忽略**——它不在 `KeyboardEventInit` 字典里，必须用 `defineProperty` 覆盖，且老页面可能读 `e.keyCode`，新页面读 `e.code`，两者都要给。
2. 事件要同时派发到 `document`、`window`（pclib 两个都监听），否则时灵时不灵。
3. `postWebMessage` 只能 **网页→原生**（WebMessageListener 的 postMessage 方向是反的），不能用于注入，所以注入走 `evaluateJavascript` 合成事件。

直通模式（设置页切换）：按键直接交给 Android 焦点系统 → WebView 原生派发 `keydown/keyup`，与真键盘无异；代价是失去 ABXY→方向键重映射能力。两种模式按手柄兼容性二选一。

## 四、选择器失效时的维护流程（SOP）

1. **先看 App 内诊断页**：设置 → 站点结构诊断。它会真实抓取 首页/列表/街机/搜索/游戏页，显示 HTTP 状态、HTML 长度、原始 `card-blog` 次数、Jsoup 实际命中数。**命中数=0 就是改版了**。
2. **抓一份现场 HTML**：
   ```powershell
   curl.exe -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36" `
     -o tools/list.html https://www.yikm.net/nes?page=1
   ```
3. **离线跑夹具校验**（`tools/SelectorCheck.java`，classpath 带 jsoup 即可运行）：
   ```powershell
   javac -cp jsoup-1.17.2.jar tools/SelectorCheck.java
   java  -cp "jsoup-1.17.2.jar;tools" SelectorCheck tools/list.html
   ```
   它输出每种候选选择器的命中数，直到找到新的稳定结构。
4. **改 SiteConfig.kt**：只改 `CARD_SELECTOR / TITLE_SELECTOR / COVER_SELECTOR / PAGER_SELECTOR / LABEL_SELECTOR` 与 URL 模板。Parser 内置降级候选链（`FALLBACK_*`），降级命中时会在大厅状态行给出黄色警告文案——这本身就是「半自动改版报警」。
5. **跑一遍 App 内诊断页 + 真机点 3 个游戏**（FC/街机/H5 各一），确认注入与工具条仍正常。
6. 若游戏页按钮 id 变了：同步改 SiteConfig 的 `JS_CLICK_*` 常量与 `GameBridge` 的对应方法（一一对应，很好找）。

## 五、反爬/合规红线

- 抓取频率已由 `GameRepository`（同分类串行 Mutex）+ OkHttp 缓存 + Room 缓存控制，不要改成并发轰炸。
- 应用仅提供**技术演示**；列表与 ROM 版权均属原站与原作者，UI 层不存储、不转发任何游戏文件。
- 广告屏蔽只作用于 WebView 内（体验用途），保留 `removeAds` 开关。
