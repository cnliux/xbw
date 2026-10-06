package com.xbw.tv.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CacheControl
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

    const val MAX_RETRY = 6

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
     * @param onProgress 已下载字节 / 总字节（总长未知时为 -1），在 IO 线程回调
     */
    /**
     * 断点续传下载：失败重试时**接着上次的位置继续**，不从头再来。
     *
     * 电视盒子实测：38MB 的包在 GitHub 上要几分钟，中间停顿超过 [readTimeout] 就会断。
     * 原来每次重试都 `dest.delete()` 从零开始，慢速网络下永远下不完（实测连挂 5 个源）。
     * 现在用 `Range: bytes=<已有字节>-` 续传，服务端返回 206 就接着写，
     * 返回 200（不支持续传）或 416（本地已完整）时分别按"重头下"/"直接完成"处理。
     *
     * @param onProgress 回调 (已下载, 总大小)；续传时已下载量从文件长度起算
     */
    suspend fun downloadToFile(
        url: String,
        dest: File,
        referer: String? = null,
        onProgress: ((done: Long, total: Long) -> Unit)? = null
    ): File =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            repeat(MAX_RETRY) { attempt ->
                try {
                    return@withContext downloadOnce(url, dest, referer, onProgress)
                } catch (e: IOException) {
                    lastError = e
                    // 不删 dest：留给下一次 Range 续传
                    if (attempt < MAX_RETRY - 1) delay(600L * (1L shl attempt))
                }
            }
            dest.delete()
            throw FetchException(
                httpCode = (lastError as? FetchException)?.httpCode ?: -1,
                cause0 = lastError,
                message = "下载失败（已重试 $MAX_RETRY 次）：${lastError?.message}"
            )
        }

    private fun downloadOnce(
        url: String, dest: File, referer: String?, onProgress: ((Long, Long) -> Unit)?,
        timeoutMs: Long = 20_000L
    ): File {
        dest.parentFile?.mkdirs()
        val resumeFrom = if (dest.isFile && dest.length() > 0) dest.length() else 0L
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
        if (resumeFrom > 0) rb.header("Range", "bytes=$resumeFrom-")
        referer?.let { rb.header("Referer", it) }
        // 大文件允许更长的读超时：盒子网络慢但连接是活的，宁可等也不要从头再来
        val call = client().newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(rb.build())
        call.execute().use { resp ->
            if (resp.code == 416) return dest            // 本地已完整（Range 越界）
            if (!resp.isSuccessful && resp.code != 206) {
                throw FetchException(resp.code, null, "HTTP ${resp.code} ${resp.message}")
            }
            val body = resp.body ?: throw FetchException(resp.code, null, "响应体为空")
            // 206 的 body 只含剩余部分，总大小要从 Content-Range 的尾部取
            val total = when {
                resp.code == 206 ->
                    resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: (resumeFrom + body.contentLength())
                else -> body.contentLength()
            }
            val append = resp.code == 206 && resumeFrom > 0
            if (!append) dest.delete()
            var done = if (append) resumeFrom else 0L
            onProgress?.invoke(done, total)
            body.byteStream().use { ins ->
                java.io.FileOutputStream(dest, append).use { outs ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
            if (dest.length() == 0L) throw FetchException(resp.code, null, "下载内容为空")
            return dest
        }
    }

    /** 取文本（默认 UA/Referer 同抓页面），升级检查读 GitHub API 用 */
    suspend fun fetchText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        noCache: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (noCache) {
            // 绕开 OkHttp 磁盘缓存：JS 挑战页每轮都不一样，
            // 缓存住挑战页会导致 cookie 反复解出同一个值、永远进不去正文
            rb.cacheControl(CacheControl.FORCE_NETWORK)
            rb.header("Cache-Control", "no-cache")
        }
        client().newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw FetchException(resp.code, null, "HTTP ${resp.code}")
            resp.body?.string() ?: throw FetchException(resp.code, null, "响应体为空")
        }
    }

    /**
     * 只跟到重定向为止，返回**最终 URL**（不读 body）。
     * 用于从 `releases/latest` 的 302 里抠出 tag，避开 GitHub API 的匿名限流。
     */
    suspend fun fetchRedirectTarget(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "text/html").build()
        client().newCall(req).execute().use { it.request.url.toString() }
    }

    /**
     * 下载源探活：返回 Content-Length，失败（超时/404/DNS 污染）返回 -1。
     *
     * 升级时用它给每个 CDN 测延迟好挑最快的，所以有两个细节：
     *  1. 优先用 `Range: bytes=0-0` 的 **GET** 而不是 HEAD——不少 GitHub 镜像对 HEAD
     *     直接回 405（实测 ghproxy.cn 就是），GET + Range 才是各家都支持的探法；
     *  2. 206 响应里总长在 `Content-Range` 的尾部，`Content-Length` 只有 1，不能当大小用。
     * 两者都拿不到时回退 HEAD 再试一次。
     */
    suspend fun headContentLength(url: String): Long = withContext(Dispatchers.IO) {
        val probe = client().newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val ranged = runCatching {
            val req = Request.Builder().url(url).get()
                .header("User-Agent", UA)
                .header("Range", "bytes=0-0")
                .build()
            probe.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) -1L
                else resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 1 }
                    ?: -1L
            }
        }.getOrDefault(-1L)
        if (ranged > 0) return@withContext ranged

        try {
            val req = Request.Builder().url(url).method("HEAD", null)
                .header("User-Agent", UA).build()
            probe.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) -1L else resp.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            -1L
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
