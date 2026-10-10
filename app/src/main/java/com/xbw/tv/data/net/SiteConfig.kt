package com.xbw.tv.data.net

import java.util.Locale

/**
 * 站点选择器与 URL 集中配置。
 *
 * ⚠️ yikm.net 改版时**只需要改这个文件**，不用动任何业务代码。
 * 所有选择器都用真实抓取的 HTML 验证过（验证脚本见 tools/SelectorCheck.java，
 * 实测记录见 docs/YIKM_SITE_STRUCTURE.md）。
 *
 * 改版自查步骤：
 *   1. 浏览器打开 https://www.yikm.net/nes?page=1&tag=&e= ，F12 复制任意一个游戏卡片的外层 HTML；
 *   2. 对照下方 CARD_SELECTOR 是否仍能命中；
 *   3. 若命中数变 0，App 会抛 ParseException 并在 UI 上给出"站点结构已变更"提示，
 *      同时设置页 → 站点诊断 会打印真实抓取到的 HTML 片段，便于定位。
 */
object SiteConfig {

    const val BASE_URL = "https://www.yikm.net"

    /** 封面图/ROM 实际托管在 CDN */
    const val CDN_URL = "https://img.1990i.com"

    /** 首页（含"热门 XX"区块与底部"所有游戏"） */
    const val HOME_URL = BASE_URL + "/"

    /** 列表页模板：%d = 页码，%s = tag，%s = e */
    const val LIST_URL_TEMPLATE = BASE_URL + "/nes?page=%d&tag=%s&e=%s"

    /**
     * 搜索页：GET /search?name=关键词&page=N
     *
     * ⚠️ 必须显式带 page=1：站点对缺省 page 的搜索请求会返回空列表（实测中文
     * 关键词稳定为空，补 page=1 才有结果），因此不能沿用"无分页"的旧假设。
     */
    const val SEARCH_URL_TEMPLATE = BASE_URL + "/search?name=%s&page=%d"

    /** 游戏页 */
    const val PLAY_URL_TEMPLATE = BASE_URL + "/play?id=%s"

    /**
     * 金手指接口：GET /cheat?id=<游戏id>，返回纯文本、逗号分隔的 `ADDR-TYPE-VAL$名称`。
     * 没有金手指的游戏返回空响应体（不是 404）。
     */
    const val CHEAT_URL_TEMPLATE = BASE_URL + "/cheat?id=%s"

    // ------------------------------------------------------------------
    // 列表 / 首頁解析选择器（已实测命中）
    // ------------------------------------------------------------------

    /** 游戏卡片容器：首页与列表页均为 20/页，实测 100% 命中 */
    const val CARD_SELECTOR = "div.card-blog"

    /** 标题 + 详情链接。用绝对 URL 判重，过滤站外页游推广 */
    const val TITLE_SELECTOR = "h4.card-caption a"

    /** 封面图：优先 data-src（懒加载），退化到 src */
    const val COVER_SELECTOR = "div.card-image img"
    const val COVER_LAZY_ATTR = "data-src"

    /** 卡片标签（射击 / 魂斗罗 / 街机 ...） */
    const val LABEL_SELECTOR = "span.label"

    /** 分页：bootstrap pager，实测选择器 ul.pager a */
    const val PAGER_SELECTOR = "ul.pager"
    const val PAGER_LINK_SELECTOR = "ul.pager a"

    /** 下一页文案（用于解析最大页码；站点没有 data-total 属性） */
    const val NEXT_PAGE_TEXT = "下一页"
    const val PREV_PAGE_TEXT = "上一页"

    /** 首页"热门 XX"区块标题：h2/h3 + 区块内 8 个卡片 */
    const val HOME_SECTION_TITLE_SELECTOR = "div.container h2, div.container h3"
    const val HOME_SECTION_MORE_SELECTOR = "a[href^='/nes?'], a[href='/h5']"

    /**
     * 推广/广告过滤：第三方页游卡片指向站外域名（jg.700sy.com / jg.doghun.com 等），
     * 不是 yikm 游戏，解析时丢弃。
     */
    val BLOCKED_LINK_PATTERNS = listOf(
        "javascript:",
        "/h5",
        "jg.700sy.com",
        "jg.doghun.com",
        "mail.qq.com",
        "mp.weixin.qq.com"
    )

    /** 合法游戏链接必须包含此片段（/play?id=4137） */
    const val PLAY_LINK_MARKER = "/play?id="

    /** 站点图片占位符（解析到时按"无封面"处理，UI 显示默认图） */
    const val NO_PIC_MARK = "nopic.png"

    /**
     * URL 查询参数编码。
     * 这里刻意不用 android.net.Uri.encode，保证 Parser 能在纯 JVM 下跑单元测试。
     * 空格用 %20（而不是 URLEncoder 默认的 +），与站点自身链接保持一致。
     */
    fun encodeParam(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /** 构建列表页 URL；tag/e 需 URL 编码（tag 可以是中文关键词，如 三国战纪） */
    fun listUrl(page: Int, tag: String = "", e: String = ""): String =
        String.format(Locale.US, LIST_URL_TEMPLATE, page, encodeParam(tag), encodeParam(e))

    fun searchUrl(keyword: String, page: Int = 1): String =
        String.format(Locale.US, SEARCH_URL_TEMPLATE, encodeParam(keyword), page)

    fun playUrl(id: String): String = String.format(Locale.US, PLAY_URL_TEMPLATE, id)

    fun cheatUrl(id: String): String = String.format(Locale.US, CHEAT_URL_TEMPLATE, encodeParam(id))

    /** 相对地址 → 绝对地址；封面图在 CDN 域名下 */
    fun absolutize(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> BASE_URL + href
        else -> "$BASE_URL/$href"
    }

    /** 图片地址补全：站内相对路径（如 /fcpic/x.png）走 CDN */
    fun absolutizeImage(src: String): String = when {
        src.startsWith("http://") || src.startsWith("https://") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> CDN_URL + src
        else -> "$CDN_URL/$src"
    }
}
