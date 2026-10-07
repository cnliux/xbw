package com.xbw.tv.data.plugin

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
 * @param title      分类上显示的名字（例："FC第三方"）
 * @param platform   归属平台（GameCategory.key）。第三方只留 FC / 街机两个选项；
 *                   XML 本身不声明平台，所以由用户指定走哪个原生核心
 * @param listUrl    XML 方式 = gamelist.xml 地址；目录方式 = 目录索引页地址
 * @param coverBase  封面 base，可空。`<image>` 的相对路径拼在它后面；
 *                   留空 = 按清单所在目录自动解析（大多数源都这么放）
 * @param romBase    ROM base，可空。留空 = 按清单目录自动解析；空则条目只读不玩
 * @param builtin    兼容老配置保留；2026-10 起不再内置任何源，恒为 false
 * @param gbkUris    磁盘文件名是 GBK 保留字节的站（186317 这种），朴素的 UTF-8
 *                   百分号编码直链会 404，需要再补一条按 GBK 编码路径段的候选地址。
 * @param dirBootstrap 是否目录引导方式（见类注释的"方式 2"）
 * @param dirMatch   目录方式下匹配目标子目录的名字片段（如 "FBA"），不区分大小写；
 *                   匹配第一个就行，目录的真实字节由服务器 href 给出来
 * @param filterByPlatform 是否按条目扩展名派生的平台过滤（只给"一份文件跨全部平台"
 *                   的合并清单用，如 186317 的 game/gamelist.xml 街机视口）；
 *                   独立按自家 xml 解析的源不开，否则 FC 里用 zip/7z 打包的 ROM
 *                   会被误丢。留空/关掉的自建源不启用此过滤。
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
    val filterByPlatform: Boolean = false
) {
    /** 归属的可玩平台分类；填了没核心的平台（java/nds…）就退化成纯元数据 */
    val category: GameCategory get() = GameCategory.fromKey(platform)

    val playable: Boolean get() = category.playable

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
    }

    companion object {
        /** 不再内置任何源（2026-10 决定）：所有第三方源由用户自行添加，平台只有
         *  FC / 街机两个选项，清单统一走 xml 解析、条目里的 path/image/video 都支持
         *  绝对 http(s) 直链。builtin 字段保留兼容老配置，但不再有新代码创建内置源。 */

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
                filterByPlatform = o.optBoolean("filterByPlatform")
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