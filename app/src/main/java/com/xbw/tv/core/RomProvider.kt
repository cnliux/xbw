package com.xbw.tv.core

import android.content.Context
import android.util.Log
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.SiteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.File
import java.io.FileInputStream

/**
 * ▶ ROM 获取层：yikm 游戏 id → 可直接喂给 libretro 核心的本地 ROM 文件。
 *
 * 实测规则（2025-10，工具链验证过，见 docs/YIKM_SITE_STRUCTURE.md §2.6）：
 *  1. play 页内嵌 JS 全局变量 gameType="fc"/"arcade" + gromname；
 *     FC 的 gromname 是 URL 相对路径（/fcrom/目录/文件.nes），街机的只是文件名
 *     （dino.zip，多版本用 $ 分隔）；
 *  2. 拼 https://file.1990i.com + 路径即原始文件直链（FC 返回裸 .nes，
 *     街机/MD/GBA 返回 ZIP；file.yikm.net 同路径 404，只有 1990i 镜像全）；
 *  3. ZIP 用 java.util.zip 解开取第一个大文件（站点打包无嵌套）。
 *
 * 街机附加件（FBNeo 需要，2025-10 抓包验证）：
 *  - BIOS：gsystem=snk-neo-geo → neogeo.zip，PGM → pgm.zip；
 *    源在 file.yikm.net 根目录（1990i 上没有），全局缓存 <filesDir>/bios/；
 *  - 金手指 ini：https://file.1990i.com/cheat/<zip名去后缀>.ini，落
 *    <systemDir>/fbneo/cheats/<名>.ini（FBNeo 按 DRV_NAME 即 zip 名反查）；
 *    修改版名会 404，回退到 $ 分隔的基础版名，内容写到每个段名一份。
 *    站点没有时兜底 GitHub 官方库 finalburnneo/FBNeo-cheats（约 3400 款，
 *    同格式 ini），走 wget.la / gh-proxy 等镜像直连 raw（国内 raw 不稳）。
 *  - 多版本（kof98eck20.zip$kof98.zip）：修改版 zip 的驱动名往往不在本版
 *    FBNeo 数据表里 → 下载全部段，主段加载失败时逐段兜底（见 RetroCore
 *    .loadAlternativeRom）。
 *
 * 缓存：<filesDir>/roms/<gameId>/ 下命中即复用 → 二次进入离线秒开；
 * 缓存总量封顶 500MB，超限按"最久未玩"整目录清（连存档），回 400MB 停。
 * .romspec 行格式：[0]核心名 [1]主 ROM 绝对路径 [2]gsystem（FC 为空串）
 *                  [3..]备选 ROM 绝对路径；旧行数 <3 视为旧格式，作废重取。
 */
object RomProvider {

private const val TAG = "RomProvider"
private const val ROM_HOST = "https://file.1990i.com"
private const val CDN_HOST = "https://file.yikm.net"

/** GitHub raw 镜像前缀：**启动时 CdnPicker 实测排序**，谁快谁在前
 *  （未测速时退回默认顺序；空串=直连兜底）。金手指库与自建 BIOS 库共用 */
private fun ghMirrors(): List<String> = com.xbw.tv.data.net.CdnPicker.ranked()

/** FBNeo 官方金手指库（GitHub，master 分支 cheats/<驱动名>.ini，约 3400 款） */
private const val GH_CHEAT_RAW = "https://raw.githubusercontent.com/finalburnneo/FBNeo-cheats/master/cheats"

/** 自建 BIOS 库：cnliux/xbw 的 fbneo/ 目录（版本与打包的 FBNeo 核心匹配，
 *  解决 file.yikm.net 旧版 BIOS 在新核心下 CRC 校验不过的问题） */
private const val GH_BIOS_RAW = "https://raw.githubusercontent.com/cnliux/xbw/master/fbneo"

/** play 页正常但没有 gromname 时的提示（NDS/DOS/Java/Flash/H5 等网页版） */
const val NO_ROM_PLATFORM = "网页版游戏"

    data class RomSpec(
        val coreName: String,      // "fceumm"/"fbneo" … 对应 lib<coreName>.so
        val romFile: File,
        val systemDir: File,
        val fromCache: Boolean,
        val fallbacks: List<File> = emptyList(),   // 主 ROM 加载失败时的兜底 zip
    )

    /**
     * [prepare] 的结果。区分三类失败，UI 才能给出**对症**的提示：
     * 以前一律返回 null → 全显示"该平台暂无原生核心"，网络/站点问题会被误报成缺核心。
     */
    sealed class RomResult {
        data class Ready(val spec: RomSpec) : RomResult()
        /** 站点有这款游戏，但本 App 没编对应核心（NDS / Java / DOS / Flash / H5） */
        data class Unsupported(val platform: String) : RomResult()
        /** 网络/站点/解包失败，可重试 */
        data class Failed(val reason: String) : RomResult()
    }

    /** 街机机种 → BIOS zip 文件名（源 file.yikm.net 根目录）；null = 无需 BIOS */
    private fun biosFor(gsystem: String): String? = when (gsystem.lowercase()) {
        "snk-neo-geo" -> "neogeo.zip"   // 拳皇/合金弹头等 NeoGeo 全家
        "pgm" -> "pgm.zip"              // 三国战纪/西游释厄传（IGS 基板）
        else -> null
    }

