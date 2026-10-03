package com.xbw.tv.data.net

/**
 * 金手指数据解析（纯 JVM，便于单测）。
 *
 * 站点接口：GET /cheat?id=<游戏id>，响应体是纯文本、逗号分隔（实测）：
 *
 * ```
 * 0590-01-B0$1P血槽,0591-01-00$2P血槽
 * 00B3-04-9A9A9A9A$生命无限          ← 4 字节：00B3 起连续写 9A 9A 9A 9A
 * 7A00-01-FF$1P$HP                  ← 名称里还带 $，只按【第一个】$ 切分
 * ```
 *
 * 每段格式 `ADDR-TYPE-VAL$名称`：
 *  - ADDR = 十六进制起始地址；
 *  - TYPE = 站点自己的分类字段（01/04/11/14…），**与字节数无关**（实测 `007F-14-1E1E1E1E`
 *    的 TYPE 是 14 但 VAL 只有 4 字节），所以直接忽略；
 *  - VAL  = 十六进制数据，长度 / 2 即字节数，逐字节写到 ADDR、ADDR+1 …
 *
 * libretro 核心只认 `AAAA:VV` 形式（见 vendor/libretro-fceumm `retro_cheat_set`：
 * strtok 以 `+,;._ ` 分隔，每段必须恰好 7 字符），多字节必须自己展开成多段，
 * 用逗号连接后整串交给一次 `retro_cheat_set`。
 */
object CheatParser {

    /** 一条可直接喂给核心的金手指 */
    data class Cheat(
        /** 展示名（站点原文，可能自带 $） */
        val name: String,
        /** 已展开的 libretro 码，如 "00B3:9A,00B4:9A" */
        val code: String,
        /** 站点原始段，如 "00B3-04-9A9A9A9A"（面板副标题/排错用） */
        val raw: String
    )

    /** 防御异常响应：单条最多 64 字节、总计最多 200 条 */
    private const val MAX_BYTES = 64
    private const val MAX_CHEATS = 200

    private val HEX = Regex("^[0-9A-Fa-f]+$")

    /** 解析整段响应；空响应（该游戏没有金手指）返回空列表 */
    fun parse(payload: String): List<Cheat> {
        if (payload.isBlank()) return emptyList()
        val out = ArrayList<Cheat>()
        for (entry in payload.trim().split(',')) {
            if (out.size >= MAX_CHEATS) break
            parseEntry(entry.trim())?.let { out += it }
        }
        return out
    }

    /** 单段 `ADDR-TYPE-VAL$名称` → 展开后的 Cheat；不合法返回 null（跳过而不是整体失败） */
    private fun parseEntry(entry: String): Cheat? {
        if (entry.isEmpty()) return null
        val sep = entry.indexOf('$')
        val codePart = (if (sep >= 0) entry.substring(0, sep) else entry).trim()
        // 名称只按第一个 $ 切：7A00-01-FF$1P$HP 的名称就是 "1P$HP"
        val name = if (sep >= 0) entry.substring(sep + 1).trim() else ""

        val parts = codePart.split('-')
        if (parts.size < 2) return null
        val addr = parts.first().trim().toIntOrNull(16) ?: return null
        val valHex = parts.last().trim()
        if (valHex.isEmpty() || valHex.length % 2 != 0 || !HEX.matches(valHex)) return null

        val bytes = valHex.chunked(2)
        if (bytes.size > MAX_BYTES) return null
        // FC 地址空间 0x0000-0xFFFF：越界整段丢弃（异常数据不如不要）
        if (addr < 0 || addr + bytes.size > 0x10000) return null

        val libretro = bytes
            .mapIndexed { i, b -> "%04X:%s".format(addr + i, b.uppercase()) }
            .joinToString(",")
        return Cheat(name.ifBlank { codePart }, libretro, entry)
    }
}
