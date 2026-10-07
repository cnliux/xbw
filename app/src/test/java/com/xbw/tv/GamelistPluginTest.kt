package com.xbw.tv

import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.plugin.AesChallenge
import com.xbw.tv.data.plugin.DirectoryIndexParser
import com.xbw.tv.data.plugin.GamelistParser
import com.xbw.tv.data.plugin.PluginRepository
import com.xbw.tv.data.plugin.PluginSource
import com.xbw.tv.data.usb.UsbScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 解析真站结构的片段：path/sortable/image/video/lang */
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
            		<video>./videos/metmqstr.mp4</video>
            		<lang>en</lang>
            	</game>
            	<game>
            		<path>./07改版/超级玛丽.nes</path>
            		<name>M-超级玛丽[smyl]</name>
            		<sortname>M-超级玛丽</sortname>
            		<image>./07改版/超级玛丽.png</image>
            	</game>
            	<game>
            		<path>./06文字/魔法总动员混沌星辰汉化MS修正.nes</path>
            		<name>M-魔法总动员混沌星辰汉化MS修正[mfzdyh]</name>
            		<sortname>M-魔法总动员混沌星辰汉化MS修正</sortname>
            		<image>./06文字/魔法总动员混沌星辰汉化MS修正.png</image>
            		<playcount>1</playcount>
            		<lastplayed>20220816T074207</lastplayed>
            		<gametime>107</gametime>
            	</game>
            </gameList>
        """.trimIndent()
        val entries = GamelistParser.parse(xml)
        assertEquals(3, entries.size)
        assertEquals("./01动作/七宝奇谋2.nes", entries[0].path)
        assertEquals("./01动作/七宝奇谋2.png", entries[0].image)
        assertEquals("./videos/metmqstr.mp4", entries[0].video)
        assertEquals("en", entries[0].lang)
        // 第二个：没有 video/lang，应为空而不是崩
        assertEquals("", entries[1].video)
        assertEquals("", entries[1].lang)
        // 第三个：playcount/lastplayed/gametime 是干扰字段，应被跳过且不污染字段
        assertEquals("./06文字/魔法总动员混沌星辰汉化MS修正.nes", entries[2].path)
        assertEquals("", entries[2].video)
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
            PluginSource("u0", "源零", "fc", "https://fc/gamelist.xml"),
            PluginSource("u1", "源一", "fc", "https://a/gamelist.xml"),
            PluginSource("u2", "源二", "arcade", "https://b/gamelist.xml")
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

    /** 现在有一个随包内置源「内置FC」（wget.la 镜像的 cnliux/xbw nes 清单），
     *  用户自建源 builtin 恒 false；老配置里残留 builtin:true 也能读回来 */
    @Test
    fun `builtin fc source present, user sources not builtin`() {
        val builtins = PluginSource.builtinSources()
        assertEquals(1, builtins.size)
        val fc = builtins[0]
        assertTrue("内置源标记 builtin", fc.builtin)
        assertEquals("内置FC", fc.title)
        assertEquals(GameCategory.FC.key, fc.platform)
        assertEquals(PluginSource.BUILTIN_FC_URL, fc.listUrl)
        assertEquals("内置源 id 由 URL 派生，用户添加同 URL 会被去重",
            fc.id, PluginSource.deriveId(PluginSource.BUILTIN_FC_URL))

        val src = BundlePluginSource(listUrl = "https://a/gamelist.xml")
        assertFalse("新建源不是内置", src.builtin)
        // 老配置里残留 builtin:true 也能读回来（兼容）
        val legacy = PluginSource.fromJson(
            org.json.JSONObject(
                "{\"id\":\"old\",\"title\":\"老源\",\"listUrl\":\"https://a/g.xml\",\"builtin\":true}"
            )
        )!!
        assertTrue(legacy.builtin)
    }

    /** xml 里的 path/image/video 支持绝对 http 直链：不做 base 拼接、不做编码 */
    @Test
    fun `xml absolute http urls pass through`() {
        val xml = """
            <?xml version="1.0"?>
            <gameList>
            	<game>
            		<path>http://186317.xo.je/roms/拳皇97.zip</path>
            		<name>拳皇97</name>
            		<image>https://cdn.img.com/../kof97.png?size=w300</image>
            		<video>http://v.example.com/kof97.mp4</video>
            	</game>
            </gameList>
        """.trimIndent()
        val src = BundlePluginSource(
            listUrl = "https://dummy/gamelist.xml", platform = "arcade", gbkUris = true
        )
        val items = PluginRepository.parse(src, xml)
        assertEquals(1, items.size)
        val it = items[0]
        // 直链原样保留：没拼 dummy 的 base，没被 GBK/UTF-8 编码
        assertEquals("http://186317.xo.je/roms/拳皇97.zip", it.rawPath)
        assertEquals("http://186317.xo.je/roms/拳皇97.zip", it.playUrl)
        assertEquals("https://cdn.img.com/../kof97.png?size=w300", it.coverUrl)
        assertEquals("http://v.example.com/kof97.mp4", it.videoUrl)
        // 下载候选 = 直链本身
        assertEquals(
            listOf("http://186317.xo.je/roms/拳皇97.zip"),
            PluginRepository.romCandidates(src, it.copy(platformKey = "arcade"))
        )
    }

    /** 网关没了：downloadBase 字段与 getter 都不应存在（编译期即验证，这里再锁住行为） */
    @Test
    fun `dir bootstrap json round trip keeps new fields`() {
        val src = PluginSource(
            id = "dir1", title = "目录源", platform = "arcade",
            listUrl = "https://a/game/", dirBootstrap = true, dirMatch = "FBA",
            gbkUris = true, filterByPlatform = true
        )
        val back = PluginSource.fromJson(src.toJson())!!
        assertTrue(back.dirBootstrap)
        assertEquals("FBA", back.dirMatch)
        assertTrue(back.gbkUris)
        assertTrue(back.filterByPlatform)
        // 非目录源默认保持关闭，序列化不丢
        assertFalse("xml 源不该有开着的 dirBootstrap", PluginSource.fromJson(
            PluginSource("x", "y", "fc", "https://a/g").toJson()
        )!!.dirBootstrap)
    }

    /** Apache 目录索引页：一行一个链接，href 带真实字节，去排序参数、去父目录 */
    @Test
    fun `parse apache autoindex page`() {
        val html = """
            <html><head><title>Index of /game</title></head><body>
            <h1>Index of /game</h1><hr><pre>
            <a href="../">../</a>
            <a href="nes/">nes/</a>
            <a href="%34%39-FBA%E5%8D%95%E6%9C%BA%E6%B8%B8%E6%88%8F/">09-FBA单机游戏/</a>
            <a href="nes/gamelist.xml">gamelist.xml</a> 2024-01-01 12:00 139985
            <a href="?C=N;O=D">Name</a>
            <hr></pre></body></html>
        """.trimIndent()
        val entries = DirectoryIndexParser.parse(html, "https://186317.22web.org/game/")
        assertEquals(3, entries.size)
        val arc = entries.firstOrNull { it.name.contains("FBA") }
        assertEquals("09-FBA单机游戏", arc!!.name)
        assertTrue(arc.isDir)
        // href 的百分号字节被原样保留（dirMatch 匹配不了时就靠它当 base）
        assertTrue(arc.url.startsWith("https://186317.22web.org/game/%34%39-FBA"))
        assertTrue(entries.any { it.name == "gamelist.xml" && !it.isDir })
        assertFalse(entries.any { it.name == "../" })
        // 排序链接（?C=N;O=D）不该被当成条目
        assertFalse(entries.any { it.url.contains("?C=") })
    }

    /** cPanel/文件管理器式索引：href 带 next= 查询参数，只取文件路径部分 */
    @Test
    fun `parse file-manager index page strips query params`() {
        val html = """
            <table>
            <tr><td><a href="?next=/game/GBA/">GBA</a></td></tr>
            <tr><td><a href="?next=/game/GBA/kof.nes">kof.nes</a></td></tr>
            <tr><td><a href="..">..</a></td></tr>
            </table>
        """.trimIndent()
        val entries = DirectoryIndexParser.parse(html, "https://example.com/game/")
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.url == "https://example.com/game/GBA/" && it.isDir })
        assertTrue(entries.any { it.url == "https://example.com/game/GBA/kof.nes" && !it.isDir })
    }

    /** 便捷扩展名 → 平台 key 映射：常见 ROM 后缀能进核心，垃圾后缀被跳过 */
    @Test
    fun `usb extension maps to playable platform`() {
        assertEquals("fc", UsbScanner.platformOf("魂斗罗.nes"))
        assertEquals("fc", UsbScanner.platformOf("B.D.T.FDS"))
        assertEquals("gba", UsbScanner.platformOf("口袋妖怪.gba"))
        assertEquals("sfc", UsbScanner.platformOf("ff6.SMC"))
        assertEquals("md", UsbScanner.platformOf("Sonic.smd"))
        assertEquals("arcade", UsbScanner.platformOf("1942.zip"))
        assertEquals("arcade", UsbScanner.platformOf("kof97.7z"))
        // 没核心/不是 ROM 的扩展名：不扫
        assertNull(UsbScanner.platformOf("readme.txt"))
        assertNull(UsbScanner.platformOf("album.jpg"))
        assertNull(UsbScanner.platformOf("无扩展名"))
    }

    /** GBK 站的 URL 编码：ASCII 路径段（game/nes/…）原样保留，中文段按 GBK %XX */
    @Test
    fun `gbk effective url keeps ascii path but encodes cn bytes`() {
        val src = BundlePluginSource(
            listUrl = "https://186317.22web.org/game/", gbkUris = true, dirBootstrap = true
        )
        assertEquals("https://186317.22web.org/game/", src.effectiveListUrl)
        val withCn = BundlePluginSource(
            listUrl = "https://a.com/game/09-FBA单机游戏/gamelist.xml", gbkUris = true
        )
        val eff = withCn.effectiveListUrl
        assertTrue(eff.startsWith("https://a.com/game/09-FBA"))
        assertTrue(eff.endsWith("/gamelist.xml"))
        // 中文段被编码成 GBK 字节，纯 ASCII 段一个 % 都没有
        assertTrue("中文段必须编码: $eff", eff.contains("%"))
        val asciiTail = eff.removePrefix("https://a.com/game/09-FBA").substringBeforeLast("/gamelist.xml")
        assertTrue("中文段是 GBK 字节: $eff", asciiTail.startsWith("%") && asciiTail.contains("%"))
    }

    /** 相对 path 的 baseDir 解析：按最后一个 / 取清单所在目录 */
    @Test
    fun `baseDir cuts list file`() {
        assertEquals(
            "https://186317.22web.org/game/nes",
            BundlePluginSource(
                listUrl = "https://186317.22web.org/game/nes/gamelist.xml"
            ).baseDir
        )
        // 目录引导源 listUrl 本身就是目录时仍直接取它自己（json 往返测试那条路径）
        val dir = BundlePluginSource(
            listUrl = "https://a/game/", dirBootstrap = true
        )
        assertEquals("https://a/game", dir.baseDir)
    }

    /** 目录引导源解析出的真实字节目录作为 base：cover 拼到它后面 */
    @Test
    fun `dir mode parse joins covers to real byte dir`() {
        val xml = """
            <?xml version="1.0"?>
            <gameList>
            	<game>
            		<path>./caps/kof97.zip</path>
            		<name>拳皇97</name>
            		<sortname>拳皇97</sortname>
            		<image>./caps/kof97.png</image>
            	</game>
            	<game>
            		<path>./caps/1943.bin</path>
            		<name>1943</name>
            		<sortname>1943</sortname>
            		<image>./caps/1943.png</image>
            	</game>
            </gameList>
        """.trimIndent()
        val src = BundlePluginSource(
            listUrl = "https://186317.22web.org/game/",
            platform = "arcade", dirBootstrap = true
        )
        val items = PluginRepository.parse(
            src, xml, "https://186317.22web.org/game/%34%39-FBA%CO%DA%BB%F8"
        )
        assertEquals(2, items.size)
        assertTrue(items.all { it.platformKey == "arcade" })
        // 标题按原名，封面拼在真实字节目录后面（不再是错的 gamelist 目录）
        assertTrue(items.all { it.coverUrl!!.startsWith("https://186317.22web.org/game/%34%39-FBA") })
    }

    /** 合并清单按扩展名过滤（跨平台文件只留本核心平台的）；独立清单不过滤（zip/7z 打包的 FC ROM 不丢） */
    @Test
    fun `merged list filters arcade view but fc file parses all`() {
        val xml = """
            <?xml version="1.0"?>
            <gameList>
            	<game><path>./01动作/魂斗罗.nes</path><name>魂斗罗</name><image>./01动作/魂斗罗.png</image></game>
            	<game><path>./caps/kof97.zip</path><name>拳皇97</name><image>./caps/kof97.png</image></game>
            	<game><path>./caps/1943.bin</path><name>1943</name><image>./caps/1943.png</image></game>
            	<game><path>./改版/超级玛丽.zip</path><name>超级玛丽</name><image>./改版/超级玛丽.png</image></game>
            </gameList>
        """.trimIndent()
        // 街机视口：只留 arcade 条目（.nes 的魂斗罗被滤掉；zip/bin 均为 arcade）
        val arcadeItems = PluginRepository.parse(
            BundlePluginSource(
                listUrl = "https://186317.22web.org/game/gamelist.xml",
                platform = "arcade", filterByPlatform = true
            ),
            xml
        )
        assertEquals(3, arcadeItems.size)
        assertTrue(arcadeItems.none { it.name == "魂斗罗" })
        assertTrue(arcadeItems.all { it.platformKey == "arcade" })
        // FC 独立解析自家 xml：不过滤，包括 zip 打包的 FC ROM。所有条目 platformKey =
        // 用户选的源核心（"fc"），进游戏就认它 —— zip 只是存储格式，绝不按扩展名猜成街机
        val fcItems = PluginRepository.parse(
            BundlePluginSource(listUrl = "https://186317.22web.org/game/nes/gamelist.xml", platform = "fc"),
            xml
        )
        assertEquals("FC 独立解析不过滤，4 条都在", 4, fcItems.size)
        assertEquals("fc", fcItems.first { it.name == "魂斗罗" }.platformKey)
        // 武功秘籍：FC 文件里 zip 打包只是存储格式，仍应走 FC 核心
        assertEquals("fc", fcItems.first { it.name == "超级玛丽" }.platformKey)
    }
}

/** 省得在测试里长参数串一遍数据类 */
private fun BundlePluginSource(
    listUrl: String,
    platform: String = "fc",
    gbkUris: Boolean = false,
    dirBootstrap: Boolean = false,
    filterByPlatform: Boolean = false
) = PluginSource(
    id = "test1", title = "测试源", platform = platform, listUrl = listUrl,
    gbkUris = gbkUris, dirBootstrap = dirBootstrap, filterByPlatform = filterByPlatform
)