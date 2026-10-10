package com.xbw.tv.data.plugin

import com.xbw.tv.core.CoreRouter
import com.xbw.tv.data.model.GameCategory
import org.json.JSONArray
import org.json.JSONObject

/** gamelist.xml 的极简读取器。
 *
 * 支持**两种清单方式**：
 *
 * **1. XML 方式（默认）**：[listUrl] 直接指向 retroFE/Emby 风格的 gamelist.xml，
 *    一份 XML 带标题、封面、ROM 路径。条目里的 `path`（ROM）、`image`（封面）、
 *    `video`（视频）三个字段都支持**绝对 http(s) 直链**，也支持相对路径
 *    （相对 `coverBase`/`romBase` 或清单所在目录）。
 *
 * **2. 目录引导方式（[dirBootstrap]）**：[listUrl] 指向一个**目录索引页**。
 *    站点把文件按目录组织（Apache 风格的 HTML 文件列表），文件名对不上
 *    UTF-8/GBK 的时候（实测 186317 的 `09-FBA单机游戏` 目录名落盘字节是错的，
 *    打出来跟 GBK 编码对不上、直连 404），**服务器发回来的 href 自带真实字节**，
 *    所以目录模式抓取 listing → 解析出逐行 href → 按 [dirMatch] 找到目标子目录
 *    → 用它目录里的 gamelist.xml 出道名+封面，用它的真实字节路径做下载。这样
 *    永远不用去手敲/猜目录字节。
 *
 * 下载一律走**目录直链**（base + 相对路径 / 或 xml 里的绝对直链），不再有
 * down.php 网关 —— 站点已把 `/game/down.php` 改成目录 `/game/`。
 *
 * @param id         稳定 id，由 listUrl 派生哈希（同一条 URL 只能有一个源）
 * @param title      大厅分类芯片上显示的名字，由用户自定（例："我的FC"）
 * @param platform   运行核心的归属平台（GameCategory.key）。XML 本身不声明平台，
 *                   所以由用户指定走哪个原生核心（编辑页「运行核心」从 5 个可玩
 *                   平台里选，进游戏只认它，绝不按文件扩展名猜）
 * @param listUrl    XML 方式 = gamelist.xml 地址；目录方式 = 目录索引页地址
 * @param coverBase  封面 base，可空。`<image>` 的相对路径拼在它后面；
 *                   留空 = 按清单所在目录自动解析（大多数源都这么放）
 * @param romBase    ROM base，可空。留空 = 按清单目录自动解析；空则条目只读不玩
 * @param builtin    随包内置的默认源（见 [builtinSources]：按分类自动编号的
 *                   街机1/街机2…、FC1/FC2…）。内置源定义在代码里、不持久化，
 *                   但用户可在插件页**删除**（记入隐藏集合）或**编辑**（隐藏原内置、
 *                   改动另存为用户副本）。用户自建源的 builtin 恒 false
 * @param gbkUris    磁盘文件名是 GBK 保留字节的站（186317 这种），朴素的 UTF-8
 *                   百分号编码直链会 404，需要再补一条按 GBK 编码路径段的候选地址。
 * @param dirBootstrap 是否目录引导方式（见类注释的"方式 2"）
 * @param dirMatch   目录方式下匹配目标子目录的名字片段（如 "FBA"），不区分大小写；
 *                   匹配第一个就行，目录的真实字节由服务器 href 给出来
 * @param filterByPlatform 是否按条目扩展名派生平台过滤（只给"一份文件跨全部平台"
 *                   的合并清单用，如 186317 的 game/gamelist.xml 街机视口）；
 *                   独立按自家 xml 解析的源不开，否则 FC 里用 zip/7z 打包的 ROM
 *                   会被误丢。留空/关掉的自建源不启用此过滤。
 * @param phpDir 站点是 **PHP 目录浏览页**（186317 的 22web/xo.je 这类免费主机）：
 *                   文件不能靠拼直链下载（必须经 index.php 的"下载"按钮
 *                   `?path=<目录>&download=<文件名>`），清单本身也要从目录页上的
 *                   gamelist.xml 下载按钮拿。由清单加载时**自动识别并写回**，
 *                   用户不用勾选。
 */
