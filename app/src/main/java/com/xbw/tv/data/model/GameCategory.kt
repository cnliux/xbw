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
    FAVORITE("favorite", "我的收藏", null),
    RECENT("recent", "最近玩过", null);

    /** 大厅里可翻页浏览的分类（最近玩过是本地数据，不参与抓取） */
    val fetchable: Boolean get() = listPath != null

    companion object {
        fun fromKey(key: String?): GameCategory =
            entries.firstOrNull { it.key == key } ?: ALL
    }
}
