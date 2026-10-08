package com.xbw.tv.data.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * ▶ GitHub raw 镜像测速器：BIOS / 金手指 ini 等小文件下载共用。
 *
 * 启动时对所有镜像**并发 HEAD 探活**，按实测响应时间排好序缓存；
 * 后续下载按这个顺位排队竞速（[ranked]），当前网络最快的镜像最先完成。
 * 盒子网络对各家 CDN 快慢差异极大（同 UpdateChecker 的实测经验），
 * 写死顺序不如每次开机实测一次。
 */
object CdnPicker {

    private const val TAG = "CdnPicker"

    /** 探活硬超时：慢源直接出局，不拖测速 */
    private const val PROBE_TIMEOUT_MS = 8_000L

    /** 第一个应答后的宽限期：给稍慢但可用的源一个进榜机会 */
    private const val GRACE_MS = 2_000L

    /** 全部候选镜像（空串=GitHub raw 直连；国内多数场景镜像更快） */
    val MIRRORS = listOf(
        "https://wget.la/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.cn/",
        "https://github.moeyy.xyz/",
        "https://ghproxy.cc/",
        "https://gh.91hai.cn/",
        ""
    )

    /** 探活用的样本文件（自建 BIOS 库里的小文件，各镜像同源） */
    private const val PROBE_SAMPLE =
        "https://raw.githubusercontent.com/cnliux/xbw/master/fbneo/pgm.zip"

    private val ranked = AtomicReference<List<String>?>(null)

    /** 启动预热：后台测一轮速。失败/超时不写缓存（下载时按默认顺位兜底） */
    suspend fun warmup() {
        val results = coroutineScope {
            val alive = Channel<Pair<String, Long>>(MIRRORS.size)
            val probes = MIRRORS.map { prefix ->
                launch(Dispatchers.IO) {
                    val url = prefix + PROBE_SAMPLE
                    val ms = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        val t0 = System.currentTimeMillis()
                        val len = runCatching { HttpFetcher.headContentLength(url) }
                            .getOrDefault(-1L)
                        if (len < 0) null else System.currentTimeMillis() - t0
                    }
                    if (ms != null) alive.send(prefix to ms)
                    else Log.i(TAG, "mirror dead/slow: ${prefix.ifEmpty { "direct" }}")
                }
            }
            val collected = mutableListOf<Pair<String, Long>>()
            val first = withTimeoutOrNull(PROBE_TIMEOUT_MS + GRACE_MS) { alive.receive() }
            if (first != null) {
                collected += first
                withTimeoutOrNull(GRACE_MS) {
                    while (true) collected += alive.receive()
                }
            }
            probes.forEach { it.cancel() }
            alive.close()
            collected.sortBy { it.second }
            collected.map { it.first }
        }
        if (results.isNotEmpty()) {
            ranked.set(results)
            Log.i(TAG, "cdn ranked: ${results.joinToString { it.ifEmpty { "direct" } }}")
        }
    }

    /**
     * 当前网络下的镜像顺位：测过速用实测结果（未上榜的补到队尾），
     * 没测过（启动测速失败/还没跑完）用默认顺序。
     */
    fun ranked(): List<String> {
        val r = ranked.get() ?: return MIRRORS
        return r + MIRRORS.filterNot { it in r }
    }
}
