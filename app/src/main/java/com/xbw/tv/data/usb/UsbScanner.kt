package com.xbw.tv.data.usb

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import com.xbw.tv.core.CoreRouter
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.model.GameItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * U盘/外置存储自动扫描。
 *
 * 盒子插上装 ROM 的 U盘后，把它挂载的卷里常见扩展名的文件扫进来，
 * 聚合进「U盘游戏」分类（[GameCategory.USB]）。约定不写错：
 *  - 不递归太深（[MAX_DEPTH]）、不扫隐藏目录、不跟符号链接绕圈；
 *  - 条目 id 用「usb-<绝对路径 hash>」，重进收藏/最近记录也能按路径找回；
 *  - 扫出来的平台由扩展名推断（[platformOf]），进核心直接用 [CoreRouter.coreForPlatform]
 *    选的 libretro 核心，跟站内游戏一个入口。
 *
 * 目录来源两层都试：StorageManager 的 removable+已挂载卷（API 24+ 能列出来），
 * 以及 /storage 下的挂载根目录（SD/部分盒子的 U盘不在 removable 列表里）。
 * 老系统（Android 7 盒子）需要 READ/WRITE_EXTERNAL_STORAGE 运行时权限，
 * 未授权时扫不到任何东西，会让分类显示为空 —— 空结果时 UI 会带一句“检查存储权限”。
 */
object UsbScanner {

    private const val TAG = "UsbScanner"
    private const val MAX_DEPTH = 24
    private const val MAX_ENTRIES = 2000

    /** 扩展名 → 平台 key（只收录有原生核心的五类，[GameCategory.playable] 一致） */
    private val EXT_PLATFORM = mapOf(
        "nes" to GameCategory.FC.key,
        "fds" to GameCategory.FC.key,
        "gba" to GameCategory.GBA.key,
        "gb" to GameCategory.GBA.key,
        "gbc" to GameCategory.GBA.key,
        "sfc" to GameCategory.SFC.key,
        "smc" to GameCategory.SFC.key,
        "md" to GameCategory.MD.key,
        "smd" to GameCategory.MD.key,
        "gen" to GameCategory.MD.key,
        "bin" to GameCategory.ARCADE.key,
        "zip" to GameCategory.ARCADE.key,
        "fba" to GameCategory.ARCADE.key,
        "7z" to GameCategory.ARCADE.key,
    )

    private data class VolumeInfo(val root: String, val mounted: Boolean)

    // 挂载卷没变就复用旧结果（插拔/换盘会换 signature 自动重扫）；目录内容增删
    // 不会反映进来，重启 App 或重新点进分类（force）时重新扫描。
    private var cache: List<GameItem> = emptyList()
    private var volumesSignature = ""
    private var scannedAt = 0L
    private var scanFailed = false

    /**
     * 当前可玩的 U盘条目。慢扫放 IO 线程；挂载卷变更时全量重扫，否则直接用缓存。
     * @param force 强制重扫（授权刚拿到、用户点重试时）——否则卷没变就不重扫，
     *              首次无权限扫出空列表会被固化，授权后依然显示"没有游戏"。
     * @return 空列表 = 没挂载卷 / 目录里没有常见 ROM 扩展名
     */
    suspend fun items(context: Context, force: Boolean = false): List<GameItem> = withContext(Dispatchers.IO) {
        val vols = mountedVolumes(context)
        val sig = vols.joinToString("|") { "${it.root}@${it.mounted}" }
        if (force || sig != volumesSignature || scannedAt == 0L) {
            val scanned = runCatching { vols.flatMap { scanVolume(it) }.distinctBy { it.localPath }.take(MAX_ENTRIES) }
            scanFailed = scanned.isFailure
            val list = scanned.getOrDefault(emptyList())
            // 非 force 时保留旧列表（瞬时 IO 失败不清空）；force 时以本次结果为准
            if (list.isNotEmpty() || force) cache = list
            volumesSignature = sig
            scannedAt = System.currentTimeMillis()
            Log.i(TAG, "usb scan: ${list.size} roms from ${vols.size} volume(s)" +
                " (force=$force)${if (scanFailed) " (partial/failed)" else ""}")
        }
        cache
    }

    private fun mountedVolumes(context: Context): List<VolumeInfo> {
        val out = ArrayList<VolumeInfo>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { v ->
                val root = volumeRoot(v)
                if (!root.isNullOrBlank() && v.isRemovable && v.state == Environment.MEDIA_MOUNTED) {
                    out += VolumeInfo(root, true)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "storageVolumes query failed", e)
        }
        // 兜底：部分盒子/SD 卷不报 removable，直接看 /storage 下的挂载根目录。
        // 不管上面有没有结果都扫一遍 —— 若 StorageManager 报了个读不了的卷，
        // 兜底可能找到同一路径的可读副本；若上面没报，兜底就是唯一来源。
        runCatching {
            File("/storage").listFiles()?.forEach { d ->
                if (d.isDirectory && d.name != "emulated" && d.name != "self" && d.canRead()) {
                    val path = d.absolutePath
                    if (out.none { it.root == path }) out += VolumeInfo(path, true)
                }
            }
        }
        return out
    }

    private fun volumeRoot(v: StorageVolume): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { v.directory?.absolutePath }.getOrNull()
        } else {
            // getPath() 老版本是 hidden API，反射拿
            runCatching {
                StorageVolume::class.java.getMethod("getPath").invoke(v) as? String
            }.getOrNull()
        }
    }

    private fun scanVolume(vol: VolumeInfo): List<GameItem> {
        if (!vol.mounted) return emptyList()
        val root = File(vol.root)
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val out = ArrayList<GameItem>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && out.size < MAX_ENTRIES) {
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in children.sortedBy { it.name.lowercase() }) {
                if (out.size >= MAX_ENTRIES) break
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) stack.addLast(f to (depth + 1))
                } else {
                    val platform = platformOf(f.name) ?: continue
                    out += GameItem(
                        id = "usb-" + java.util.UUID.nameUUIDFromBytes(f.absolutePath.toByteArray())
                            .toString().replace("-", "").take(12),
                        name = f.name.substringBeforeLast('.').ifBlank { f.name },
                        coverUrl = null,
                        playUrl = "",
                        tags = listOf(GameCategory.fromKey(platform).title),
                        source = GameItem.SOURCE_USB,
                        platformKey = platform,
                        localPath = f.absolutePath,
                    )
                }
            }
        }
        return out
    }

    /** 扩展名 → 平台 key；只允许有原生核心的平台（无核心的扩展名一律跳过） */
    internal fun platformOf(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val key = EXT_PLATFORM[ext] ?: return null
        return if (CoreRouter.coreForPlatform(key) != null) key else null
    }
}