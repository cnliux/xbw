package com.xbw.tv.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 网络层：抓取 yikm.net 原始 HTML。
 *
 * 关键点：
 *  - **UA 伪装成桌面 Chrome**。站点 PC 端与移动端的卡片 DOM 不完全一致，
 *    且移动端会插入"添加到桌面"提示；桌面 UA 拿到的 DOM 已被 SelectorCheck 验证。
 *  - **重试 3 次 + 指数退避**（方案 3.4 要求）。
 *  - OkHttp 自带磁盘缓存（10MB，URL 级 5 分钟内命中），列表页翻页回退时秒开。
 *  - HTTP 错误/解析错误抛出 [FetchException]，UI 可区分网络问题与改版问题。
 */
object HttpFetcher {

    /** 桌面 Chrome UA。必须含 "Windows NT"/"Macintosh"，否则站点判定为移动端 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

    const val MAX_RETRY = 3

    class FetchException(
        val httpCode: Int = -1,
        val cause0: Throwable? = null,
        override val message: String
    ) : IOException(message, cause0)

    @Volatile
    private var client: OkHttpClient? = null

    fun init(cacheDir: java.io.File) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .cookieJar(CookieJar.NO_COOKIES)
                // HTTP 层缓存：5 分钟内同 URL 直接命中本地，省流量提速
                .cache(Cache(java.io.File(cacheDir, "okhttp"), 10L * 1024 * 1024))
                .build()
        }
    }

    private fun client(): OkHttpClient =
        client ?: throw IllegalStateException("HttpFetcher 未初始化，请在 Application 中调用 init()")

    /**
     * 带 UA 与重试的 GET，返回文本。
     *
     * @param allowEmpty 允许空响应体。金手指接口（/cheat?id=）对"该游戏没有金手指"
     *   就是返回空体，这时空体是正常结果而不是错误。
     */
    suspend fun fetchHtml(url: String, allowEmpty: Boolean = false): String = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(MAX_RETRY) { attempt ->
            try {
                return@withContext execute(url, allowEmpty)
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_RETRY - 1) {
                    // 指数退避：600ms → 1.2s → 2.4s
                    delay(600L * (1L shl attempt))
                }
            }
        }
        throw FetchException(
            httpCode = (lastError as? FetchException)?.httpCode ?: -1,
            cause0 = lastError,
            message = "请求失败（已重试 $MAX_RETRY 次）：${lastError?.message}"
        )
    }

    private fun execute(url: String, allowEmpty: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", SiteConfig.BASE_URL + "/")
            .build()

        client().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw FetchException(resp.code, null, "HTTP ${resp.code} ${resp.message}")
            }
            val body = resp.body
            if (body == null) throw FetchException(resp.code, null, "响应体为空")
            // 站点 charset=utf-8；Jsoup 自己会处理 meta，这里直接 string()
            val text = body.string()
            if (text.isBlank() && !allowEmpty) throw FetchException(resp.code, null, "响应内容为空")
            return text
        }
    }

    /**
     * 流式下载文件到本地（先写 .part 再由调用方改名），带重试。
     * 用于 ROM 直链（file.1990i.com）等大文件；ROM ≤1MB，内存无压力但仍走流式。
     */
    suspend fun downloadToFile(url: String, dest: File, referer: String? = null): File =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            repeat(MAX_RETRY) { attempt ->
                try {
                    return@withContext downloadOnce(url, dest, referer)
                } catch (e: IOException) {
                    lastError = e
                    dest.delete()
                    if (attempt < MAX_RETRY - 1) delay(600L * (1L shl attempt))
                }
            }
            throw FetchException(
                httpCode = (lastError as? FetchException)?.httpCode ?: -1,
                cause0 = lastError,
                message = "下载失败（已重试 $MAX_RETRY 次）：${lastError?.message}"
            )
        }

    private fun downloadOnce(url: String, dest: File, referer: String?): File {
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
        referer?.let { rb.header("Referer", it) }
        client().newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw FetchException(resp.code, null, "HTTP ${resp.code} ${resp.message}")
            }
            val body = resp.body ?: throw FetchException(resp.code, null, "响应体为空")
            dest.parentFile?.mkdirs()
            body.byteStream().use { ins ->
                dest.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) }
            }
            if (dest.length() == 0L) throw FetchException(resp.code, null, "下载内容为空")
            return dest
        }
    }

    /** 图片可用性预判（Glide 兜底用，不发请求也可以） */
    fun isProbablyImage(url: String): Boolean =
        url.startsWith("http") && !url.contains(SiteConfig.NO_PIC_MARK)

    /** 诊断模式：返回状态码 + 内容长度，供"站点诊断"页展示 */
    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val html = execute(url)
            val cards = Regex("card-blog").findAll(html).count()
            ProbeResult(true, 200, html.length, cards, null, html.take(600))
        } catch (e: Exception) {
            ProbeResult(false, (e as? FetchException)?.httpCode ?: -1, 0, 0, e.message, "")
        }
    }

    data class ProbeResult(
        val ok: Boolean,
        val code: Int,
        val bodyLength: Int,
        val cardMentions: Int,
        val error: String?,
        val head: String
    )
}
