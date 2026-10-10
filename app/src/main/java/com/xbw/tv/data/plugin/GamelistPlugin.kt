package com.xbw.tv.data.plugin

import android.util.Log
import com.xbw.tv.data.net.HttpFetcher
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay

/**
 * 能解开 JS cookie 挑战的抓取器。
 *
 * 第三方源常挂在"先解一道 JS 挑战再放行"的网盘上（样本：186317.22web.org）。
 * 挑战页长这样：
 * ```html
 * <script src="/aes.js"></script><script>
 *   var a=toNumbers("f655…"), b=toNumbers("9834…"), c=toNumbers("03e9…");
 *   document.cookie="__test="+toHex(slowAES.decrypt(c,2,a,b)); location.href="…?i=2";
 * </script>
 * ```
 * `slowAES.decrypt(cipher, mode, key, iv)` 就是 **AES-CBC、不填充**：a 是密钥、
 * b 是 IV、c 是密文，解出来的字节直接 hex 当 cookie 值。
 *
 * 挑战是**每轮换一次**的：带上第 N 轮的 cookie 请求，第 N+1 轮还要再解一次，
 * 实测 2 轮才拿到正文。所以这里是循环解，不是解一次就完。
 */
object AesChallenge {

    private const val TAG = "AesChallenge"
    private const val MAX_ROUNDS = 10

    /** 429 后等这麼久再重试（服务器对挑战后紧接的请求容易限流） */
    private const val RATE_LIMIT_DELAY_MS = 1500L
    /** 解出 cookie 后、带 cookie 重试前的小停顿，模拟浏览器 reload 节奏 */
    private const val SOLVE_DELAY_MS = 700L

    /** 挑战页特征：出现 toNumbers(" 就是它 */
    fun isChallenge(body: String): Boolean = body.contains("toNumbers(")

    /**
     * 抓取 [url]，中途遇到挑战就解完重试。
     * 服务端会限流挑战后紧接的请求（HTTP 429），这里解完稍等、429 再等重试。
     * @param cookieJar 已有的 cookie，回写更新后的（同一个源复用，省掉重复握手）
     */
    suspend fun fetch(url: String, cookieJar: MutableMap<String, String>? = null): String {
        var cookie = cookieJar?.get(COOKIE)?.let { "$COOKIE=$it" }
        var rateRetries = 2
        repeat(MAX_ROUNDS) {
            val headers = buildMap<String, String> { cookie?.let { put("Cookie", it) } }
            val body = try {
                HttpFetcher.fetchText(url, headers, noCache = true)
            } catch (e: HttpFetcher.FetchException) {
                if (e.httpCode == 429 && rateRetries > 0) {
                    rateRetries--
                    Log.w(TAG, "rate-limited(429) for $url, wait ${RATE_LIMIT_DELAY_MS}ms then retry")
                    delay(RATE_LIMIT_DELAY_MS)
                    return@repeat
                }
                throw e
            }
            if (!isChallenge(body)) {
                if (body.isBlank()) {
                    Log.w(TAG, "fetch $url -> EMPTY BODY (cookie=${cookie?.substringAfter("=")?.take(8)})")
                } else {
                    Log.i(TAG, "fetch $url -> ${body.length}B preview=${body.take(96)}")
                }
                return body
            }
            val solved = solve(body) ?: throw IllegalStateException("挑战页无法解析")
            val prev = cookie?.substringAfter('=')
            if (solved == prev) {
                // cookie 没变说明服务器在回同一道题（实测 PC 上第 2 轮就放行，
                // 盒子这边偶尔会把同一道题连发好几轮），换新 cookie 只会原地打转
                Log.w(TAG, "challenge for $url did not advance, giving up")
                throw IllegalStateException("挑战没有进展")
            }
            cookie = "$COOKIE=$solved"
            cookieJar?.set(COOKIE, solved)
            Log.i(TAG, "solved challenge for $url, cookie=$solved")
            if (rateRetries < 2) rateRetries = 2   // 重新开始，429 后还能重试
            delay(SOLVE_DELAY_MS)
        }
        throw IllegalStateException("挑战轮数超过 $MAX_ROUNDS")
    }

