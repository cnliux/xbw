package com.xbw.tv.data.net

import java.io.File
import java.util.zip.ZipInputStream

/**
 * ROM 获取器：游戏详情页内联变量 → file.1990i.com 直链下载 → 本地缓存。
 *
 * 站点机制（实测，详见 docs/YIKM_SITE_STRUCTURE.md）：
 *  - play 页内联脚本定义 var gameType="fc"、var gromname="/fcrom/dzmx/Super Mario Bros. (W) [!].nes"；
 *  - ROM 本体托管在 https://file.1990i.com + gromname（与封面 img.1990i.com 不同主机）；
 *  - 部分游戏是 zip 包（PK 头），需要解包取出里面的 .nes。
 *
 * 缓存布局：<cacheDir>/roms/<gameId>_<原始文件名>；zip 游戏同时留 zip 与解包产物，
 * 查缓存时跳过 .zip 只认解包后的 ROM。存档（即时档/SRAM）放 filesDir，不受缓存清理影响。
 *
 * 纯解析函数（parsePlayPage/romUrl/isNesRom）不依赖 Android，可 JVM 单测。
 */
object RomFetcher {

    /** ROM 直链 CDN */
    const val ROM_CDN = "https://file.1990i.com"

    class RomException(message: String, cause0: Throwable? = null) :
        RuntimeException(message, cause0)

    /** play 页内联脚本里的游戏元信息 */
    data class PlayMeta(
        val gameType: String?,
        val romName: String?,
        val gameName: String?,
        val gameId: String?
    ) {
        val hasRom: Boolean get() = !romName.isNullOrBlank()

        /** 是否为当前原生内核支持的 FC/NES 平台 */
        val isNativeFc: Boolean
            get() = gameType != null && gameType.lowercase() in NATIVE_TYPES
    }

    /** 已就位的本地 ROM */
    data class RomSource(
        val gameId: String,
        val gameType: String?,
        val file: File,
        val fromCache: Boolean
    )

    /** 原生内核支持的游戏类型（站点 gameType 取值，实测小写） */
    val NATIVE_TYPES = setOf("fc", "nes")

    // ------------------------------------------------------------------
    // play 页解析（纯 JVM，可单测）
    // ------------------------------------------------------------------

    /**
     * 从 play 页 HTML 提取内联 var：gameType/gromname/gname/gameid。
     * 实测站点写法是 `var hascheat='1',gameType="fc"; var gromname="…",gameid="3882",gname="…"`，
     * 目标变量可能跟在逗号后而非 var 后，所以只要求变量名前面不是单词字符
     * （排除 xyzgameType= 之类），引号兼容单双引号。
     */
    fun parsePlayPage(html: String): PlayMeta {
        fun jsVar(name: String): String? {
            val m = Regex("""(?<!\w)$name\s*=\s*["']([^"']*)["']""")
                .find(html) ?: return null
            return m.groupValues[1].takeIf { it.isNotBlank() }
        }
        return PlayMeta(
            gameType = jsVar("gameType"),
            romName = jsVar("gromname"),
            gameName = jsVar("gname"),
            gameId = jsVar("gameid")
        )
    }

    /** 仅抓 play 页拿元信息（决定原生/网页路线用，不下载 ROM） */
    suspend fun fetchMeta(gameId: String): PlayMeta =
        parsePlayPage(HttpFetcher.fetchHtml(SiteConfig.playUrl(gameId)))

    /**
     * gromname → 直链 URL。路径逐段百分号编码（文件名含空格/括号/[]/中文），
     * 已含 '%' 的段视为已编码不再处理，避免双重编码。
     */
    fun romUrl(romName: String): String {
        val path = romName.trim().trimStart('/')
        val encoded = path.split('/').joinToString("/") { seg ->
            if (seg.isEmpty() || seg.contains('%')) seg else SiteConfig.encodeParam(seg)
        }
        return "$ROM_CDN/$encoded"
    }

