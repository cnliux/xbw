package com.xbw.tv.data.model

/**
 * 站点分类。value 直接对应 yikm.net 的列表页 URL 参数，实测结论：
 *
 *   /nes?tag=0    → FC（热门 FC）
 *   /nes?tag=9    → 街机
 *   /nes?tag=11   → GBA
 *   /nes?tag=13   → MD
 *   /nes?tag=1    → H5 游戏
 *   /nes?e=5&tag= → SFC（e 是 emulator 编号）
 *   /nes?e=6&tag= → DOS
 *   /nes?e=7&tag= → NDS
 *   /nes?e=8&tag= → Java
 *   /nes?e=9&tag= → Flash
 *   /h5           → 第三方页游（广告性质，默认不在大厅展示）
 *
 * 顶部导航与"更多 >"链接都指向这些 URL，因此分类列表复用同一套卡片解析逻辑。
 */
enum class GameCategory(
    val key: String,
    val title: String,
    /** 相对路径；null 表示"全部游戏"（首页底部列表） */
    val listPath: String?
) {
    ALL("all", "全部游戏", "/nes?page=1&tag=&e="),
    FC("fc", "FC / 红白机", "/nes?tag=0"),
    ARCADE("arcade", "街机", "/nes?tag=9"),
    GBA("gba", "GBA", "/nes?tag=11"),
    SFC("sfc", "SFC", "/nes?e=5&tag="),
    MD("md", "MD", "/nes?tag=13"),
    NDS("nds", "NDS", "/nes?e=7&tag="),
    JAVA("java", "Java", "/nes?e=8&tag="),
    DOS("dos", "DOS", "/nes?e=6&tag="),
    FLASH("flash", "Flash", "/nes?e=9&tag="),
    H5("h5", "H5 游戏", "/nes?tag=1"),
    /**
     * 旧的第三方聚合分类，2026-10 起废弃：分类不再写死，每个用户自建源在大厅单个芯片，
     * 标题由用户设定、核心由用户选择。条目仍保留做旧配置/缓存的 fromKey 兼容，不再展示、不再抓取。
     */
    THIRD("third", "第三方", null),
    THIRD_ARCADE("third_arcade", "第三方街机", null),
    /** U盘本地游戏（自动扫描挂载的 USB 卷里的常见 ROM 扩展名），见 data/usb/UsbScanner */
    USB("usb", "U盘游戏", null),
    FAVORITE("favorite", "我的收藏", null),
    RECENT("recent", "最近玩过", null);

    /** 大厅里可翻页浏览的分类（最近玩过是本地数据，不参与抓取） */
    val fetchable: Boolean get() = listPath != null

    /** 有原生核心（可进游戏页） */
    val playable: Boolean get() = this == FC || this == ARCADE || this == SFC || this == GBA || this == MD

    companion object {
        /**
         * 大厅顶部展示的内置分类：**屏蔽没有原生核心的** NDS / Java / DOS / Flash / H5，
         * 也不再放死的「第三方 / 第三方街机」聚合芯片——用户自建的源由大厅动态生成独立芯片。
         * 分类仍然保留在 [entries] 里 —— 老链接、缓存里的 categoryKey 还能正常解析。
         */
        val lobbyChips: List<GameCategory> = listOf(
            ALL, FC, ARCADE, GBA, SFC, MD, USB, FAVORITE, RECENT
        )

        /** 有原生核心、可被第三方源选为运行核心的分类（插件编辑页「运行核心」选择器用它） */
        val playableCategories: List<GameCategory> = listOf(FC, ARCADE, SFC, GBA, MD)

        fun fromKey(key: String?): GameCategory =
            entries.firstOrNull { it.key == key } ?: ALL

        /**
         * 卡片标签 → 分类 key。搜索结果卡片只带"标签"这一个弱信号：命中平台名时可信
         * （站点的平台分类就叫这些名字），命中题材词（运动比赛、双子系列…）时不可信。
         * 只用于**否定判断**：标签明确是 NDS/Java/DOS/Flash/H5 时可直接判"没核心"。
         */
        private val BY_LABEL: Map<String, GameCategory> = buildMap {
            // 注意：buildMap 内部 this 是 Map，entries 必须限定为 GameCategory.entries
            GameCategory.entries.forEach { put(it.title.lowercase(), it) }
            put("fc", FC); put("红白机", FC); put("nes", FC)
            put("gba", GBA)
            put("snes", SFC); put("sfc", SFC)
            put("md", MD); put("mdp", MD); put("genesis", MD); put("世嘉", MD)
            put("街机", ARCADE); put("arcade", ARCADE); put("cps1", ARCADE); put("cps2", ARCADE)
            put("h5", H5); put("h5 游戏", H5)
        }

        /** 标签里出现平台名就返回对应分类；题材词返回 null */
        fun fromLabel(label: String?): GameCategory? {
            val t = label?.trim()?.lowercase() ?: return null
            return BY_LABEL[t]
        }
    }
}
