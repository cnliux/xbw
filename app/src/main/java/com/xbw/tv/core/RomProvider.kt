package com.xbw.tv.core

import android.content.Context
import android.util.Log
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.SiteConfig
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

    data class RomSpec(
        val coreName: String,      // "fceumm"/"fbneo" … 对应 lib<coreName>.so
        val romFile: File,
        val systemDir: File,
        val fromCache: Boolean,
        val fallbacks: List<File> = emptyList(),   // 主 ROM 加载失败时的兜底 zip
    )

    /**
     * play 页的 gameType → libretro 核心名。
     * gameType 是站点自己的稳定字段（FC="fc"，街机="arcade"），比猜 gromname 路径可靠。
     */
    private fun coreFor(gameType: String): String? = when (gameType) {
        "fc" -> "fceumm"
        "arcade" -> "fbneo"
        else -> null
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
     * @return 核心加载所需的全部信息；不支持/失败返回 null
     */
    suspend fun prepare(context: Context, gameId: String): RomSpec? {
        val dir = cacheDirOf(context, gameId)
        evictIfNeeded(context, gameId)
        // 缓存命中：.romspec 记录核心名 + ROM 文件位置 + 兜底列表
        val meta = File(dir, ".romspec")
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
                    return RomSpec(lines[0], f, File(dir, "system"), true, fallbacks)
                }
            }
            // 旧格式（<3 行）或文件丢失：作废重取
            meta.delete()
        }

        // 1) play 页 → gameType / gromname / gsystem
        val playUrl = "${SiteConfig.BASE_URL}/play?id=$gameId"
        val html = try {
            HttpFetcher.fetchHtml(playUrl)
        } catch (e: Exception) {
            Log.w(TAG, "play page fetch failed id=$gameId", e)
            return null
        }
        val gameType = Regex("""gameType="([^"]*)"""").find(html)?.groupValues?.get(1)
        val grom = Regex("""gromname="([^"]+)"""").find(html)?.groupValues?.get(1)
        if (grom.isNullOrBlank()) {
            Log.w(TAG, "gromname not found id=$gameId")
            return null
        }
        // 路由：街机认 gameType=arcade（整 zip 喂 FBNeo）；其余系统 play 页没有
        // gameType，用 gromname 固定目录前缀区分（实测 2026-10）：
        //   /fcrom/…nes → fceumm 裸文件；/gbarom/xxx.zip → mgba；
        //   /mdrom/mdN.zip → genesis_plus_gx；/sfc/….7z → snes9x
        val gsystem = Regex("""gsystem="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
        val core = when {
            gameType == "arcade" -> "fbneo"
            grom.startsWith("/fcrom") -> "fceumm"
            grom.startsWith("/gbarom") -> "mgba"
            grom.startsWith("/mdrom") -> "genesis_plus_gx"
            gameType == "sfc" || grom.startsWith("/sfc") ||
                grom.substringBeforeLast('$').trim().endsWith(".7z") -> "snes9x"
            else -> coreFor(gameType.orEmpty())
        } ?: run {
            Log.i(TAG, "gameType=$gameType grom=$grom not native-supported yet")
            return null
        }
        // FBNeo 靠 zip 内的 rom 名反查机型表，所以必须整套一起喂，不能拆散。
        // FCEUmm/mgba/gx/snes9x 相反，要的是 zip/7z 里那一个 rom 文件。
        val zip = core == "fbneo"
        // 街机多版本用 $ 分隔（"修改版.zip$基础版.zip"），全部要下载
        val segments = grom.split('$').map { it.trim() }.filter { it.isNotBlank() }
        val romPath = when {
            !zip -> grom
            segments.isEmpty() -> return null
            else -> {
                if (gsystem.isBlank()) {
                    Log.w(TAG, "gsystem missing for arcade id=$gameId")
                    return null
                }
                "roms/fbneo/$gsystem/${segments.first()}"
            }
        }
        if (romPath.isBlank()) {
            Log.w(TAG, "empty rom path id=$gameId grom=$grom")
            return null
        }

        // 2) 直链下载（路径逐段转义，空格/中括号原样在 URL 里会被 nginx 拒）
        val encoded = romPath.trimStart('/').split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        val url = "$ROM_HOST/$encoded"
        // FBNeo 直接吃这个 zip（整套 rom 一个文件），所以按原名落盘方便排查
        val raw = if (zip) File(dir, romPath.substringAfterLast('/')) else File(dir, "rom.raw")
        try {
            HttpFetcher.downloadToFile(url, raw, referer = SiteConfig.BASE_URL + "/")
        } catch (e: Exception) {
            Log.w(TAG, "rom download failed $url", e)
            raw.delete()
            return null
        }

        // 3) 压缩壳：FBNeo 整套留用；其余拆出单个 rom（zip 或 7z）
        val rom = when {
            zip -> raw
            is7zFile(raw) -> unwrap7z(dir, raw) ?: run { raw.delete(); return null }
            else -> unwrapZip(dir, raw) ?: run { raw.delete(); return null }
        }
        if (rom.length() <= 16) {
            rom.delete()
            return null
        }

        // 街机专属：兜底 zip + BIOS + 金手指 ini
        val fallbacks = if (zip) downloadFallbacks(dir, gsystem, segments.drop(1)) else emptyList()
        val sysDir = File(dir, "system").apply { mkdirs() }
        if (zip) {
            ensureBios(context, gsystem, sysDir)
            ensureCheatIni(sysDir, segments)
        }

        // 4) 写规格缓存
        meta.writeText(listOf(core, rom.absolutePath, gsystem)
            .plus(fallbacks.map { it.absolutePath }).joinToString("\n"))
        Log.i(TAG, "rom ready id=$gameId core=$core size=${rom.length()} fallbacks=${fallbacks.size}")
        return RomSpec(core, rom, sysDir, false, fallbacks)
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
     * 保证机种 BIOS 就位：全局缓存 <filesDir>/bios/<name>（多游戏共享一次下载），
     * 再复制到本游戏的 systemDir 根（FBNeo 在 system 目录下找 neogeo.zip/pgm.zip）。
     */
    private suspend fun ensureBios(context: Context, gsystem: String, sysDir: File) {
        val bios = biosFor(gsystem) ?: return
        val cache = File(context.filesDir, "bios/$bios")
        if (!cache.exists() || cache.length() <= 16) {
            try {
                HttpFetcher.downloadToFile("$CDN_HOST/$bios", cache, referer = SiteConfig.BASE_URL + "/")
            } catch (e: Exception) {
                Log.w(TAG, "bios download failed $bios", e)
                cache.delete()
                return
            }
        }
        val dest = File(sysDir, bios)
        if (dest.exists() && dest.length() == cache.length()) return
        try {
            cache.copyTo(dest, overwrite = true)
            Log.i(TAG, "bios ready: ${dest.name} (${dest.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "bios copy failed $bios", e)
        }
    }

    /**
     * 下载金手指 ini：任一段名命中即用，内容写到每个段名一份——
     * FBNeo 按 DRV_NAME（= 加载成功的 zip 名）反查 ini，命中哪个段名都要有。
     * 没有金手指的游戏 404/失败属正常，静默跳过。
     */
    private suspend fun ensureCheatIni(sysDir: File, segments: List<String>) {
        if (segments.isEmpty()) return
        val cheatDir = File(sysDir, "fbneo/cheats").apply { mkdirs() }
        // 已就位就不再动（避免重复下载）
        if (segments.all { File(cheatDir, "${it.substringBeforeLast('.')}.ini").exists() }) return
        val content = segments.firstNotNullOfOrNull { name ->
            val stem = name.substringBeforeLast('.')
            val url = "$ROM_HOST/cheat/${java.net.URLEncoder.encode(stem, "UTF-8").replace("+", "%20")}.ini"
            try {
                HttpFetcher.fetchHtml(url, allowEmpty = false)
            } catch (e: Exception) {
                null
            }
        } ?: return
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            Log.w(TAG, "un7z failed", e)
            best?.delete()
            return null
        }
        raw.delete()
        return best?.takeIf { it.length() > 16 }
    }
}
