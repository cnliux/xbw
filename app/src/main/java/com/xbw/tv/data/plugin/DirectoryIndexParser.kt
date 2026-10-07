package com.xbw.tv.data.plugin

import java.net.URI

/**
 * 目录索引页解析器（"网站目录"方式的内容入口）。
 *
 * 这类"免费网盘"站点给目录开放了网页文件列表（Apache/nginx 的 autoindex，或各家
 * 文件管理器的同款 HTML）：每一个目录/文件一行 `<a href="...">名字</a>`。我们把
 * 行解析成 [Entry]，href 里带着服务器文件系统的**真实字节**（百分号编码），
 * 中文/落盘字节对不上的情况就靠它兜住——不再需要手敲目录名。
 *
 * 兼容两种常见版式：
 *  - Apache 的 `<pre>` / `<table>` 版（href 多带 `?C=N&O=D` 排序参数，要去掉）
 *  - cPanel/DA 文件管理器的超链接版（href 常带 `?next=...` 之类，同样只取文件路径）
 *
 * 判别"目录"：href 以 `/` 结尾，或锚文本以 `/` 结尾。父目录 `../` 与排序链接直接跳过。
 */
object DirectoryIndexParser {

    data class Entry(
        /** 锚文本（显示名，可能带乱码，别拿来当键） */
        val name: String,
        /** 绝对 URL（href 相对当前页解析，去掉查询串，真实字节保留） */
        val url: String,
        val isDir: Boolean
    )

    private val HREF_LINK = Regex("<a[^>]+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
    private const val SORT_HINT = "?C="
    /** 文件管理器版式把真实路径藏在 ?next=/…（或 ?path= / ?dir=）里 */
    private val NEXT_PATH = Regex("(?i)[?&](?:next|path|dir)=([^&#]+)")

    fun parse(html: String, pageUrl: String): List<Entry> {
        val base = URI(pageUrl)
        val out = LinkedHashMap<String, Entry>()
        for (m in HREF_LINK.findAll(html)) {
            var href = m.groupValues[1].trim()
            val text = htmlEntities(m.groupValues[2]).trim()
            if (href.isEmpty()) continue
            // 排序链接 / 父目录 / 无意义链接
            if (href.startsWith("#") || href == "../.." || href == "../" || href == "..") continue
            if (href.contains(SORT_HINT) && text.isBlank()) continue
            val isDir = href.endsWith("/") || text.endsWith("/")
            // 只保留文件路径部分：autoindex 的 ?C=N&O=D、文件管理器的 ?next=/… 都要还原成路径用它
            var clean = href.substringBefore('?')
            if (clean.isEmpty()) clean = NEXT_PATH.find(href)?.groupValues?.get(1) ?: continue
            if (clean.startsWith("javascript")) continue
            // 相对 → 绝对
            val url = try { base.resolve(clean).toString() } catch (e: Exception) { continue }
            // 文件中一个名字只留一行（有的版式 Table + Pre 双重复）
            val key = url + if (isDir) "/" else ""
            out[key] = Entry(
                name = text.ifEmpty { clean.substringAfterLast('/').trimEnd('/') }.trimEnd('/'),
                url = url,
                isDir = isDir
            )
        }
        return out.values.toList()
    }

    private fun htmlEntities(s: String): String {
        var t = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        // 数字实体（&#NN; / &#xNN;），顺带处理
        t = Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { it.toChar().toString() } ?: "" }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { it.toChar().toString() } ?: "" }
        return t
    }
}