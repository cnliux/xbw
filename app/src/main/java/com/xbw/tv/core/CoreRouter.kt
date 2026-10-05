package com.xbw.tv.core

import com.xbw.tv.data.model.GameCategory

/**
 * ▶ 平台 ↔ libretro 核心 的**唯一**映射表（搜索过滤、ROM 准备、核心路由都走这里）。
 *
 * 之前这份规则散在 RomProvider.coreFor() 与 RomProvider.prepare() 的 when 里，
 * 站点再新增一个 gromname 前缀就要改两处；现在改这里一处即可。
 *
 * 路由依据（实测 2026-10，docs/YIKM_SITE_STRUCTURE.md §2.6）：
 *  - play 页内嵌 JS 全局变量 `gameType` / `gromname` / `gsystem`；
 *  - 街机认 `gameType="arcade"`（整 zip 喂 FBNeo）；
 *  - FC/街机以外的平台 play 页没有 gameType，用 gromname 的固定目录前缀区分：
 *    `/fcrom/…nes` → fceumm，`/gbarom/x.zip` → mgba，
 *    `/mdrom/mdN.zip` → genesis_plus_gx，`/sfc/….7z` → snes9x。
 */
object CoreRouter {

    /** 站点 play 页字段（三个全局变量） */
    data class PlayInfo(
        val gameType: String?,
        val gromname: String,
        val gsystem: String
    )

    /**
     * play 页抓取结果。三态必须分开，否则 UI 会把"这游戏压根没 ROM 直链"说成"网络失败"：
     *  - [Ok] 页面正常且有 `gromname`（站点提供了可直链下载的 ROM）
     *  - [NoRom] 页面正常但没有 `gromname`：NDS/DOS/Java/Flash/H5 这类网页版，
     *    play 页只有一个空的 `<div id="game">` 容器，站点不提供 ROM 直链
     *  - [FetchFailed] 页面没抓到（离线/限流/改版），可重试
     */
    sealed class PlayResult {
        data class Ok(val info: PlayInfo) : PlayResult()
        object NoRom : PlayResult()
        object FetchFailed : PlayResult()
    }

    /**
     * 解析 play 页 HTML → 平台字段。
     * @return [PlayResult.Ok] 正常；[PlayResult.NoRom] 无 ROM 直链；
     *   null = 页面结构异常（连游戏容器都没有，多半是改版或被拦了）
     */
    fun parsePlay(html: String): PlayResult? {
        // 容错匹配 `var gromname="..."` 与 `var gromname = "..."` 两种写法
        fun jsVar(name: String): String? =
            Regex("""\b$name\s*=\s*"([^"]*)\"""").find(html)?.groupValues?.get(1)

        val gromname = jsVar("gromname")
        if (gromname.isNullOrBlank()) {
            // 站点有游戏容器但没有 ROM 字段 = 网页版（NDS/DOS/Java/Flash/H5）
            val hasGameContainer = html.contains("id=\"game\"") || html.contains("id=\"canvas\"")
            return if (hasGameContainer) PlayResult.NoRom else null
        }
        return PlayResult.Ok(PlayInfo(jsVar("gameType"), gromname, jsVar("gsystem").orEmpty()))
    }

    /**
     * (gameType, gromname) → libretro 核心名（= lib\<name\>.so）。
     * @return null = 该平台没有原生核心（NDS / Java / DOS / Flash / H5）
     */
    fun coreFor(gameType: String?, gromname: String): String? = when {
        gameType == "arcade" -> "fbneo"
        gromname.startsWith("/fcrom") -> "fceumm"
        gromname.startsWith("/gbarom") -> "mgba"
        gromname.startsWith("/mdrom") -> "genesis_plus_gx"
        gameType == "sfc" || gromname.startsWith("/sfc") ||
                gromname.substringBeforeLast('$').trim().endsWith(".7z") -> "snes9x"
        gameType == "fc" -> "fceumm"
        else -> null
    }

    /** 核心名 → 站点分类 key（用于本地索引 / 平台缓存统一口径） */
    fun categoryKeyOf(coreName: String): String? = when (coreName) {
        "fceumm" -> GameCategory.FC.key
        "fbneo" -> GameCategory.ARCADE.key
        "snes9x" -> GameCategory.SFC.key
        "mgba" -> GameCategory.GBA.key
        "genesis_plus_gx" -> GameCategory.MD.key
        else -> null
    }

    /** 有原生核心的平台（拼音索引只建这些，搜索结果也只放行这些） */
    val playableCategoryKeys: List<String> =
        listOf("fceumm", "fbneo", "snes9x", "mgba", "genesis_plus_gx")
            .mapNotNull { categoryKeyOf(it) }

    fun isPlayableCategory(categoryKey: String): Boolean =
        categoryKey.isNotBlank() && categoryKey in playableCategoryKeys

    /** 平台探测结果：能否用原生核心跑 */
    sealed class Platform {
        /** 有核心：[categoryKey] 是站点分类 key，[coreName] 对应 lib\<coreName\>.so */
        data class Native(val categoryKey: String, val coreName: String) : Platform()

        /** 站点有这个游戏，但本 App 没有对应核心（NDS/Java/DOS/Flash/H5） */
        object Unsupported : Platform()

        /** play 页抓不到（离线/改版/限流），保守起见按"先放行"处理 */
        object Unknown : Platform()
    }

    fun platformOf(info: PlayInfo): Platform {
        val core = coreFor(info.gameType, info.gromname)
            ?: return Platform.Unsupported
        val key = categoryKeyOf(core)
            ?: return Platform.Unsupported
        return Platform.Native(key, core)
    }
}
