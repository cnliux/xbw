package com.xbw.tv

import com.xbw.tv.data.net.CheatParser
import com.xbw.tv.data.net.SiteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CheatParser 纯 JVM 解析测试。
 *
 * 样本全部是 2026-10 实测抓到的真实响应（GET https://www.yikm.net/cheat?id=<id>）：
 *  - 3882 激龟快打：单字节码
 *  - 4141：多字节码 + TYPE 与字节数不一致（007F-14-1E1E1E1E）
 *  - 4712：名称里带 $（7A00-01-FF$1P$HP）
 */
class CheatParserTest {

    // ------------------------------------------------------------------
    // 真实响应样本
    // ------------------------------------------------------------------

    @Test
    fun parse_realPayload_3882_singleByte() {
        val payload = "0590-01-B0\$1P血槽,0591-01-00\$2P血槽,0649-01-02\$一局定胜负," +
                "0600-01-80\$1P能量珠无限,0601-01-80\$2P能量珠无限"
        val list = CheatParser.parse(payload)

        assertEquals(5, list.size)
        assertEquals("1P血槽", list[0].name)
        assertEquals("0590:B0", list[0].code)
        assertEquals("0590-01-B0\$1P血槽", list[0].raw)
        assertEquals("2P能量珠无限", list[4].name)
        assertEquals("0601:80", list[4].code)
    }

    @Test
    fun parse_realPayload_4141_multiByteAndIgnoredType() {
        val payload = "00B3-04-9A9A9A9A\$生命无限,0369-01-05\$援助无限," +
                "007F-14-1E1E1E1E\$最强装备,07C8-11-46\$无敌,0075-01-04\$最终关"
        val list = CheatParser.parse(payload)

        assertEquals(5, list.size)
        // 4 字节 → 展开成地址连续的 4 段
        assertEquals("生命无限", list[0].name)
        assertEquals("00B3:9A,00B4:9A,00B5:9A,00B6:9A", list[0].code)
        // TYPE=14 但 VAL 仍只有 4 字节：字节数只由 VAL 长度决定
        assertEquals("007F:1E,0080:1E,0081:1E,0082:1E", list[2].code)
        // TYPE=11、VAL 单字节
        assertEquals("07C8:46", list[3].code)
    }

    @Test
    fun parse_realPayload_4712_nameMayContainDollar() {
        val payload = "7AE0-03-999999\$1P金钱,7AE3-03-999999\$2P金钱," +
                "7A00-01-FF\$1P\$HP,7A01-01-FF\$2P\$HP"
        val list = CheatParser.parse(payload)

        assertEquals(4, list.size)
        // 只按第一个 $ 切分，名称里的 $ 保留
        assertEquals("1P\$HP", list[2].name)
        assertEquals("7A00:FF", list[2].code)
        assertEquals("2P\$HP", list[3].name)
        // 3 字节
        assertEquals("7AE0:99,7AE1:99,7AE2:99", list[0].code)
    }

    // ------------------------------------------------------------------
    // 边界与容错
    // ------------------------------------------------------------------

    @Test
    fun parse_emptyMeansNoCheats() {
        assertEquals(0, CheatParser.parse("").size)
        assertEquals(0, CheatParser.parse("   \n ").size)
    }

    @Test
    fun parse_skipsMalformedEntriesButKeepsGoodOnes() {
        val payload = "0590-01-B0\$好码," +
                "ZZZZ-01-00\$坏地址," +          // 地址非十六进制
                "0590-01-0\$奇数长度," +         // VAL 长度奇数
                "0590-01-GG\$非十六进制值," +     // VAL 非十六进制
                "noDashHere," +                  // 缺分隔符
                "0592-01-11"                     // 无名称 → 回落成代码原文
        val list = CheatParser.parse(payload)

        assertEquals(2, list.size)
        assertEquals("好码", list[0].name)
        assertEquals("0590:B0", list[0].code)
        assertEquals("0592-01-11", list[1].name)
        assertEquals("0592:11", list[1].code)
    }

    @Test
    fun parse_rejectsOverflowingAddress() {
        // 0xFFFF 起写 2 字节 → 越过 64K 地址空间，整段丢弃
        assertEquals(0, CheatParser.parse("FFFF-02-1122\$越界").size)
        // 正好写到 0xFFFF 是合法的
        assertEquals(1, CheatParser.parse("FFFE-02-1122\$压线").size)
    }

    @Test
    fun parse_lowercaseHexIsNormalizedToUpper() {
        val list = CheatParser.parse("0abc-01-ff\$小写")
        assertEquals(1, list.size)
        assertEquals("0ABC:FF", list[0].code)
    }

    // ------------------------------------------------------------------
    // URL 拼装
    // ------------------------------------------------------------------

    @Test
    fun cheatUrl_usesCheatEndpoint() {
        assertEquals("${SiteConfig.BASE_URL}/cheat?id=3882", SiteConfig.cheatUrl("3882"))
    }
}
