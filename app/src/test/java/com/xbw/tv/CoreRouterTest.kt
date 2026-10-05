package com.xbw.tv

import com.xbw.tv.core.CoreRouter
import com.xbw.tv.data.model.GameCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台 ↔ 核心 路由规则的回归测试。
 *
 * 这些判定直接决定"能不能进游戏"和"搜索里显不显示"，站点改版时最容易悄悄错，
 * 所以把实测过的 play 页字段（docs/YIKM_SITE_STRUCTURE.md §2.6）固化成用例。
 * 纯 JVM，无 Android 依赖（CoreRouter 不碰任何 Android API）。
 */
class CoreRouterTest {

    private fun playHtml(vararg vars: Pair<String, String>): String {
        val script = vars.joinToString("") { (k, v) -> """var $k="$v";""" }
        return "<html><body><div id=\"game\"></div><script>$script</script></body></html>"
    }

    @Test
    fun `街机走 fbneo 并用 gsystem 拼 CDN 路径`() {
        val r = CoreRouter.parsePlay(
            playHtml("gameType" to "arcade", "gsystem" to "capcom-cps-1",
                "gromname" to "/roms/fbneo/capcom-cps-1/wof3js.zip\$wof.zip")
        ) as CoreRouter.PlayResult.Ok
        assertEquals("arcade", r.info.gameType)
        assertEquals("capcom-cps-1", r.info.gsystem)
        // 三剑圣（9540）：CPS1，核心必须落到 FBNeo 而不是 Neo Geo
        val platform = CoreRouter.platformOf(r.info)
        assertTrue(platform is CoreRouter.Platform.Native)
        platform as CoreRouter.Platform.Native
        assertEquals("fbneo", platform.coreName)
        assertEquals(GameCategory.ARCADE.key, platform.categoryKey)
    }

    @Test
    fun `FC SFC GBA MD 按 gromname 前缀识别`() {
        fun coreOf(gameType: String?, grom: String): String? =
            (CoreRouter.parsePlay(playHtml("gameType" to gameType.orEmpty(), "gromname" to grom))
                as CoreRouter.PlayResult.Ok).let { CoreRouter.coreFor(it.info.gameType, it.info.gromname) }

        assertEquals("fceumm", coreOf(null, "/fcrom/魂斗罗(美版).nes"))
        assertEquals("snes9x", coreOf(null, "/sfc/超级机器人大战.7z"))
        assertEquals("mgba", coreOf(null, "/gbarom/gbaxxx.zip"))
        assertEquals("genesis_plus_gx", coreOf(null, "/mdrom/md1.zip"))
        assertEquals("fbneo", coreOf("arcade", "/roms/fbneo/neogeo/msl.zip"))
    }

    @Test
    fun `没有 gromname 的网页版判为无核心而不是抓取失败`() {
        // 实测 NDS/DOS/Java/Flash 的 play 页只有空的 <div id="game">，没有任何 ROM 字段
        val r = CoreRouter.parsePlay(playHtml())
        assertTrue(r is CoreRouter.PlayResult.NoRom)
    }

    @Test
    fun `空页面结构异常交给上层按未知处理`() {
        // 既没有 gromname 也没有页面骨架时返回 null，由 RomProvider 归为抓取失败
        assertNull(CoreRouter.parsePlay(""))
    }

    @Test
    fun `有 ROM 字段但平台没核心时判 Unsupported`() {
        val r = CoreRouter.parsePlay(
            playHtml("gameType" to "h5", "gromname" to "/h5game/some.html")
        ) as CoreRouter.PlayResult.Ok
        assertTrue(CoreRouter.platformOf(r.info) is CoreRouter.Platform.Unsupported)
    }

    @Test
    fun `可玩平台集合只含五种原生核心平台`() {
        assertEquals(
            listOf("fc", "arcade", "sfc", "gba", "md"),
            CoreRouter.playableCategoryKeys
        )
        assertTrue(CoreRouter.isPlayableCategory(GameCategory.FC.key))
        assertTrue(!CoreRouter.isPlayableCategory(GameCategory.NDS.key))
        assertTrue(!CoreRouter.isPlayableCategory(""))
    }

    @Test
    fun `卡片标签明确是没核心的平台时能被识别`() {
        assertEquals(GameCategory.JAVA, GameCategory.fromLabel("Java"))
        assertEquals(GameCategory.DOS, GameCategory.fromLabel("dos"))
        assertEquals(GameCategory.NDS, GameCategory.fromLabel("NDS"))
        assertEquals(GameCategory.GBA, GameCategory.fromLabel("GBA"))
        assertEquals(GameCategory.FC, GameCategory.fromLabel("红白机"))
        // 题材词不能当平台用（否则会把正常游戏误杀）
        assertNull(GameCategory.fromLabel("运动比赛"))
        assertNull(GameCategory.fromLabel("双子系列"))
        assertNull(GameCategory.fromLabel(null))
        assertTrue(!GameCategory.JAVA.playable)
        assertTrue(GameCategory.FC.playable)
    }
}
