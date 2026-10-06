package com.xbw.tv

import com.xbw.tv.data.plugin.AesChallenge
import com.xbw.tv.data.plugin.GamelistParser
import com.xbw.tv.data.plugin.PluginSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三方源插件的纯逻辑测试：挑战解算、XML 解析、配置序列化。
 * 不碰网络、不碰 Android 上下文，所以能跑在 JVM 单测里。
 */
class GamelistPluginTest {

    /**
     * 挑战页求解：用真站第一轮的那组三元组解出 cookie。
     * AES-CBC/NoPadding，key=a、iv=b、cipher=c，cookie = 解出字节的 hex。
     */
    @Test
    fun `solve real challenge page`() {
        val html = """
            <html><body><script type="text/javascript" src="/aes.js" ></script><script>
            function toNumbers(d){var e=[];d.replace(/(..)/g,function(d){e.push(parseInt(d,16))});return e}
            function toHex(){for(var d=[],d=1==arguments.length&&arguments[0].constructor==Array?arguments[0]:arguments,e="",f=0;f<d.length;f++)e+=(16>d[f]?"0":"")+d[f].toString(16);return e.toLowerCase()}
            var a=toNumbers("f655ba9d09a112d4968c63579db590b4"),
                b=toNumbers("98344c2eee86c3994890592585b49f80"),
                c=toNumbers("c41b9f57e0d97c665fac856c10845130");
            document.cookie="__test="+toHex(slowAES.decrypt(c,2,a,b));
            </script></body></html>
        """.trimIndent()
        assertTrue(AesChallenge.isChallenge(html))
        val cookie = AesChallenge.solve(html)
        assertEquals(32, cookie!!.length)
        assertTrue(cookie.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `challenge detector ignores normal xml`() {
        assertTrue(!AesChallenge.isChallenge("<?xml version=\"1.0\"?><gameList></gameList>"))
    }

    /** 解析真站结构的片段：path/sortable/image/lang */
    @Test
    fun `parse gamelist entries`() {
        val xml = """
            <?xml version="1.0"?>
            <gameList>
            	<game>
            		<path>./01动作/七宝奇谋2.nes</path>
            		<name>Q-七宝奇谋2[qbqm]</name>
            		<sortname>Q-七宝奇谋2</sortname>
            		<image>./01动作/七宝奇谋2.png</image>
            		<lang>en</lang>
            	</game>
            	<game>
            		<path>./07改版/超级玛丽.nes</path>
            		<name>M-超级玛丽[smyl]</name>
            		<sortname>M-超级玛丽</sortname>
            		<image>./07改版/超级玛丽.png</image>
            	</game>
            </gameList>
        """.trimIndent()
        val entries = GamelistParser.parse(xml)
        assertEquals(2, entries.size)
        assertEquals("./01动作/七宝奇谋2.nes", entries[0].path)
        assertEquals("./01动作/七宝奇谋2.png", entries[0].image)
        assertEquals("en", entries[0].lang)
        // 第二个没有 lang，应为空而不是崩
        assertEquals("", entries[1].lang)
    }

    @Test
    fun `split name strips sort letter and pinyin tag`() {
        val (title, initials) = GamelistParser.splitName("M-魔法总动员混沌星辰汉化MS修正[mfzdyh]")
        assertEquals("魔法总动员混沌星辰汉化MS修正", title)
        assertEquals("mfzdyh", initials)
    }

    @Test
    fun `split name keeps titles that start with a letter`() {
        // 站点用首字母排序，但不是所有标题都带 "A-" 前缀；
        // 没有 "字母-" 形态时不能把首字母吃掉
        val (title, _) = GamelistParser.splitName("Zelda传说[zld]")
        assertEquals("Zelda传说", title)
    }

    @Test
    fun `genre comes from folder prefix without number`() {
        assertEquals("动作", GamelistParser.genreOf("./01动作/七宝奇谋2.nes"))
        assertEquals("改版", GamelistParser.genreOf("./07改版/超级玛丽.nes"))
        // 没有目录层级时不该崩，也不该返回空题材
        assertEquals("", GamelistParser.genreOf("game.nes"))
    }

    @Test
    fun `plugin source json round trip`() {
        val src = PluginSource(
            id = "abc", title = "我的FC源", platform = "fc",
            listUrl = "https://example.com/gamelist.xml",
            coverBase = "https://example.com/img/"
        )
        val back = PluginSource.fromJson(src.toJson())!!
        assertEquals(src.id, back.id)
        assertEquals(src.title, back.title)
        assertEquals(src.platform, back.platform)
        assertEquals(src.listUrl, back.listUrl)
        assertEquals(src.coverBase, back.coverBase)
        // 默认值往返后不该变成 null
        assertEquals("", back.romBase)
    }

    @Test
    fun `plugin list json round trip keeps order`() {
        val list = listOf(
            PluginSource.BUILTIN_FC_186317,
            PluginSource("u1", "源一", "fc", "https://a/gamelist.xml"),
            PluginSource("u2", "源二", "gba", "https://b/gamelist.xml")
        )
        val back = PluginSource.parseList(PluginSource.toJsonList(list))
        assertEquals(3, back.size)
        assertEquals(list.map { it.id }, back.map { it.id })
    }

    @Test
    fun `malformed json yields empty list instead of crash`() {
        assertTrue(PluginSource.parseList("not json").isEmpty())
        assertTrue(PluginSource.parseList("[]").isEmpty())
        // 缺 listUrl 的条目要被丢掉
        assertTrue(PluginSource.parseList("""[{"id":"x","title":"y"}]""").isEmpty())
        assertNull(PluginSource.fromJson(org.json.JSONObject().put("title", "无地址")))
    }

    @Test
    fun `derive id is stable and url specific`() {
        val a = PluginSource.deriveId("https://a/gamelist.xml")
        assertEquals(a, PluginSource.deriveId("https://a/gamelist.xml"))
        assertTrue(a != PluginSource.deriveId("https://b/gamelist.xml"))
    }

    @Test
    fun `builtin source targets fc and is protected`() {
        val b = PluginSource.BUILTIN_FC_186317
        assertEquals("fc", b.platform)
        assertTrue(b.builtin)
        assertEquals("https://186317.22web.org/nes/gamelist.xml", b.listUrl)
        assertEquals("FC第三方", b.title)
    }
}