    private fun cacheDirOf(context: Context, gameId: String): File =
        File(context.filesDir, "roms/$gameId").apply { mkdirs() }

    /* 缓存封顶：roms/ 总量 >500MB 触发清理，清回 400MB 停（留 100MB 余量避免
     * 每次进游戏都触发删除）。按"最久未玩"（目录 mtime）整目录删——ROM 和
     * <rom>.sram 存档都在游戏目录里，清掉即一并回收；BIOS 全局缓存极小不计入。 */
    private const val MAX_CACHE_BYTES = 500L * 1024 * 1024
    private const val EVICT_TARGET_BYTES = 400L * 1024 * 1024

    /** 缓存超限清理：删除最久未访问的游戏缓存目录，直到总量回落到目标 */
    private fun evictIfNeeded(context: Context, keepGameId: String) {
        try {
            val roms = File(context.filesDir, "roms")
            var total = roms.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            Log.w(TAG, "rom cache ${total / 1048576}MB exceeds ${MAX_CACHE_BYTES / 1048576}MB cap, evicting LRU")
            val dirs = roms.listFiles()
                ?.filter { it.isDirectory && it.name != keepGameId }
                ?.sortedBy { it.lastModified() }
                ?: return
            for (d in dirs) {
                if (total <= EVICT_TARGET_BYTES) break
                val bytes = d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                if (d.deleteRecursively()) {
                    total -= bytes
                    Log.i(TAG, "evicted rom cache ${d.name} (~${bytes / 1048576}MB)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "rom cache evict failed", e)
        }
    }

    /**
     * 下载（或命中缓存）游戏 [gameId] 的 ROM。
     * @return 成功给 [RomResult.Ready]；无核心 / 下载失败分别给对应子类，UI 提示不混淆
     */
    suspend fun prepare(
        context: Context,
        gameId: String,
        onProgress: ((done: Long, total: Long) -> Unit)? = null
    ): RomResult {
        val dir = cacheDirOf(context, gameId)
        evictIfNeeded(context, gameId)
        // 缓存命中：.romspec 记录核心名 + ROM 文件位置 + 兜底列表
        val meta = File(dir, ".romspec")
        if (meta.exists()) {
            val lines = meta.readLines()
            if (lines.size >= 3) {
                val f = File(lines[1])
                // v0.0.8 自愈：normalizeFbneoZip 曾误把 BIOS 解成 ROM 写进 .romspec，
                // 命中这种污染缓存直接作废重取（BIOS 名永远不是游戏）
                if (lines[0] == "fbneo" && isBiosRomName(f.name)) meta.delete()
            }
            if (meta.exists()) {
                val lines = meta.readLines()
                if (lines.size >= 3) {
                    val f = File(lines[1])
                    if (f.exists() && f.length() > 16) {
                        val fallbacks = lines.drop(3).mapNotNull { l ->
                            val ff = File(l)
                            if (ff.exists() && ff.length() > 16) ff else null
                        }
                        dir.setLastModified(System.currentTimeMillis())   // 记录访问时间供 LRU
                        Log.i(TAG, "rom cached id=$gameId core=${lines[0]} fallbacks=${fallbacks.size}")
                        return RomResult.Ready(RomSpec(lines[0], f, File(dir, "system"), true, fallbacks))
                    }
                }
                // 旧格式（<3 行）或文件丢失：作废重取
                meta.delete()
            }
        }

        // 1) play 页 → gameType / gromname / gsystem（路由规则见 CoreRouter）
        var core = ""
        var gsystem = ""
        var romSegments: List<String> = emptyList()
        var romPath = ""
        when (val page = fetchPlayPage(gameId)) {
            is CoreRouter.PlayResult.FetchFailed ->
                return RomResult.Failed("游戏页抓取失败（网络不通或站点结构已变）")
            // 页面正常却没有任何 ROM 字段：NDS/DOS/Java/Flash/H5 这类网页版
            is CoreRouter.PlayResult.NoRom -> return RomResult.Unsupported(NO_ROM_PLATFORM)
            is CoreRouter.PlayResult.Ok -> {
                val info = page.info
                val routed = CoreRouter.coreFor(info.gameType, info.gromname)
                if (routed == null) {
                    Log.i(TAG, "gameType=${info.gameType} grom=${info.gromname} not native-supported yet")
                    return RomResult.Unsupported(info.gameType?.takeIf { it.isNotBlank() } ?: "该平台")
                }
                core = routed
                gsystem = info.gsystem
                // 街机多版本用 $ 分隔（"修改版.zip$基础版.zip"），全部要下载
                romSegments = info.gromname.split('$').map { it.trim() }.filter { it.isNotBlank() }
                romPath = when {
                    // FBNeo 要整套 zip（靠里面的 rom 名反查机型表），其余平台要单个 rom 文件
                    core != "fbneo" -> info.gromname
                    romSegments.isEmpty() -> return RomResult.Failed("街机 ROM 名为空（id=$gameId）")
                    gsystem.isBlank() -> {
                        Log.w(TAG, "gsystem missing for arcade id=$gameId")
                        return RomResult.Failed("街机机型字段缺失（id=$gameId）")
                    }
                    else -> "roms/fbneo/$gsystem/${romSegments.first()}"
                }
                if (romPath.isBlank()) {
                    Log.w(TAG, "empty rom path id=$gameId grom=${info.gromname}")
                    return RomResult.Failed("ROM 路径为空（id=$gameId）")
                }
            }
        }
        val zip = core == "fbneo"

        // 2) 直链下载（路径逐段转义，空格/中括号原样在 URL 里会被 nginx 拒）
        val encoded = romPath.trimStart('/').split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        val url = "$ROM_HOST/$encoded"
        // FBNeo 直接吃这个 zip（整套 rom 一个文件），所以按原名落盘方便排查
        val raw = if (zip) File(dir, romPath.substringAfterLast('/')) else File(dir, "rom.raw")
        try {
            HttpFetcher.downloadToFile(url, raw, referer = SiteConfig.BASE_URL + "/", onProgress = onProgress)
        } catch (e: Exception) {
            Log.w(TAG, "rom download failed $url", e)
            raw.delete()
            return RomResult.Failed("ROM 下载失败（$url）")
        }

        // 3) 压缩壳：FBNeo 整套留用（中文名嵌套包解内层）；其余拆出单个 rom（zip 或 7z）
        val rom = when {
            zip -> normalizeFbneoZip(dir, raw)
            is7zFile(raw) -> unwrap7z(dir, raw) ?: run {
                raw.delete()
                return RomResult.Failed("7z 解包失败（ROM 壳损坏或格式不支持）")
            }
            else -> unwrapZip(dir, raw) ?: run {
                raw.delete()
                return RomResult.Failed("ZIP 解包失败（ROM 壳损坏或格式不支持）")
            }
        }
        if (rom.length() <= 16) {
            rom.delete()
            return RomResult.Failed("ROM 内容为空（下载到了 HTML 错误页？）")
        }

        // 街机专属：兜底 zip + BIOS + 金手指 ini
        val fallbacks = if (zip) downloadFallbacks(dir, gsystem, romSegments.drop(1)) else emptyList()
        val sysDir = File(dir, "system").apply { mkdirs() }
        if (zip) {
            ensureBios(context, gsystem, sysDir)
            ensureCheatIni(sysDir, romSegments)
        }

        // 4) 写规格缓存
        meta.writeText(listOf(core, rom.absolutePath, gsystem)
            .plus(fallbacks.map { it.absolutePath }).joinToString("\n"))
        Log.i(TAG, "rom ready id=$gameId core=$core size=${rom.length()} fallbacks=${fallbacks.size}")
        return RomResult.Ready(RomSpec(core, rom, sysDir, false, fallbacks))
    }

    /**
     * 第三方插件源的直链 ROM：跳过 play 页，直接从 [romUrl] 下载。
     *
     * 插件条目在 XML 里只给相对路径 + 用户在编辑页选的平台，没有 yikm 的
     * gameType/gromname/gsystem。核心名由 [CoreRouter.coreForPlatform] 从平台 key
     * 直接映射（fc→fceumm 等）——"如果是 FC 就用之前的核心"，不用给插件重新编核心。
     *
     * 街机（fbneo）需要整套 zip 且依赖 BIOS，插件源没给 gsystem，只能尽力而为：
     * zip 原样落盘试跑，缺 BIOS 时核心自己会报错，属于插件源该补的平台信息。
     */
    suspend fun prepareDirect(
        context: Context,
        gameId: String,
        romUrls: List<String>,
        platformKey: String?,
        cookie: String?,
        romFileName: String? = null,
        onProgress: ((done: Long, total: Long) -> Unit)? = null
    ): RomResult {
        val dir = cacheDirOf(context, gameId)
        evictIfNeeded(context, gameId)
        val meta = File(dir, ".romspec")
        if (meta.exists()) {
            val lines = meta.readLines()
            if (lines.size >= 3) {
                val f = File(lines[1])
                if (f.exists() && f.length() > 16 && !(lines[0] == "fbneo" && isBiosRomName(f.name))) {
                    dir.setLastModified(System.currentTimeMillis())
                    val sysDir = File(dir, "system")
                    // 自愈：老缓存下载时还没有"插件源下金手指"这一步，命中时补装；
                    // 中文名嵌套盗版包同样在此解出内层驱动 zip（幂等，正常包原样返回）
                    if (lines[0] == "fbneo") {
                        val nf = normalizeFbneoZip(dir, f)
                        if (nf != f) {
                            meta.writeText(listOf(lines[0], nf.absolutePath, "")
                                .plus(lines.drop(3)).joinToString("\n"))
                            ensureBiosForZip(context, nf, sysDir)
                            ensureCheatIni(sysDir, listOf(nf.name))
                            return RomResult.Ready(RomSpec(lines[0], nf, sysDir, true))
                        }
                        ensureBiosForZip(context, f, sysDir)
                        ensureCheatIni(sysDir, listOf(f.name))
                    }
                    return RomResult.Ready(RomSpec(lines[0], f, sysDir, true))
                }
            }
            meta.delete()
        }
        val core = CoreRouter.coreForPlatform(platformKey)
            ?: return RomResult.Unsupported(platformKey?.let { GameCategory.fromKey(it).title } ?: "该平台")
        if (romUrls.isEmpty()) return RomResult.Failed("插件条目没有可用的 ROM 直链")

        // 1) 候选地址逐个试：GBK 直链 → 通用直链 → down.php 网关，
        //    直到拿到一个不是 HTML/挑战页的真文件（40x、挑战页都换下一个）
        //    FBNeo 必须按 zip 原名落盘（DRV_NAME 依赖文件名），所以 raw 用
        //    原始 zip 名而不是 rom.raw
        val raw = File(dir, if (core == "fbneo" && !romFileName.isNullOrBlank()) romFileName else "rom.raw")
        var lastError = "所有候选地址均不可用"
        var ok = false
        for (url in romUrls) {
            raw.delete()
            try {
                val headers = cookie?.let { mapOf("Cookie" to "__test=$it") } ?: emptyMap()
                HttpFetcher.downloadToFile(url, raw, headers = headers, onProgress = onProgress)
            } catch (e: Exception) {
                lastError = e.message ?: "网络异常"
                Log.w(TAG, "plugin rom candidate failed: $url（$lastError）")
                continue
            }
            if (raw.length() <= 16) {
                lastError = "源返回了空/错误页（${raw.length()}B）"
                Log.w(TAG, "plugin rom candidate empty: $url")
                continue
            }
            if (looksLikeChallenge(raw)) {
                lastError = "源返回了挑战页（cookie 可能失效）"
                Log.w(TAG, "plugin rom candidate is challenge/html: $url")
                continue
            }
            ok = true
            break
        }
        if (!ok) {
            raw.delete()
            return RomResult.Failed("ROM 下载失败（$lastError）")
        }

        // 2) 解包：FBNeo 要整套 zip（中文名嵌套盗版包解内层驱动 zip）；其余拆出单个 rom
        val zip = core == "fbneo"
        val rom = when {
            zip -> normalizeFbneoZip(dir, raw)
            is7zFile(raw) -> unwrap7z(dir, raw) ?: run {
                raw.delete()
                return RomResult.Failed("7z 解包失败（ROM 壳损坏或格式不支持）")
            }
            else -> unwrapZip(dir, raw) ?: run {
                raw.delete()
                return RomResult.Failed("ZIP 解包失败（ROM 壳损坏或格式不支持）")
            }
        }
        if (rom.length() <= 16) {
            rom.delete()
            return RomResult.Failed("ROM 内容为空（下载到了 HTML 错误页？）")
        }

        // 3) 写规格缓存（插件源没有 gsystem/兜底；BIOS 从 zip 内文件名反推补装）
        val sysDir = File(dir, "system").apply { mkdirs() }
        if (zip) {
            ensureBiosForZip(context, rom, sysDir)
            // FBNeo 按 DRV_NAME（= zip 名）反查 ini，插件源街机同样要下金手指
            ensureCheatIni(sysDir, listOf(rom.name))
        }
        meta.writeText(listOf(core, rom.absolutePath, "").joinToString("\n"))
        Log.i(TAG, "plugin rom ready id=$gameId core=$core size=${rom.length()}")
        return RomResult.Ready(RomSpec(core, rom, sysDir, false))
    }

    /**
     * U盘/本地 ROM：文件已在盒子上，不下载直接喂核心。
     *
     * FBNeo 必须整套 zip（靠 zip 内文件名匹配机型表），原样引用 U盘文件；
     * 其它平台里 zip/7z 是壳的，先在缓存目录解出单个 rom（**绝不删 U盘原文件**——
     * unwrap* 会删它的输入，所以先拷进缓存再解）。
     * 写 .romspec 缓存，二次进直接命中。缺核心时仍按“没核心”提示。
     */
    suspend fun prepareLocal(
        context: Context,
        gameId: String,
        localFile: File,
        platformKey: String?
    ): RomResult {
        if (!localFile.exists() || localFile.length() <= 16) {
            return RomResult.Failed("U盘 ROM 文件不可用（未挂载或文件已删除）")
        }
        val core = CoreRouter.coreForPlatform(platformKey)
            ?: return RomResult.Unsupported(platformKey?.let { GameCategory.fromKey(it).title } ?: "该平台")
        val dir = cacheDirOf(context, gameId)
        val meta = File(dir, ".romspec")
        val sysDir = File(dir, "system").apply { mkdirs() }
        val isShell = localFile.name.endsWith(".zip", ignoreCase = true) || is7zFile(localFile)
        var rom = when {
            core == "fbneo" -> localFile                    // 整套 zip 原样给
            !isShell -> localFile                            // 单文件直接给
            else -> {                                        // 壳文件：缓存目录里解开
                val copy = File(dir, "usb_copy.${localFile.extension.ifBlank { "rom" }}")
                runCatching { localFile.copyTo(copy, overwrite = true) }.getOrNull()
                when {
                    copy.length() <= 16 -> localFile
                    is7zFile(copy) -> unwrap7z(dir, copy) ?: copy
                    else -> unwrapZip(dir, copy) ?: copy
                }
            }
        }
        if (rom.length() <= 16) return RomResult.Failed("ROM 文件为空")
        if (core == "fbneo") {
            // 中文名嵌套盗版包：解内层驱动 zip 到缓存目录（U盘原文件不动）
            rom = normalizeFbneoZip(dir, rom)
            ensureBiosForZip(context, rom, sysDir)
            ensureCheatIni(sysDir, listOf(rom.name))
        }
        meta.writeText(listOf(core, rom.absolutePath, "").joinToString("\n"))
        Log.i(TAG, "usb/local rom ready id=$gameId core=$core path=${rom.absolutePath} size=${rom.length()}")
        return RomResult.Ready(RomSpec(core, rom, sysDir, false))
    }

    /** 只抓 play 页判断这款游戏有没有原生核心（**不下载 ROM**）。
     * 搜索结果过滤用它：远程搜索会返回 Java/NDS/DOS/Flash 等没核心的平台，
     * 没有这层过滤用户点进去只会看到"暂无原生核心"。
     */
    suspend fun probePlatform(gameId: String): CoreRouter.Platform =
        when (val page = fetchPlayPage(gameId)) {
            is CoreRouter.PlayResult.Ok -> CoreRouter.platformOf(page.info)
            // 页面正常但站点没给 ROM 直链 = 确定没有原生核心，可以放心写缓存
            is CoreRouter.PlayResult.NoRom -> CoreRouter.Platform.Unsupported
            // 抓不到就无法判定，保守放行且不写缓存
            is CoreRouter.PlayResult.FetchFailed -> CoreRouter.Platform.Unknown
        }

    /** 抓 play 页并解析平台字段；抓不到返回 [CoreRouter.PlayResult.FetchFailed] */
    private suspend fun fetchPlayPage(gameId: String): CoreRouter.PlayResult {
        val html = try {
            HttpFetcher.fetchHtml(SiteConfig.playUrl(gameId))
        } catch (e: Exception) {
            Log.w(TAG, "play page fetch failed id=$gameId", e)
            return CoreRouter.PlayResult.FetchFailed
        }
        return CoreRouter.parsePlay(html) ?: CoreRouter.PlayResult.FetchFailed
    }

    /** 逐个下载 $ 分隔的后续段（基础版 zip）；单个失败不致命，跳过即可 */
    private suspend fun downloadFallbacks(dir: File, gsystem: String, names: List<String>): List<File> {
        val out = mutableListOf<File>()
        for (name in names) {
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 16) {
                out.add(dest)
                continue
            }
            val segPath = "roms/fbneo/$gsystem/$name"
            val encoded = segPath.split('/').joinToString("/") { seg ->
                java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
            try {
                HttpFetcher.downloadToFile("$ROM_HOST/$encoded", dest, referer = SiteConfig.BASE_URL + "/")
                if (dest.length() > 16) out.add(dest) else dest.delete()
            } catch (e: Exception) {
                Log.w(TAG, "fallback rom download failed $name", e)
                dest.delete()
            }
        }
        return out
    }

    /**
     * 保证 BIOS 就位：全局缓存 <filesDir>/bios/<name>（多游戏共享一次下载），
     * 再复制到本游戏的 systemDir 根（FBNeo 在 system 目录下找 neogeo.zip/pgm.zip）。
     */
    private suspend fun ensureBiosFile(context: Context, bios: String, sysDir: File) {
        val cache = fetchBiosToCache(context, bios) ?: return
        val dest = File(sysDir, bios)
        if (dest.exists() && dest.length() == cache.length()) return
        try {
            cache.copyTo(dest, overwrite = true)
            Log.i(TAG, "bios ready: ${dest.name} (${dest.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "bios copy failed $bios", e)
        }
    }

    /** 缓存里没有就竞速下载；返回全局缓存文件，全部源失败返回 null */
    private suspend fun fetchBiosToCache(context: Context, bios: String): File? {
        val cache = File(context.filesDir, "bios/$bios")
        if (cache.exists() && cache.length() > 16) return cache
        cache.parentFile?.mkdirs()
        val got = raceDownloadTo(cache.parentFile!!, bios)
        return if (got != null) {
            Log.i(TAG, "bios cached: $bios (${got.length()} bytes)")
            got
        } else {
            Log.w(TAG, "bios download failed from all sources: $bios")
            null
        }
    }

    /**
     * 多 CDN **并发竞速**下载 BIOS：自建 GitHub 库的各镜像（wget.la / gh-proxy /
     * ghfast / 直连）与 file.yikm.net 同时开下，谁先下完用谁，其余立刻取消。
     * 顺序试的缺点是"最慢的源排在前面就全等它"，盒子网络对各家 CDN 快慢不一，
     * 只有并发才能稳定拿到当前网络下的最快源（同 UpdateChecker.pickSources 思路，
     * BIOS 只有几 MB，省去探活直接开下）。
     */
    private suspend fun raceDownloadTo(biosDir: File, bios: String): File? = coroutineScope {
        val urls = ghMirrors().map { it + "$GH_BIOS_RAW/$bios" } + "$CDN_HOST/$bios"
        // 每次调用独立后缀，避免并发/残留的 .part 互相踩
        val token = java.util.UUID.randomUUID().toString().take(8)
        biosDir.listFiles()?.forEach {
            if (it.name.startsWith("$bios.part")) runCatching { it.delete() }
        }
        val done = Channel<Pair<Int, Boolean>>(urls.size)
        val jobs = urls.mapIndexed { i, url ->
            launch(Dispatchers.IO) {
                val tmp = File(biosDir, "$bios.part.$i.$token")
                val ok = runCatching {
                    HttpFetcher.tryGetFile(url, tmp, timeoutSec = 30) && tmp.length() > 16
                }.getOrDefault(false)
                done.send(i to ok)
            }
        }
        var winner = -1
        var settled = 0
        while (winner < 0 && settled < urls.size) {
            val (i, ok) = done.receive()
            settled++
            if (ok) winner = i
        }
        jobs.forEach { it.cancel() }   // 输家：中断下载（阻塞 IO 结束后自行退出）
        val cache = File(biosDir, bios)
        if (winner < 0) {
            biosDir.listFiles()?.forEach {
                if (it.name.startsWith("$bios.part")) runCatching { it.delete() }
            }
            return@coroutineScope null
        }
        val win = File(biosDir, "$bios.part.$winner.$token")
        if (!win.renameTo(cache)) runCatching { win.copyTo(cache, overwrite = true) }
        biosDir.listFiles()?.forEach {
            if (it.name.startsWith("$bios.part")) runCatching { it.delete() }
        }
        cache.takeIf { it.exists() && it.length() > 16 }
    }

    /** 启动预热要覆盖的机种 BIOS（体积共约 3.5MB，覆盖绝大多数插件源街机） */
    private val PREWARM_BIOS = listOf("neogeo.zip", "pgm.zip")

    /** 还缺哪些预热 BIOS（UI 据此决定要不要提示；命中缓存则为空） */
    fun pendingPrewarmBios(context: Context): List<String> =
        PREWARM_BIOS.filter {
            val f = File(context.filesDir, "bios/${it}")
            !f.exists() || f.length() <= 16
        }

    /**
     * 启动后台预热常用街机 BIOS（竞速下载）。
     * @return true = 全部就位；false = 有失败（进游戏时懒加载会再兜底重试）
     */
    suspend fun prewarmBios(context: Context): Boolean {
        var all = true
        for (b in pendingPrewarmBios(context)) {
            if (fetchBiosToCache(context, b) == null) all = false
        }
        return all
    }

    private suspend fun ensureBios(context: Context, gsystem: String, sysDir: File) {
        ensureBiosFile(context, biosFor(gsystem) ?: return, sysDir)
    }

    /**
     * 插件源/USB 的街机 zip 没有 gsystem，BIOS 两步补齐：
     * ① 全局缓存（启动预热的 neogeo.zip/pgm.zip）里有什么就**无条件铺进**
     *    system 目录——FBNeo 只会用它需要的那份，多余无害。NeoGeo 驱动名无法
     *    枚举，靠这一步兜底；kovsh 这类 PGM 游戏 zip 内全是游戏自己的 ROM，
     *    光看内部文件名认不出机种，同样靠这一步命中。
     * ② 缓存里没有的（预热失败/离线）再按驱动名前缀 + zip 内文件名推断机种，
     *    竞速下载。读不出/不匹配就不装（多数 CPS/STV 基板本来也不需要 BIOS）。
     */
    private suspend fun ensureBiosForZip(context: Context, romZip: File, sysDir: File) {
        File(context.filesDir, "bios").listFiles()
            ?.filter { it.isFile && it.length() > 16 }
            ?.forEach { b ->
                val dest = File(sysDir, b.name)
                if (!dest.exists() || dest.length() != b.length()) {
                    runCatching { b.copyTo(dest, overwrite = true) }
                        .onSuccess { Log.i(TAG, "bios ready: ${dest.name} (${dest.length()} bytes)") }
                }
            }
        val driver = romZip.name.substringBeforeLast('.').lowercase()
        val inner = try {
            java.util.zip.ZipFile(romZip).use { zip ->
                zip.entries().toList().map { it.name.lowercase() }
            }
        } catch (e: Exception) {
            emptyList()
        }
        val bios = when {
            // PGM 基板（IGS）：三国战纪 kov*/拳皇三国 kovqhs*/西游释厄传 ddp*/
            // 海盗船 pirates*/kronos*/雷虎 thunderh*；zip 内文件名为旧启发式保留
            driver.startsWith("kov") || driver.startsWith("ddp") ||
                driver.startsWith("pirates") || driver.startsWith("kronos") ||
                driver.startsWith("thunderh") ||
                inner.any { it.startsWith("pgm_") || it.startsWith("ddp3") || it.startsWith("u18") } -> "pgm.zip"
            inner.any { it.startsWith("neo-") || it == "sp-s3.sp1" || it == "sm1.sm1" || it == "sfix.sfix" } -> "neogeo.zip"
            else -> null
        } ?: return
        ensureBiosFile(context, bios, sysDir)
    }

    /**
     * 盗版街机包归一化：像"拳皇十周年纪念 2005 特别版"这类非站点源（插件/U盘）
     * 常是**中文名外层 zip 里套一个正确命名的驱动 zip**（如 kof10th.zip）。
     * FBNeo 按 DRV_NAME（= 喂给它的 zip 文件名）反查机型表，中文名外层包必挂。
     *
     * 判定极保守，只在**确定是套壳**时才解内层：
     *  - 外层里除了 zip 和纯文档（txt/nfo/图片等）外**不能有任何裸 rom 文件**——
     *    正常整合包（如 NeoGeo set 里捆绑 neogeo.zip BIOS + 一堆 .bin/.rom）
     *    必须原样喂核心，误解 BIOS 就是 v0.0.8 那次"进度条走完进不了游戏"的根因；
     *  - 内层 zip 必须**恰好一个**，且文件名是 ASCII 驱动名（中文名解出来也白解）；
     *  - 内层不能是 BIOS 包本身（neogeo.zip/pgm.zip 永远不是游戏）。
     * U盘原文件只读不改，解出的内层落在缓存目录 [dir] 里。
     */
    private fun normalizeFbneoZip(dir: File, zipFile: File): File {
        val head = ByteArray(4)
        runCatching { FileInputStream(zipFile).use { if (it.read(head) < 4) return zipFile } }
            .onFailure { return zipFile }
        if (!(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) return zipFile
        try {
            ZipArchiveInputStream(FileInputStream(zipFile), "GBK", true, false).use { zin ->
                var innerName: String? = null
                var innerEntrySeen = false
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory || e.name.endsWith("/")) continue
                    val name = e.name.substringAfterLast('/')
                    if (name.isBlank() || name.startsWith(".") ||
                        name.startsWith("__MACOSX")) continue
                    val lower = name.lowercase()
                    if (lower.endsWith(".zip")) {
                        if (innerEntrySeen) return zipFile   // 多个内层 zip：不动
                        innerName = name
                        innerEntrySeen = true
                    } else if (!isDocEntry(lower)) {
                        return zipFile   // 有裸 rom 文件 = 正常整合包，绝不能动
                    }
                }
                val inner = innerName ?: return zipFile
                val stem = inner.substringBeforeLast('.')
                // 内层必须是 ASCII 驱动名、且不是 BIOS 包
                if (!stem.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) return zipFile
                if (stem.lowercase() in setOf("neogeo", "pgm")) return zipFile
                val dest = File(dir, inner)
                if (!dest.exists() || dest.length() <= 16) {
                    // 第二遍流式解出内层
                    ZipArchiveInputStream(FileInputStream(zipFile), "GBK", true, false).use { zin2 ->
                        while (true) {
                            val e = zin2.nextEntry ?: break
                            if (e.isDirectory) continue
                            if (e.name.substringAfterLast('/')
                                    .equals(inner, ignoreCase = true)) {
                                dest.outputStream().use { os -> zin2.copyTo(os, 64 * 1024) }
                                break
                            }
                        }
                    }
                    if (!dest.exists() || dest.length() <= 16) return zipFile
                }
                Log.i(TAG, "fbneo nested zip unwrapped: ${zipFile.name} -> $inner")
                return dest
            }
        } catch (e: Throwable) {
            Log.w(TAG, "normalizeFbneoZip failed for ${zipFile.name}", e)
            return zipFile
        }
    }

    /** 纯文档/说明类条目（盗版套壳里常见 README），不算裸 rom */
    private fun isDocEntry(lowerName: String): Boolean =
        lowerName.endsWith(".txt") || lowerName.endsWith(".nfo") ||
            lowerName.endsWith(".md") || lowerName.endsWith(".pdf") ||
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") || lowerName.endsWith(".gif") ||
            lowerName.endsWith(".bmp") || lowerName.endsWith(".webp") ||
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
            lowerName.endsWith(".url") || lowerName.endsWith(".sfv") ||
            lowerName.endsWith(".xml") || lowerName.endsWith(".log") ||
            lowerName == "readme"

    /** BIOS 包名永远不可能作为 ROM 喂核心——命中即缓存被污染，作废重取 */
    private fun isBiosRomName(name: String): Boolean =
        name.substringBeforeLast('.').lowercase() in setOf("neogeo", "pgm")

    /**
     * 多个候选 URL **并发竞速**取文本，第一个通过 [accept] 的胜出，其余取消。
     * 给金手指 ini 这类小文本文件用（同 [raceDownloadTo] 的思路，BIOS 是文件版）。
     */
    private suspend fun raceFetchText(urls: List<String>, accept: (String) -> Boolean): String? =
        coroutineScope {
            val done = Channel<String?>(urls.size)
            val jobs = urls.map { url ->
                launch(Dispatchers.IO) {
                    val r = try {
                        HttpFetcher.fetchText(url).takeIf(accept)
                    } catch (e: Exception) {
                        null
                    }
                    done.send(r)
                }
            }
            var winner: String? = null
            var settled = 0
            while (winner == null && settled < urls.size) {
                val r = done.receive()
                settled++
                if (r != null) winner = r
            }
            jobs.forEach { it.cancel() }
            winner
        }

    /**
     * 下载金手指 ini：任一段名命中即用，内容写到每个段名一份——
     * FBNeo 按 DRV_NAME（= 加载成功的 zip 名）反查 ini，命中哪个段名都要有。
     * 没有金手指的游戏 404/失败属正常，静默跳过。
     *
     * 两级来源：站点 1990i 优先（中文命名、和 ROM 同源），站点没有就查
     * GitHub 官方库 finalburnneo/FBNeo-cheats（master/cheats/<名>.ini，约 3400 款，
     * 与站点完全同格式）；GitHub 各镜像**并发竞速**，谁快用谁。
     */
    private suspend fun ensureCheatIni(sysDir: File, segments: List<String>) {
        if (segments.isEmpty()) return
        val cheatDir = File(sysDir, "fbneo/cheats").apply { mkdirs() }
        // 已就位就不再动（避免重复下载）
        if (segments.all { File(cheatDir, "${it.substringBeforeLast('.')}.ini").exists() }) return
        val encode = { stem: String ->
            java.net.URLEncoder.encode(stem, "UTF-8").replace("+", "%20")
        }
        var content = segments.firstNotNullOfOrNull { name ->
            val stem = name.substringBeforeLast('.')
            val url = "$ROM_HOST/cheat/${encode(stem)}.ini"
            try {
                HttpFetcher.fetchHtml(url, allowEmpty = false)
            } catch (e: Exception) {
                null
            }
        }
        // 站点没有（或下回来的是错误页）→ GitHub 官方库：
        // 所有 (段名 × 镜像) 组合并发竞速，第一个返回真 ini 的胜出
        if (content.isNullOrBlank() || !content.trimStart().startsWith("cheat")) {
            val urls = segments.flatMap { name ->
                val raw = "$GH_CHEAT_RAW/${encode(name.substringBeforeLast('.'))}.ini"
                ghMirrors().map { it + raw }
            }
            content = raceFetchText(urls) { it.trimStart().startsWith("cheat") }
        }
        content ?: return
        for (name in segments) {
            val stem = name.substringBeforeLast('.')
            try {
                File(cheatDir, "$stem.ini").writeText(content)
            } catch (e: Exception) {
                Log.w(TAG, "cheat ini write failed $stem", e)
            }
        }
        Log.i(TAG, "cheat ini ready: ${segments.size} copy(ies), ${content.length} bytes")
    }

    /**
     * 下到的文件到底是不是个 HTML/挑战页：站点 cookie 失效时会把 ROM 请求
     * 重定向到去往挑战页或直接回一段 <html>，长度又远小于真 ROM，特征可判。
     */
    private fun looksLikeChallenge(f: File): Boolean {
        if (f.length() >= 64_000) return false
        val head = f.inputStream().use { ins ->
            val buf = ByteArray(160)
            val n = ins.read(buf)
            String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
        }
        val t = head.trimStart()
        return t.startsWith("<") &&
                (t.contains("<html", true) || t.contains("toNumbers") || t.contains("<!doctype", true))
    }

    /** PK 头则解 zip（取最大文件——站点 zip 里就一个 rom），否则原样使用。
     *  站点 zip 文件名多为 GBK 编码，java.util.zip 会 MALFORMED，
     *  改用 commons-compress 按 GBK 解码（带 UTF-8 标志的条目自动走 UTF-8）。 */
    private fun unwrapZip(dir: File, raw: File): File? {
        val head = ByteArray(4)
        FileInputStream(raw).use { if (it.read(head) < 4) return null }
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                (head[2] == 3.toByte() || head[2] == 5.toByte() || head[2] == 8.toByte())
        if (!isZip) return if (raw.length() > 16) raw else null

        var best: File? = null
        var bestSize = -1L
        try {
            ZipArchiveInputStream(FileInputStream(raw), "GBK", true, false).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory || e.name.startsWith("__MACOSX") ||
                        e.name.endsWith("/")) continue
                    val name = e.name.substringAfterLast('/')
                    if (name.isBlank() || name.startsWith(".")) continue
                    val out = File(dir, "unzipped_$name")
                    out.outputStream().use { os -> zin.copyTo(os, 64 * 1024) }
                    if (out.length() > bestSize) {
                        best?.delete()
                        best = out
                        bestSize = out.length()
                    }
                }
            }
        } catch (e: Throwable) {   // 同上：R8/低版本类装载问题多为 Error，别让协程裸崩
            Log.w(TAG, "unzip failed", e)
            return null
        }
        raw.delete()
        return best?.takeIf { it.length() > 16 }
    }