    /**
     * 从挑战页解出 cookie 值（hex 字符串）。
     *
     * 按出现顺序取三个十六进制串：key、iv、cipher。脚本本身把顺序写死了
     * （`decrypt(c,2,a,b)`），所以取序比匹配变量名更稳。
     */
    fun solve(challengeHtml: String): String? {
        val hexes = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)")
            .findAll(challengeHtml)
            .map { it.groupValues[1].lowercase() }
            .take(3)
            .toList()
        if (hexes.size < 3) return null
        val (keyHex, ivHex, cipherHex) = Triple(hexes[0], hexes[1], hexes[2])
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(hexToBytes(keyHex), "AES"),
                IvParameterSpec(hexToBytes(ivHex))
            )
            val plain = cipher.doFinal(hexToBytes(cipherHex))
            plain.joinToString("") { "%02x".format(it) }
        }.onFailure { Log.w(TAG, "solve failed: ${it.message}") }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val padded = if (clean.length % 2 == 1) "0$clean" else clean
        return ByteArray(padded.length / 2) {
            padded.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** 挑战后的会话 cookie 名（资源直链有时也要带它才回 200） */
    const val COOKIE = "__test"
}

/**
 * gamelist.xml 的极简读取器。
 *
 * 刻意不用 DOM/反射式 XML 框架：这类清单动辄几百 KB，用 [XmlPullParser] 流式过一遍
 * 内存占用低，而且 libxml 遇到站点里的非法实体不会像某些 DOM 实现那样直接抛异常。
 * 只需认识 `<game>` 下的 path/name/sortable/image/video/lang 这几个字段，其余
 * （playcount、lastplayed、gametime、region）跳过。
 */
object GamelistParser {

    private const val TAG = "Gamelist"

    /** 一条原始记录 */
    data class Entry(
        val path: String,
        val name: String,
        val sortname: String,
        val image: String,
        val video: String,
        val lang: String
    )

    fun parse(xml: String): List<Entry> {
        val out = ArrayList<Entry>(256)
        var inGame = false
        val path = StringBuilder()
        val name = StringBuilder()
        val sort = StringBuilder()
        val image = StringBuilder()
        val video = StringBuilder()
        val lang = StringBuilder()
        var cur = ""
        // 用 XmlPullParserFactory 而不是 android.util.Xml：后者在 JVM 单测里是
        // 未实现 stub，工厂在设备（JDK 内置实现）和单测（kxml2）上都能跑
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag.equals("game", true)) {
                        inGame = true
                        path.setLength(0); name.setLength(0); sort.setLength(0)
                        image.setLength(0); video.setLength(0); lang.setLength(0)
                    } else if (inGame) cur = tag.lowercase()
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> if (inGame) when (cur) {
                    "path" -> path.append(parser.text)
                    "name" -> name.append(parser.text)
                    "sortname" -> sort.append(parser.text)
                    "image" -> image.append(parser.text)
                    "video" -> video.append(parser.text)
                    "lang" -> lang.append(parser.text)
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name.equals("game", true)) {
                        inGame = false
                        val p = path.toString().trim()
                        if (p.isNotEmpty()) {
                            out += Entry(
                                path = p,
                                name = name.toString().trim(),
                                sortname = sort.toString().trim(),
                                image = image.toString().trim(),
                                video = video.toString().trim(),
                                lang = lang.toString().trim()
                            )
                        }
                    }
                    cur = ""
                }
            }
            event = parser.next()
        }
        Log.i(TAG, "parsed ${out.size} entries")
        return out
    }

    /** `<name>M-魔法总动员…[mfzdyh]</name>` → ("魔法总动员…", "mfzdyh") */
    fun splitName(raw: String): Pair<String, String> {
        var title = raw.replace(Regex("\\[[a-z0-9]+]"), "").trim()
        // 站点把排序字母塞在名字前面："M-xxx" / "Q-xxx"
        title = Regex("^[A-Za-z]-(?=\\S)").replace(title, "")
        return title to initialsOf(raw)
    }

    /** 只取站点塞在 `<name>` 里的 `[拼音首字母]` 标记（`<sortname>` 通常没有这个标记） */
    fun initialsOf(raw: String): String =
        Regex("\\[([a-z0-9]+)]").find(raw)?.groupValues?.get(1).orEmpty()

    /** `./01动作/x.nes` → "动作"（去掉数字前缀，当题材标签用） */
    fun genreOf(path: String): String {
        val clean = path.removePrefix("./")
        val dir = clean.substringBeforeLast('/', "")
        if (dir.isEmpty()) return ""
        return Regex("^\\d+").replace(dir, "").trim()
    }
}