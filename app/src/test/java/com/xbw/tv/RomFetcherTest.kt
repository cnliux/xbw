package com.xbw.tv

import com.xbw.tv.data.net.RomFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RomFetcher 纯 JVM 部分：play 页内联变量解析、直链 URL 编码、iNES 魔数、缓存命名。
 * 网络下载/解包走真机集成验证（见 docs/YIKM_SITE_STRUCTURE.md 的直链实测记录）。
 */
class RomFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // play 页解析
    // ------------------------------------------------------------------

    @Test
    fun parsePlayPage_fc() {
        // 实测站点真实格式：变量可能跟在逗号后（非 var 后），单引号双引号混用
        val html = """
            <html><head><title>激龟快打</title></head><body>
            <script> var hascheat='1',gameType="fc",gsystem="fc"; var gromname="/fcrom/gd/Teenage Mutant Hero Turtles - Tournament Fighters (E) [!].nes",gpic="/fcpic/gd/2254a.png",gameid="3882",gname="激龟快打(忍者神龟格斗)欧版";</script>
            </body></html>
        """.trimIndent()
        val meta = RomFetcher.parsePlayPage(html)
        assertEquals("fc", meta.gameType)
        assertEquals("/fcrom/gd/Teenage Mutant Hero Turtles - Tournament Fighters (E) [!].nes", meta.romName)
        assertEquals("激龟快打(忍者神龟格斗)欧版", meta.gameName)
        assertEquals("3882", meta.gameId)
        assertTrue(meta.hasRom)
        assertTrue(meta.isNativeFc)
    }

    @Test
    fun parsePlayPage_varPrefixStillWorks() {
        val html = """<script>var gameid = "4137"; var gameType="fc";
            var gromname = "/fcrom/dzmx/Super Mario Bros. (W) [!].nes";
            var gname="魂斗罗";</script>"""
        val meta = RomFetcher.parsePlayPage(html)
        assertEquals("fc", meta.gameType)
        assertEquals("/fcrom/dzmx/Super Mario Bros. (W) [!].nes", meta.romName)
        assertEquals("4137", meta.gameId)
        assertTrue(meta.isNativeFc)
    }

    @Test
    fun parsePlayPage_nonNativeType() {
        val html = """<script>var gameType="gba"; var gromname="/gbarom/xxx.gba";</script>"""
        val meta = RomFetcher.parsePlayPage(html)
        assertTrue(meta.hasRom)
        assertFalse(meta.isNativeFc)   // GBA 走 WebView 兜底
    }

    @Test
    fun parsePlayPage_missingVars() {
        val meta = RomFetcher.parsePlayPage("<html><body>404</body></html>")
        assertNull(meta.gameType)
        assertNull(meta.romName)
        assertFalse(meta.hasRom)
        assertFalse(meta.isNativeFc)
    }

    // ------------------------------------------------------------------
    // 直链 URL 编码
    // ------------------------------------------------------------------

    @Test
    fun romUrl_encodesSpacesAndBrackets() {
        val url = RomFetcher.romUrl("/fcrom/dzmx/Super Mario Bros. (W) [!].nes")
        assertTrue(url.startsWith(RomFetcher.ROM_CDN + "/fcrom/dzmx/"))
        // 空格/括号/中括号必须百分号编码，OkHttp 才能直接请求
        assertFalse(url.contains(" "))
        assertFalse(url.contains("["))
        assertTrue(url.endsWith(".nes"))
    }

    @Test
    fun romUrl_keepsPathSlashes() {
        val url = RomFetcher.romUrl("/fcrom/a/b/c.nes")
        assertEquals("${RomFetcher.ROM_CDN}/fcrom/a/b/c.nes", url.substringBefore("?").let {
            it // 段内无特殊字符时保持原样
        })
        assertTrue(url.contains("/a/b/"))
    }

    // ------------------------------------------------------------------
    // iNES 魔数
    // ------------------------------------------------------------------

    @Test
    fun isNesRom_detectsMagic() {
        val ok = tmp.newFile("a.nes").apply {
            writeBytes(byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 0x1A) +
                    ByteArray(32))
        }
        val bad = tmp.newFile("b.bin").apply { writeBytes(ByteArray(64)) }
        assertTrue(RomFetcher.isNesRom(ok))
        assertFalse(RomFetcher.isNesRom(bad))
        assertFalse(RomFetcher.isNesRom(File(tmp.root, "missing.nes")))
    }

    // ------------------------------------------------------------------
    // 缓存命名约定：<id>_ 开头跳过 .zip
    // ------------------------------------------------------------------

    @Test
    fun peekCache_skipsZip() {
        val dir = RomFetcher.romDir(tmp.newFolder("cache"))
        File(dir, "1001_xxx.zip").writeBytes(ByteArray(4))
        val rom = File(dir, "1001_game.nes").apply { writeBytes(ByteArray(8)) }
        File(dir, "1002_other.nes").writeBytes(ByteArray(8))

        val hit = RomFetcher.peekCache("1001", tmp.root.resolve("cache"))
        assertEquals(rom.absolutePath, hit?.absolutePath)
        assertNull(RomFetcher.peekCache("9999", tmp.root.resolve("cache")))
    }
}