    /** 缓存目录：<cacheDir>/roms */
    fun romDir(cacheDir: File): File = File(cacheDir, "roms").apply { mkdirs() }

    /** 查缓存：跳过 .zip（那是压缩包，解包产物才是 ROM） */
    fun peekCache(gameId: String, cacheDir: File): File? =
        romDir(cacheDir).listFiles { f ->
            f.isFile && f.name.startsWith("${gameId}_") && !f.name.endsWith(".zip")
        }?.maxByOrNull { it.lastModified() }

    /** iNES 魔数（NES\x1A）校验 */
    fun isNesRom(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        file.inputStream().use { ins ->
            val b = ByteArray(4)
            var off = 0
            while (off < 4) {
                val n = ins.read(b, off, 4 - off)
                if (n < 0) return false
                off += n
            }
            return b[0] == 'N'.code.toByte() && b[1] == 'E'.code.toByte() &&
                    b[2] == 'S'.code.toByte() && b[3] == 0x1A.toByte()
        }
    }

    // ------------------------------------------------------------------
    // 主入口：确保 ROM 在本地
    // ------------------------------------------------------------------

    /**
     * 确保 gameId 对应的 ROM 已在本地并返回 [RomSource]。
     * 命中缓存不联网；未命中则抓 play 页 → 下载 →（zip 时）解包。
     * 传 [meta] 可避免重复抓 play 页。
     */
    suspend fun resolve(gameId: String, cacheDir: File, meta: PlayMeta? = null): RomSource {
        if (gameId.isBlank()) throw RomException("缺少游戏 id")
        val dir = romDir(cacheDir)

        peekCache(gameId, cacheDir)?.let {
            return RomSource(gameId, meta?.gameType, it, fromCache = true)
        }

        val m = meta ?: fetchMeta(gameId)
        if (!m.hasRom) {
            throw RomException("play 页未找到 gromname（游戏可能不含可下载 ROM，或站点已改版）")
        }

        val url = romUrl(m.romName!!)
        val rawName = m.romName.trim().trimStart('/').substringAfterLast('/').ifBlank { "rom" }
        val part = File(dir, "${gameId}_$rawName.part")
        HttpFetcher.downloadToFile(url, part, referer = SiteConfig.BASE_URL + "/")
        val raw = File(dir, "${gameId}_$rawName")
        if (!part.renameTo(raw)) {
            part.copyTo(raw, overwrite = true)
            part.delete()
        }

        val rom = if (isZip(raw)) extractRomFromZip(raw, dir, gameId) else raw
        return RomSource(gameId, m.gameType, rom, fromCache = false)
    }

    /** PK 魔数判断 zip 包 */
    private fun isZip(f: File): Boolean {
        if (f.length() < 4) return false
        f.inputStream().use { ins ->
            val b = ByteArray(4)
            var off = 0
            while (off < 4) {
                val n = ins.read(b, off, 4 - off)
                if (n < 0) return false
                off += n
            }
            return b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        }
    }

    /**
     * 解包 zip：优先取 .nes/.fds 条目，没有则取第一个文件（兜底）。
     * FC ROM ≤1MB，整条目读入内存没有压力。
     */
    private fun extractRomFromZip(zip: File, dir: File, gameId: String): File {
        var fallback: Pair<String, ByteArray>? = null
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/').ifBlank { "rom" }
                        val bytes = zis.readBytes()
                        if (name.endsWith(".nes", true) || name.endsWith(".fds", true)) {
                            return saveExtracted(dir, gameId, name, bytes)
                        }
                        if (fallback == null) fallback = name to bytes
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            throw RomException("zip 解包失败：${e.message}", e)
        }
        fallback?.let { (name, bytes) -> return saveExtracted(dir, gameId, name, bytes) }
        throw RomException("zip 包内没有可用的 ROM 文件")
    }

    private fun saveExtracted(dir: File, gameId: String, name: String, bytes: ByteArray): File {
        val f = File(dir, "${gameId}_$name")
        f.writeBytes(bytes)
        return f
    }
}