data class PluginSource(
    val id: String,
    val title: String,
    val platform: String,
    val listUrl: String,
    val coverBase: String = "",
    val romBase: String = "",
    val builtin: Boolean = false,
    val gbkUris: Boolean = false,
    val dirBootstrap: Boolean = false,
    val dirMatch: String = "",
    val filterByPlatform: Boolean = false,
    val phpDir: Boolean = false
) {
    /** 归属的可玩平台分类；填了没核心的平台（java/nds…）就退化成纯元数据 */
    val category: GameCategory get() = GameCategory.fromKey(platform)

    val playable: Boolean get() = category.playable

    /** 用户选定的运行核心名（fc→fceumm、arcade→fbneo…），进游戏只认它 */
    val coreName: String? get() = CoreRouter.coreForPlatform(platform)

    /** 条目 id 前缀，避免和官方站 id 撞车（官方是纯数字） */
    val idPrefix: String get() = "plug-$id-"

    /** gamelist.xml / 目录索引所在目录 —— cover/rom/video 相对路径的自动解析基准。
     *  XML 源取清单所在目录；目录引导源 listUrl 本身就是目录，不能再去父级。 */
    val baseDir: String get() {
        var u = listUrl.trim().trimEnd('/')
        if (dirBootstrap) return u
        val slash = u.lastIndexOf('/')
        if (slash <= "https:/".length) return u
        return u.substring(0, slash)
    }

    /** scheme://authority */
    val origin: String get() = runCatching {
        val u = java.net.URI(listUrl)
        "${u.scheme}://${u.authority}"
    }.getOrElse { "" }

    /**
     * 真正拿去发 HTTP 请求的清单地址。GBK 落盘的站路径里的中文段按 GBK 百分号
     * 编码（OkHttp 会把裸中文自动按 UTF-8 编码上送，和磁盘名对不上就 404）。
     * 非 GBK 站原样返回。ASCII 路径段（game/nes/…）按原样保留 —— 服务器目录
     * 处理器只认明文路径，把 "game" 编码成 `%67%61%6D%65` 会拿到空响应。
     */
    val effectiveListUrl: String get() {
        if (!gbkUris) return listUrl
        return runCatching {
            val u = java.net.URI(listUrl)
            val path = u.rawPath.split('/').joinToString("/") { gbkEncodeSegment(it) }
            "${u.scheme}://${u.authority}$path"
        }.getOrElse { listUrl }
    }

    /** 清单地址里若含 GitHub raw 的规范 URL（无论是否被某个镜像前缀包裹，
     *  如 `https://wget.la/https://raw.githubusercontent.com/...`），取出那段裸
     *  raw 地址；[PluginRepository] 据此按 CdnPicker 启动实测的镜像顺位竞速抓取。
     *  非 GitHub raw 源返回 null（直链源如 cnliux.dpdns.org 不吃竞速）。 */
    val githubRawUrl: String? get() {
        val marker = "https://raw.githubusercontent.com/"
        val i = listUrl.indexOf(marker)
        return if (i < 0) null else listUrl.substring(i)
    }

    private val HEX = "0123456789ABCDEF"

    /** 路径段按 GBK 编码：ASCII 原样保留，非 ASCII 逐字节 %XX（GBK）；已有的 %XX 不二次编码 */
    private fun gbkEncodeSegment(seg: String): String {
        if (seg.all { it.code < 0x80 }) return seg
        val gbk = java.nio.charset.Charset.forName("GBK")
        val sb = StringBuilder()
        var i = 0
        while (i < seg.length) {
            val c = seg[i]
            if (c.code < 0x80) {
                if (c == '%' && i + 2 < seg.length &&
                    isHex(seg[i + 1]) && isHex(seg[i + 2])
                ) {
                    // 已编码字节（%XX）：照抄，不二次编码
                    sb.append(seg, i, i + 3); i += 3
                    continue
                }
                sb.append(c); i++
            } else {
                var j = i
                while (j < seg.length && seg[j].code >= 0x80) j++
                for (b in seg.substring(i, j).toByteArray(gbk)) {
                    sb.append('%').append(HEX[(b.toInt() ushr 4) and 0xF])
                        .append(HEX[b.toInt() and 0xF])
                }
                i = j
            }
        }
        return sb.toString()
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("platform", platform)
        put("listUrl", listUrl)
        put("coverBase", coverBase)
        put("romBase", romBase)
        put("builtin", builtin)
        put("gbkUris", gbkUris)
        put("dirBootstrap", dirBootstrap)
        put("dirMatch", dirMatch)
        put("filterByPlatform", filterByPlatform)
        put("phpDir", phpDir)
    }

    companion object {
        /** 内置 FC 清单：cnliux/xbw 仓库的 nes/gamelist.xml。写 wget.la 只是给一个
         *  规范/兜底形态（也保住 id 与缓存键稳定）；实际抓取时 [PluginRepository]
         *  会认 [githubRawUrl] 并按 CdnPicker 启动实测的镜像顺位**竞速**，谁快用谁。 */
        const val BUILTIN_FC_URL =
            "https://wget.la/https://raw.githubusercontent.com/cnliux/xbw/master/nes/gamelist.xml"

        /** 随包内置的默认源（大厅里的固定芯片）。内置源不再持久化进用户配置，
         *  改由 [PluginRepository] 用隐藏集合记录用户"删除"、编辑时另存为用户副本。
         *
         *  命名按分类自动编号（街机1/街机2/…、FC1/FC2/…），不写死具体名：
         *  186317 两站是 PHP 目录浏览页（phpDir=true）：文件必须走"下载按钮"
         *  `?path=&download=`，清单也从目录页的下载按钮 href 拿；FC 清单在
         *  `/game/nes` 子目录，所以 listUrl 带 `?path=nes`（phpPathParam 据此
         *  拼 ROM 按钮地址）。gbkUris 不开 —— 清单路径全 ASCII，GBK 编码在
         *  按钮 URL 构造时按条目相对路径做。 */
        fun builtinSources(): List<PluginSource> {
            val raw = listOf(
                PluginSource(
                    id = deriveId("https://186317.22web.org/game/"),
                    title = "",
                    platform = GameCategory.ARCADE.key,
                    listUrl = "https://186317.22web.org/game/",
                    builtin = true,
                    phpDir = true
                ),
                PluginSource(
                    id = deriveId("http://186317.xo.je/"),
                    title = "",
                    platform = GameCategory.ARCADE.key,
                    listUrl = "http://186317.xo.je/",
                    builtin = true,
                    phpDir = true
                ),
                PluginSource(
                    id = deriveId(BUILTIN_FC_URL),
                    title = "",
                    platform = GameCategory.FC.key,
                    listUrl = BUILTIN_FC_URL,
                    builtin = true
                ),
                PluginSource(
                    id = deriveId("https://186317.22web.org/game/?path=nes"),
                    title = "",
                    platform = GameCategory.FC.key,
                    listUrl = "https://186317.22web.org/game/?path=nes",
                    builtin = true,
                    phpDir = true
                )
            )
            // 按分类顺序编号：街机1、街机2…；FC1、FC2…
            val counters = HashMap<String, Int>()
            return raw.map { s ->
                val n = (counters[s.platform] ?: 0) + 1
                counters[s.platform] = n
                s.copy(title = platformPrefix(s.platform) + n)
            }
        }

        /** 分类名 → 芯片名前缀（FC / 红白机 → FC、街机 → 街机、SFC → SFC…） */
        private fun platformPrefix(key: String): String =
            GameCategory.fromKey(key).title.substringBefore(" /").trim()

        fun fromJson(o: JSONObject): PluginSource? {
            val url = o.optString("listUrl").trim()
            if (url.isEmpty()) return null
            return PluginSource(
                id = o.optString("id").ifEmpty { deriveId(url) },
                title = o.optString("title").ifEmpty { "第三方源" },
                platform = o.optString("platform").ifEmpty { GameCategory.FC.key },
                listUrl = url,
                coverBase = o.optString("coverBase"),
                romBase = o.optString("romBase"),
                builtin = o.optBoolean("builtin"),
                gbkUris = o.optBoolean("gbkUris"),
                dirBootstrap = o.optBoolean("dirBootstrap"),
                dirMatch = o.optString("dirMatch"),
                filterByPlatform = o.optBoolean("filterByPlatform"),
                phpDir = o.optBoolean("phpDir")
            )
        }

        /** 同一条 URL 只能有一个源，重复添加直接顶掉旧的 */
        fun deriveId(url: String): String {
            val md = java.security.MessageDigest.getInstance("MD5")
            val hex = md.digest(url.trim().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return hex.substring(0, 10)
        }

        fun parseList(text: String): List<PluginSource> = runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())

        private val HEX = "0123456789ABCDEF"

        fun toJsonList(list: List<PluginSource>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}