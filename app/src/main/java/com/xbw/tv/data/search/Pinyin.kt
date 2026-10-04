package com.xbw.tv.data.search

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 汉字音节表（来自 pinyin4j 自带的 pinyindb 数据，Unihan 公开数据）。
 *
 * 为什么不用 pinyin4j 的 jar：它运行时用 getResourceAsStream 从 classpath 读
 * pinyindb 下的 txt 资源，纯 Java jar 的资源文件在 Android dex 环境根本不会打包，
 * 真机必炸。所以只取数据文件放 assets，自己解析（格式 30 行注释讲得完）。
 *
 * 数据两份：
 *   pinyindb/unicode_to_hanyu_pinyin.txt  单字表 "4E00 (yi1)"，多读音逗号分隔
 *   pinyindb/multi_pinyin.txt             词组表 "一丘之貉 (yi1,qiu1,zhi1,he2)"，
 *                                         每字一音节，用于多音字校正（最长匹配）
 * 懒加载一次（约 40ms），常驻内存 2MB 上下，对 TV 盒子无压力。
 */
object Pinyin {

    /** 单字：char → 各候选读音的首字母（按词典首选音序） */
    private var singles: Map<Char, CharArray> = emptyMap()

    /** 词组：word → 每字首字母拼成的串（长度=音节数） */
    private var phrases: Map<String, String> = emptyMap()

    @Volatile
    private var loaded = false
    private val loadLock = Any()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val ctx = context.applicationContext
            val s = HashMap<Char, CharArray>(40960)
            val p = HashMap<String, String>(10000)
            ctx.assets.open("pinyindb/unicode_to_hanyu_pinyin.txt").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).forEachLine { line ->
                    parseLine(line)?.let { (ch, letters) -> s[ch] = letters }
                }
            }
            ctx.assets.open("pinyindb/multi_pinyin.txt").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).forEachLine { line ->
                    parsePhrase(line)?.let { (word, initials) -> p[word] = initials }
                }
            }
            singles = s
            phrases = p
            loaded = true
        }
    }

    /** "4E00 (yi1,ding1)" → ('一', ['y','d'])；(noneN) 假字丢弃 */
    private fun parseLine(line: String): Pair<Char, CharArray>? {
        val sp = line.indexOf(' ')
        val lp = line.indexOf('(')
        val rp = line.indexOf(')')
        if (sp < 0 || lp < 0 || rp < lp) return null
        val code = line.substring(0, sp).toIntOrNull(16) ?: return null
        val body = line.substring(lp + 1, rp)
        val letters = body.split(',')
            .filter { it.isNotBlank() && !it.startsWith("none") }
            .map { it[0].lowercaseChar() }
            .distinct()
            .toCharArray()
        if (letters.isEmpty()) return null
        return code.toChar() to letters
    }

    /** "一丘之貉 (yi1,qiu1,zhi1,he2)" → ("一丘之貉", "yqzh")，取每音节首字母 */
    private fun parsePhrase(line: String): Pair<String, String>? {
        val lp = line.indexOf('(')
        val rp = line.indexOf(')')
        if (lp < 1 || rp < lp) return null
        val word = line.substring(0, lp).trim()
        val syllables = line.substring(lp + 1, rp).split(',').filter { it.isNotBlank() }
        if (word.isEmpty() || syllables.isEmpty()) return null
        val initials = syllables.joinToString("") { it[0].lowercaseChar().toString() }
        return word to initials
    }

    /**
     * 取标题的拼音首字母串（检索用）。规则：
     *  - 词组表最长匹配优先（多音字按词组读音，最多回看 8 字）；
     *  - 单字取词典首选音；无法识别的汉字跳过；
     *  - ASCII 字母数字原样保留（小写），其余符号（括号/空格/日文等）跳过。
     * 例：圣斗士星矢(日版) → sdsxs；超级机器人大战Z → cjjqrdzz
     */
    fun initials(context: Context, name: String): String {
        ensureLoaded(context)
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val matched = phraseAt(name, i)
            if (matched != null) {
                sb.append(matched)
                i += lastMatchLen
            } else {
                val c = name[i]
                when {
                    c in singles -> sb.append(singles[c]!!.first())
                    c.isAsciiLetterOrDigit() -> sb.append(c.lowercaseChar())
                }
                i++
            }
        }
        return sb.toString()
    }

    /** 词组匹配的命中长度（线程内单次调用序，索引构建是单协程串行，够用） */
    private var lastMatchLen = 0

    private fun phraseAt(name: String, start: Int): String? {
        val maxLen = minOf(8, name.length - start)
        for (len in maxLen downTo 2) {
            phrases.subsequenceOrNull(name, start, len)?.let {
                lastMatchLen = len
                return it
            }
        }
        return null
    }

    private fun Map<String, String>.subsequenceOrNull(s: String, off: Int, len: Int): String? =
        if (off + len <= s.length) this[s.substring(off, off + len)] else null

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')

    /** 词典是否已就绪（UI 展示"索引功能是否可用"用） */
    val ready: Boolean get() = loaded
}
