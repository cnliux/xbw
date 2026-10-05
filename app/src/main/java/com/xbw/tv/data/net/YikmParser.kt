package com.xbw.tv.data.net

import com.xbw.tv.data.model.GameItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * yikm.net HTML → GameItem 解析器。
 *
 * 设计原则：
 *  1. **主选择器 + 兜底选择器**：主选择器改版失效时自动降级尝试备用选择器，
 *     并在 [ParseResult.warnings] 里记录，UI 可提示"站点可能已改版"。
 *  2. **零内置假定**：解析不到就返回空列表并抛结构化异常，绝不用假数据填充。
 *  3. **纯函数**：不依赖任何 Android API，可在 JVM 单元测试里直接喂 HTML 验证。
 */
object YikmParser {

    /** 解析结果：条目 + 元信息（用于诊断和分页） */
    data class ParseResult(
        val items: List<GameItem>,
        val currentPage: Int,
        val maxPage: Int,
        /** 主选择器命中的卡片总数（含被过滤的推广） */
        val rawCardCount: Int,
        val warnings: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    /** 结构化解析异常：让 UI 能区分"改版"与"网络错误" */
    class ParseException(
        val kind: Kind,
        override val message: String,
        val snippet: String = ""
    ) : RuntimeException(message) {
        enum class Kind { NO_CARDS, NO_TITLE, BAD_HTTP, EMPTY_BODY }
    }

    // 备用选择器：按命中率从高到低尝试
    private val FALLBACK_CARD_SELECTORS = listOf(
        SiteConfig.CARD_SELECTOR,
        "div.card-blog",
        "div.card.card-blog",
        "div.col-md-3 div.card",
        "div.col-xs-6 div.card",
        "a[href*='/play?id=']"
    )

    private val FALLBACK_TITLE_SELECTORS = listOf(
        SiteConfig.TITLE_SELECTOR,
        "h4.card-caption a",
        "h4 a[href*='/play?id=']",
        ".card-caption a",
        "a[href*='/play?id=']"
    )

    private val FALLBACK_COVER_SELECTORS = listOf(
        SiteConfig.COVER_SELECTOR,
        "div.card-image img",
        ".card-image img",
        "img"
    )

    /**
     * 解析列表页 / 搜索结果页。
     * @param pageHint 请求的页码。站点分页控件缺失/改版时用它兜底，
     *   否则 currentPage 会一律回落成 1，翻页状态与 UI 显示就会错位。
     */
    fun parseList(html: String, pageHint: Int = 1): ParseResult {
        if (html.isBlank()) {
            throw ParseException(ParseException.Kind.EMPTY_BODY, "返回内容为空")
        }
        val doc = Jsoup.parse(html)
        val warnings = mutableListOf<String>()

        // 站点改版常见信号：出现"服务器错误/404"文案
        val body = doc.body()

        var cards: Elements? = null
        for (sel in FALLBACK_CARD_SELECTORS) {
            val found = doc.select(sel)
            if (found.isNotEmpty()) {
                cards = found
                if (sel != SiteConfig.CARD_SELECTOR) {
                    warnings += "主卡片选择器 '${SiteConfig.CARD_SELECTOR}' 未命中，已降级使用 '$sel'"
                }
                break
            }
        }
        if (cards == null || cards.isEmpty()) {
            throw ParseException(
                ParseException.Kind.NO_CARDS,
                "未解析到任何游戏卡片，站点结构可能已变更",
                body.text().take(400)
            )
        }

        val out = LinkedHashMap<String, GameItem>()
        var noTitle = 0
        for (card in cards) {
            val item = parseCard(card)
            if (item == null) {
                noTitle++
            } else {
                // 同 id 卡片在不同区块重复出现（首页很常见），保留首次出现的来源标记
                out.putIfAbsent(item.id, item)
            }
        }

        if (out.isEmpty() && noTitle > 0) {
            throw ParseException(
                ParseException.Kind.NO_TITLE,
                "命中 ${cards.size} 个卡片但都取不到标题/链接，选择器需要更新",
                cards.firstOrNull()?.outerHtml()?.take(400).orEmpty()
            )
        }

        val (pagerCur, pagerMax) = parsePager(doc)
        // 分页控件缺失或被限流截断时，用请求页码兜底，别把 currentPage 谎报成 1
        val cur = pagerCur.takeIf { it > 0 } ?: pageHint.coerceAtLeast(1)
        val max = maxOf(pagerMax, pageHint.coerceAtLeast(1))
        if (pagerMax < pageHint) {
            warnings += "分页控件未解析出第 $pageHint 页，按请求页码兜底"
        }
        return ParseResult(out.values.toList(), cur, max, cards.size, warnings)
    }

    /** 单卡片 → GameItem；取不到有效链接/标题返回 null */
    private fun parseCard(card: Element): GameItem? {
        // 标题 + 链接
        var titleEl: Element? = null
        for (sel in FALLBACK_TITLE_SELECTORS) {
            val e = card.selectFirst(sel)
            if (e != null && e.attr("href").isNotBlank()) {
                titleEl = e
                break
            }
        }
        // 最外层 a 也可能是链接（部分区块卡片没有 h4）
        if (titleEl == null) {
            titleEl = card.select("a[href*='${SiteConfig.PLAY_LINK_MARKER}']").firstOrNull()
        }
        val href = titleEl?.attr("href").orEmpty()
        if (href.isBlank() || href.contains("javascript:")) return null

        // 过滤站外推广（页游）
        if (SiteConfig.BLOCKED_LINK_PATTERNS.any { href.contains(it, ignoreCase = true) }) return null
        if (!href.contains(SiteConfig.PLAY_LINK_MARKER)) return null

        val name = titleEl!!.text().trim()
        if (name.isEmpty()) return null

        // 封面
        var coverEl: Element? = null
        for (sel in FALLBACK_COVER_SELECTORS) {
            val e = card.selectFirst(sel)
            if (e != null) { coverEl = e; break }
        }
        var cover: String? = null
        if (coverEl != null) {
            val lazy = coverEl.attr(SiteConfig.COVER_LAZY_ATTR)
            val raw = lazy.ifBlank { coverEl.attr("src") }.ifBlank { coverEl.attr("data-original") }
            if (raw.isNotBlank() && !raw.contains(SiteConfig.NO_PIC_MARK)) {
                cover = SiteConfig.absolutizeImage(raw)
            }
        }

        // 标签
        val tags = card.select(SiteConfig.LABEL_SELECTOR)
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val playUrl = SiteConfig.absolutize(href)
        val id = extractId(playUrl) ?: return null

        return GameItem(
            id = id,
            name = name,
            coverUrl = cover,
            playUrl = playUrl,
            tags = tags
        )
    }

    /** /play?id=4137 → "4137" */
    fun extractId(url: String): String? {
        val q = url.indexOf("id=")
        if (q < 0) return null
        val tail = url.substring(q + 3)
        val end = tail.indexOfAny(charArrayOf('&', '#', '?'))
        val v = (if (end >= 0) tail.substring(0, end) else tail).trim()
        return v.ifBlank { null }
    }

    /**
     * 解析分页。站点没有总页数字段，用 pager 里的数字页码取最大值。
     * 结构（实测）：<ul class="pager"><li class="disabled">…1…</li><li><a href="/nes?page=2…">2</a></li>…</ul>
     */
    private fun parsePager(doc: Document): Pair<Int, Int> {
        val pager = doc.selectFirst(SiteConfig.PAGER_SELECTOR) ?: return 1 to 1
        var max = 1
        var cur = 1
        for (a in pager.select(SiteConfig.PAGER_LINK_SELECTOR)) {
            val text = a.text().trim()
            val num = text.toIntOrNull()
            if (num != null) {
                max = maxOf(max, num)
                val li = a.parent()
                if (li != null && li.className().contains("disabled")) cur = maxOf(cur, num)
            }
        }
        return cur to max
    }

    /**
     * 解析首页"热门 XX"区块。首页每个区块是 h2/h3 标题 + 紧跟的 8 张卡片，
     * 底部还有"所有游戏"列表。这里按区块切分，用于大厅的分类预览。
     *
     * 实现方式：遍历容器内节点，遇到 h2/h3 就开一个新分组，之后的卡片归入该分组。
     */
    fun parseHomeSections(html: String): List<HomeSection> {
        val doc = Jsoup.parse(html)
        val sections = mutableListOf<HomeSection>()
        val containers = doc.select("div.container")
        var currentTitle: String? = null
        var buffer = mutableListOf<Element>()

        fun flush() {
            val title = currentTitle ?: return
            val items = buffer.mapNotNull { parseCard(it) }
                .distinctBy { it.id }
            if (items.isNotEmpty()) {
                sections += HomeSection(
                    title = title,
                    moreUrl = findMoreUrl(title, doc),
                    items = items
                )
            }
            buffer = mutableListOf()
        }

        for (c in containers) {
            for (child in c.children()) {
                val tag = child.tagName()
                if (tag == "h2" || tag == "h3") {
                    flush()
                    currentTitle = child.text().substringBefore("更多").trim().ifBlank { null }
                } else if (tag == "div" && child.hasClass("row")) {
                    buffer += child.select(SiteConfig.CARD_SELECTOR)
                }
            }
        }
        flush()

        // 兜底：区块解析失败时，把整页卡片当作一个"全部游戏"区块
        if (sections.isEmpty()) {
            val all = doc.select(SiteConfig.CARD_SELECTOR)
                .mapNotNull { parseCard(it) }
                .distinctBy { it.id }
            if (all.isNotEmpty()) {
                sections += HomeSection("全部游戏", SiteConfig.listUrl(1), all)
            }
        }
        return sections
    }

    /** 首页区块标题 → 对应"更多 >"链接（同容器内的 a[href^=/nes?]） */
    private fun findMoreUrl(title: String, doc: Document): String? {
        val heading = doc.select(SiteConfig.HOME_SECTION_TITLE_SELECTOR)
            .firstOrNull { it.text().contains(title) } ?: return null
        val container = heading.closest("div.container") ?: return null
        // 优先同标题元素内嵌的"更多"链接
        val inline = heading.select("a[href]").firstOrNull { it.text().contains("更多") }
        val href = inline?.attr("href")
            ?: container.select("a[href^='/nes?']").firstOrNull { it.text().contains("更多") }?.attr("href")
        return href?.let { SiteConfig.absolutize(it) }
    }

    /** 首页一个"热门 XX"区块 */
    data class HomeSection(
        val title: String,
        val moreUrl: String?,
        val items: List<GameItem>
    )
}
