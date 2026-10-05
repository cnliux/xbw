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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 一个可安装的新版本 */
data class UpdateInfo(
    val tag: String,
    val versionCode: Int,
    val notes: String,
    /** 已经挑好的最快下载源（GitHub 直连或镜像），见 [UpdateChecker.pickSource] */
    val downloadUrl: String,
    val sizeBytes: Long
)

/**
 * ▶ 自动升级：进游戏时/设置页检查 GitHub 最新 Release，下载 APK 并拉起系统安装。
 *
 * 版本号规则（与 .github/workflows/build.yml 对齐）：Release tag = `v<run_number>`，
 * Gradle 用同一个数字做 versionCode/versionName（`-PbuildNo=N`），所以
 * "比大小"只需比 tag 里的数字。
 *
 * 下载源策略：同一个 Release 资产在 GitHub 直连与几个镜像上是同一份文件，
 * 先并发 HEAD 探活，取最快可用者；探活全灭就退到 GitHub 直连真下载（失败再逐个试）。
 * 国内盒子常见"GitHub 能访问但极慢/超时"，所以镜像自动选择是必需的，不是锦上添花。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    const val OWNER = "cnliux"
    const val REPO = "xbw"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val ASSET_NAME = "app-release.apk"

    /** 镜像前缀（拼在 `/{owner}/{repo}/releases/download/{tag}/{asset}` 前面） */
    private val SOURCES = listOf(
        "" to "https://github.com",
        "https://ghfast.top/https://github.com" to "ghfast",
        "https://gh-proxy.com/https://github.com" to "gh-proxy",
        "https://ghproxy.net/https://github.com" to "ghproxy.net"
    )

    val currentVersion: Int get() = BuildConfig.VERSION_CODE

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    /**
     * 查最新 Release。@return null 表示无需升级 / 已是最新；
     * 网络失败也返回 null（调用方按"没更新"处理，绝不打断用户）。
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val latest = fetchLatest() ?: return@withContext null
        if (latest.versionCode <= currentVersion) return@withContext null
        val url = pickSource(latest.tag)
        Log.i(TAG, "update available: ${latest.tag} (cur=$currentVersion) via $url")
        UpdateInfo(latest.tag, latest.versionCode, latest.notes, url, latest.sizeBytes)
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
            // 不带 Accept 头，让 OkHttp 跟完 302，最终 URL 形如 .../releases/tag/v12
            val finalUrl = HttpFetcher.fetchRedirectTarget(
                "https://github.com/$OWNER/$REPO/releases/latest"
            )
            Regex("/releases/tag/([^/?#]+)$").find(finalUrl)?.groupValues?.get(1)
        }.getOrNull() ?: return null
        return Latest(tag, versionOf(tag), "", 0L)
    }

    /** `v12` / `12` / `build-12` → 12；解析不出返回 0（视为"不比"） */
    fun versionOf(tag: String): Int =
        Regex("(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * 自动选下载源：并发 HEAD 探活，返回最快可用者的直链。
     * 探不出长度/全超时 → 回落到 GitHub 直连（真下载阶段还有重试兜底）。
     */
    private suspend fun pickSource(tag: String): String {
        val githubUrl = assetUrl(tag, "")
        val timed = measureTime { HttpFetcher.headContentLength(githubUrl) }
        if (timed.first >= 0) {
            Log.i(TAG, "source github (${timed.second}ms)")
            return githubUrl
        }
        val mirrors = SOURCES.drop(1)
        var best: Pair<String, Long>? = null
        mirrors.forEach { (prefix, name) ->
            val (len, ms) = measureTime { HttpFetcher.headContentLength(assetUrl(tag, prefix)) }
            if (len >= 0 && (best == null || ms < best!!.second)) {
                best = assetUrl(tag, prefix) to ms
                Log.i(TAG, "mirror $name ${ms}ms len=$len")
            }
        }
        return best?.first ?: githubUrl
    }

    private suspend fun measureTime(block: suspend () -> Long): Pair<Long, Long> {
        val t0 = System.currentTimeMillis()
        val len = block()
        return len to (System.currentTimeMillis() - t0)
    }

    private fun assetUrl(tag: String, prefix: String): String =
        if (prefix.isEmpty()) "https://github.com/$OWNER/$REPO/releases/download/$tag/$ASSET_NAME"
        else "$prefix/$OWNER/$REPO/releases/download/$tag/$ASSET_NAME"

    // ------------------------------------------------------------------
    // 下载 + 安装
    // ------------------------------------------------------------------

    private fun apkDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * 下载并弹出系统安装器（失败只提示，不抛）。
     * @param onProgress 0..100；size 未知时传 -1
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
            val ok = runCatching {
                HttpFetcher.downloadToFile(
                    info.downloadUrl, dest,
                    onProgress = { done, total ->
                        activity.runOnUiThread {
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            binding.updateProgress.isIndeterminate = pct < 0
                            if (pct >= 0) binding.updateProgress.progress = pct
                            binding.updateStatus.text = activity.getString(
                                if (pct >= 0) R.string.update_downloading_fmt else R.string.update_downloading
                            ) + if (pct >= 0) " $pct%" else ""
                        }
                    }
                )
            }.isSuccess
            if (!ok) {
                dest.delete()
                dialog.dismiss()
                activity.runOnUiThread {
                    toast(activity, activity.getString(R.string.update_download_failed))
                }
                return@launch
            }
            dialog.dismiss()
            install(activity, dest)
        }
    }

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
