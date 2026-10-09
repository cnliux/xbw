package com.xbw.tv.data.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.xbw.tv.BuildConfig
import com.xbw.tv.R
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.databinding.DialogUpdateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 一个可安装的新版本 */
data class UpdateInfo(
    val tag: String,
    val versionCode: Int,
    val notes: String,
    /**
     * 候选下载源，**按实测速度从快到慢排好序**（不是固定偏好）。
     * 第一个是最快的，下载失败就顺位换下一个，见 [UpdateChecker.pickSources]。
     */
    val sources: List<UpdateChecker.Source>,
    val sizeBytes: Long
) {
    /** 最快那个源的直链，仅用于日志/展示 */
    val downloadUrl: String get() = sources.first().url(tag)
}

/**
 * ▶ 自动升级：进游戏时/设置页检查 GitHub 最新 Release，下载 APK 并拉起系统安装。
 *
 * 版本号规则：语义化 `v<major>.<minor>.<patch>`，首个正式版从 **v0.0.1** 起，
 * CI 每次发 Release 自动 patch+1（与 app/build.gradle 的 versionCode 派生算法一致：
 * `0.0.1→1, 0.0.14→14, 1.2.3→1002003`），所以"比大小"两边用的是同一套算法。
 *
 * 下载源策略：同一个 Release 资产在 GitHub 直连与各镜像上是同一份文件。
 * **每个源都并发 HEAD 探活，按实测响应时间排序，最快的先下**；不写死任何偏好，
 * 因为"哪个 CDN 快"完全取决于用户当前网络（直连/家宽/校园网/移动网络各不相同），
 * 盒子这类设备直连 GitHub 常年超时，只能靠镜像。
 * 下载阶段还会顺位换源重试，并校验落盘文件确实是 APK（镜像偶尔返回错误页却带 200）。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    const val OWNER = "cnliux"
    const val REPO = "xbw"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val ASSET_NAME = "app-release.apk"

    /** 单个探活的硬超时：探源不能拖慢"检查更新"，慢的源直接出局 */
    private const val PROBE_TIMEOUT_MS = 12_000L

    /**
     * 第一个源应答后再多等这么久，看有没有更快的。
     * 电视盒子实测：5 个源全部要 5s 以上才应答，等齐所有源等于没选；
     * 所以"谁先应答谁当主力"，只给后来的一个短暂翻盘机会。
     */
    private const val PROBE_GRACE_MS = 1_500L

    /** 候选下载源。[prefix] 拼在 `/$OWNER/$REPO/releases/download/$tag/$ASSET_NAME` 前面 */
    data class Source(val name: String, val prefix: String) {
        fun url(tag: String): String = buildString {
            append(if (prefix.isEmpty()) "https://github.com" else prefix)
            append("/").append(OWNER).append("/").append(REPO)
            append("/releases/download/").append(tag).append("/").append(ASSET_NAME)
        }
    }

    /**
     * 直连 + 常见 GitHub 镜像。顺序无所谓：真正选谁由 [pickSources] 实测决定，
     * 这里只影响"探活全灭"时的兜底顺位。
     */
    val SOURCES = listOf(
        Source("GitHub", ""),
        Source("wget.la", "https://wget.la/https://github.com"),
        Source("idayer", "https://gh.idayer.com/https://github.com"),
        Source("boki", "https://github.boki.moe/https://github.com"),
        Source("gh-proxy.org", "https://cdn.gh-proxy.org/https://github.com"),
        Source("h233", "https://gh.h233.eu.org/https://github.com"),
        Source("gh-proxy", "https://gh-proxy.com/https://github.com"),
        Source("ghfast", "https://ghfast.top/https://github.com"),
        Source("ghproxy.net", "https://ghproxy.net/https://github.com"),
        Source("ghproxy.cn", "https://ghproxy.cn/https://github.com")
    )

    val currentVersion: Int get() = BuildConfig.VERSION_CODE

    /**
     * 最近一次 [check] 是否真的连上了 GitHub。
     * `check()` 网络失败也返回 null，所以靠这个标志把"已是最新"和"根本没查成"分开，
     * 否则设置页会在断网时骗用户说已经最新。
     */
    @Volatile
    var lastCheckReachable: Boolean = false
        private set

    /** 便捷读法（给 UI 用） */
    fun reachable(): Boolean = lastCheckReachable

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    /**
     * 查最新 Release。@return null 表示无需升级 / 已是最新；
     * 网络失败也返回 null（调用方按"没更新"处理，绝不打断用户），
     * 失败与否看 [reachable]。
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        lastCheckReachable = false
        val latest = fetchLatest() ?: return@withContext null
        lastCheckReachable = true
        if (latest.versionCode <= currentVersion) return@withContext null
        val sources = pickSources(latest.tag)
        Log.i(TAG, "update available: ${latest.tag} (cur=$currentVersion) via ${sources.first().name}")
        UpdateInfo(latest.tag, latest.versionCode, latest.notes, sources, latest.sizeBytes)
    }

    private class Latest(val tag: String, val versionCode: Int, val notes: String, val sizeBytes: Long)

    /** 先走 API（能拿到 release notes 与资产大小），失败退回 latest 页 302 里的 tag */
    private suspend fun fetchLatest(): Latest? {
        val api = runCatching {
            val text = HttpFetcher.fetchText(
                API_LATEST,
                headers = mapOf("Accept" to "application/vnd.github+json")
            )
            val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            val notes = Regex("\"body\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"")
                .find(text)?.groupValues?.get(1).orEmpty()
            val size = Regex("\"name\"\\s*:\\s*\"$ASSET_NAME\".*?\"size\"\\s*:\\s*(\\d+)", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            tag?.let { Latest(it, versionOf(it), notes.replace("\\r\\n", "\n").trim(), size) }
        }.getOrNull()
        if (api != null) return api

        val tag = runCatching {
            // 不带 Accept 头，让 OkHttp 跟完 302，最终 URL 形如 .../releases/tag/v0.0.1
            val finalUrl = HttpFetcher.fetchRedirectTarget(
                "https://github.com/$OWNER/$REPO/releases/latest"
            )
            Regex("/releases/tag/([^/?#]+)$").find(finalUrl)?.groupValues?.get(1)
        }.getOrNull() ?: return null
        return Latest(tag, versionOf(tag), "", 0L)
    }

    /**
     * 版本号 → 可比较的整数，必须与 app/build.gradle 的派生算法一致：
     * `v0.0.14` → 14（== versionCode），`v1.2.3` → 1002003。
     * 兼容旧的 `v13` / `build-12` 单数字 tag（→ 13 / 12）。
     */
    fun versionOf(tag: String): Int {
        val semver = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(tag)
        if (semver != null) {
            val (major, minor, patch) = semver.destructured
            return major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
        }
        return Regex("(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ------------------------------------------------------------------
    // 选源：并发探活 → 按实测速度排序
    // ------------------------------------------------------------------

    /**
     * 并发探活所有源，返回**按实测响应时间从快到慢**的候选列表。
     *
     * 不做"直连优先"这种固定偏好：一个源只要探活成功就算可用，顺序完全由当次实测决定。
     * 实现上是"赛跑"而不是"等齐"：第一个源应答后只再等 [PROBE_GRACE_MS] 看有没有更快的，
     * 之后就按已有数据排序；探不出来的源排在最后当兜底（真下载时仍可能成功，
     * 例如不支持 Range/HEAD 的镜像）。
     */
    private suspend fun pickSources(tag: String): List<Source> = coroutineScope {
        val alive = Channel<Pair<Source, Long>>(Channel.UNLIMITED)
        val probes = SOURCES.map { src ->
            launch(Dispatchers.IO) {
                val ms = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    val t0 = System.currentTimeMillis()
                    val len = runCatching { HttpFetcher.headContentLength(src.url(tag)) }
                        .getOrDefault(-1L)
                    if (len < 0) null else System.currentTimeMillis() - t0
                }
                if (ms == null) Log.i(TAG, "source ${src.name} slow/dead (>${PROBE_TIMEOUT_MS}ms)")
                else alive.send(src to ms)
            }
        }
        val collected = mutableListOf<Pair<Source, Long>>()
        val first = withTimeoutOrNull(PROBE_TIMEOUT_MS + PROBE_GRACE_MS) { alive.receive() }
        if (first != null) {
            collected += first
            // 宽限期内谁先到就收，收满或到期即止
            withTimeoutOrNull(PROBE_GRACE_MS) {
                while (true) collected += alive.receive()
            }
        }
        probes.forEach { it.cancel() }
        alive.close()
        Log.i(TAG, "source order: " + (collected.sortedBy { it.second }.joinToString { "${it.first.name} ${it.second}ms" }
            .ifEmpty { "none alive, fallback to declaration order" }))
        rankSources(collected, SOURCES)
    }

    /**
     * 纯逻辑：把探活结果排成下载顺位。
     * 通畅的源按毫秒数升序在前，探不出来的按原声明顺序垫底（保留兜底价值）。
     * 抽成独立函数是为了能上 JVM 单测——选源逻辑错了用户只会觉得"下载慢"。
     */
    internal fun rankSources(
        alive: List<Pair<Source, Long>>,
        all: List<Source> = SOURCES
    ): List<Source> {
        val fast = alive.sortedBy { it.second }.map { it.first }
        val slow = all.filter { src -> fast.none { it == src } }
        return fast + slow
    }

    // ------------------------------------------------------------------
    // 下载 + 安装
    // ------------------------------------------------------------------

    private fun apkDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * 下载并弹出系统安装器（失败只提示，不抛）。
     *
     * 按 [UpdateInfo.sources] 的实测顺位依次尝试：某个 CDN 超时/断流就换下一个，
     * 任何一个源完整下完且校验是 APK 就装。
     */
    fun downloadAndInstall(activity: androidx.activity.ComponentActivity, info: UpdateInfo) {
        val binding = DialogUpdateBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .setCancelable(false)
            .create()
        dialog.show()

        activity.lifecycleScope.launch {
            val dest = File(apkDir(activity), "app-release-${info.tag}.apk")
            var installedFrom: String? = null
            for ((index, src) in info.sources.withIndex()) {
                binding.updateStatus.text = activity.getString(
                    R.string.update_downloading_from_fmt, src.name
                )
                val ok = runCatching {
                    HttpFetcher.downloadToFile(
                        src.url(info.tag), dest,
                        onProgress = { done, total ->
                            activity.runOnUiThread {
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                binding.updateProgress.isIndeterminate = pct < 0
                                if (pct >= 0) binding.updateProgress.progress = pct
                                binding.updateStatus.text = activity.getString(
                                    R.string.update_downloading_from_fmt, src.name
                                ) + if (pct >= 0) " $pct%" else ""
                            }
                        }
                    )
                }.isSuccess && isCompleteApk(dest, info.sizeBytes)
                if (ok) {
                    installedFrom = src.name
                    break
                }
                Log.w(
                    TAG,
                    "source ${src.name} unusable (want=${info.sizeBytes} got=${dest.length()}), trying next"
                )
                // 换源时清掉半截文件：断点续传是按字节偏移的，
                // 不同 CDN 的响应体长度未必一致，接着写会拼出坏包
                dest.delete()
                dest.delete()
                if (index == 0) binding.updateProgress.progress = 0
            }
            if (installedFrom == null) {
                dialog.dismiss()
                activity.runOnUiThread {
                    toast(activity, activity.getString(R.string.update_download_failed))
                }
                return@launch
            }
            Log.i(TAG, "apk ready from $installedFrom size=${dest.length()}")
            dialog.dismiss()
            install(activity, dest)
        }
    }

    /**
     * 镜像偶尔在 200 响应里塞 HTML 错误页或截断内容；这种文件交给系统安装器只会得到
     * 一句"解析失败"，还不如当场换源重下。所以既看 APK 魔数，也对齐 API 报的资产大小。
     *
     * @param expectedSize API 给的资产字节数；0 = 未知（走了 fallback 查 tag），跳过大小校验
     */
    internal fun isCompleteApk(file: File, expectedSize: Long = 0L): Boolean {
        if (!looksLikeApk(file)) return false
        return expectedSize <= 0L || file.length() == expectedSize
    }

    /**
     * 镜像偶尔在 200 响应里塞 HTML 错误页；这种文件交给系统安装器只会得到一句
     * "解析失败"，还不如当场换源重下。
     */
    internal fun looksLikeApk(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_APK_BYTES) return false
        return runCatching {
            file.inputStream().use { stream ->
                val magic = ByteArray(2)
                if (stream.read(magic) != 2) return false
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()   // "PK"
            }
        }.getOrDefault(false)
    }

    /** 正式包 30MB+，1MB 足够挡住错误页又不会误杀小包 */
    private const val MIN_APK_BYTES = 1024L * 1024L

    /** FileProvider 直出 + 系统安装器；未授权"安装未知应用"时先跳授权页 */
    fun install(activity: androidx.activity.ComponentActivity, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apk
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_need_permission, Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${activity.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, activity.getString(R.string.update_no_installer)) }
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