    /** 7z 魔数：'7' 'z' BC AF 27 1C */
    private fun is7zFile(f: File): Boolean {
        val h = ByteArray(6)
        FileInputStream(f).use { if (it.read(h) < 6) return false }
        return h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte() &&
            h[2] == 0xBC.toByte() && h[3] == 0xAF.toByte() &&
            h[4] == 0x27.toByte() && h[5] == 0x1C.toByte()
    }

    /**
     * 7z 解包（站点 SFC 分发格式）：逐个取文件、留最大者。
     * commons-compress 自带 LZMA2 解码器，纯 LZMA 封装由 xz 兜底；
     * 加密或稀有编码器会抛异常，按加载失败处理。
     */
    private fun unwrap7z(dir: File, raw: File): File? {
        var best: File? = null
        var bestSize = -1L
        try {
            // 纯 Java 内存通道（SeekableByteChannel 需 API 24+，SFC 入口在更老的
            // 系统上会失败降级，不影响其余平台）；站点 7z 仅 1~3MB，全量进内存可接受
            val ch = org.apache.commons.compress.utils.SeekableInMemoryByteChannel(raw.readBytes())
            SevenZFile(ch).use { zin ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/').substringAfterLast('\\')
                    if (name.isBlank() || name.startsWith(".")) continue
                    val out = File(dir, "unzipped_$name")
                    out.outputStream().use { os ->
                        while (true) {
                            val n = zin.read(buf, 0, buf.size)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                        }
                    }
                    if (out.length() > bestSize) {
                        best?.delete()
                        best = out
                        bestSize = out.length()
                    }
                }
            }
        } catch (e: Throwable) {   // API<24 上 SeekableByteChannel 抛的是 VerifyError（Error 系）
            Log.w(TAG, "un7z failed", e)
            best?.delete()
            return null
        }
        raw.delete()
        return best?.takeIf { it.length() > 16 }
    }
}
