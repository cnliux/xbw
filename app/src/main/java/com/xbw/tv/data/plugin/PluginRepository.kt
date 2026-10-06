package com.xbw.tv.data.plugin

import android.content.Context
import android.util.Log
import com.xbw.tv.data.model.GameItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * 第三方源的注册表 + 加载器。
 *
 * 设计取向：**插件只管把外部清单变成 [GameItem]**，剩下的分页、搜索、过滤、
 * 详情、下载全都复用官方站那套。所以接一个新源的成本就是写一个 [PluginSource] 配置。
 *
 * 存储用 SharedPreferences 存 JSON 数组，不进 Room —— 加表要升 DB 版本，
 * 而 `fallbackToDestructiveMigration` 会把用户的「收藏 / 最近玩过」一起清掉，
 * 为了几个源地址不值当。
 *
 * 缓存分两级：
 *  - 磁盘：把 XML 原文存到 cacheDir，按 url 的 md5 命名，24h 内复用（源挂了也能出列表）
 *  - 内存：解析结果按源 id 缓存，切分类来回翻不重复解挑战
 */
object PluginRepository {

    private const val TAG = "PluginRepo"
    private const val PREFS = "xbw_prefs"
    private const val KEY_SOURCES = "plugin_sources_v1"
    private const val CACHE_MS = 24 * 3600_000L

    private val mutex = Mutex()
    private val memCache = HashMap<String, List<GameItem>>()
    private val cookieJars = HashMap<String, MutableMap<String, String>>()

    // ---------- 源的管理 ----------

    fun sources(context: Context): List<PluginSource> {
        val user = PluginSource.parseList(
            prefs(context).getString(KEY_SOURCES, "") ?: ""
        )
        // 内置源永远在最前，且用户改不了
        return listOf(PluginSource.BUILTIN_FC_186317) + user
    }

    fun save(context: Context, list: List<PluginSource>) {
        prefs(context).edit()
            .putString(KEY_SOURCES, PluginSource.toJsonList(list.filter { !it.builtin }))
            .apply()
        memCache.keys.removeAll { id -> list.none { it.id == id } }
    }

    /** 新增或覆盖（按 id / url 去重），返回生效后的源列表 */
    fun upsert(context: Context, src: PluginSource): List<PluginSource> {
        val merged = sources(context).filterNot {
            it.id == src.id || it.listUrl == src.listUrl
        } + src
        save(context, merged)
        return merged
    }

    fun remove(context: Context, id: String) {
        save(context, sources(context).filterNot { it.id == id && !it.builtin })
    }

    /** 绑定了某个平台的第三方源（大厅的"FC第三方"分类用它） */
    fun forPlatform(context: Context, platformKey: String): List<PluginSource> =
        sources(context).filter { it.platform == platformKey }

    // ---------- 加载 ----------

    /** 某个源的全部条目；失败时回落到磁盘缓存，都拿不到就抛 */
    suspend fun load(context: Context, src: PluginSource): List<GameItem> =
        mutex.withLock {
            memCache[src.id]?.let { return@withLock it }
            val app = context.applicationContext
            val xml = runCatching { readXml(app, src) }.getOrElse {
                Log.w(TAG, "fetch ${src.id} failed: ${it.message}")
                readCache(app, src.listUrl)
            }
            val items = parse(src, xml)
            if (items.isNotEmpty()) {
                writeCache(app, src.listUrl, xml)
                memCache[src.id] = items
            } else if (xml.isEmpty()) {
                throw IllegalStateException("第三方源没有返回任何条目")
            }
            items
        }

    /** 绑到某平台的所有第三方源合并去重（大厅分类 / 搜索用） */
    suspend fun loadByPlatform(context: Context, platformKey: String): List<GameItem> {
        val list = forPlatform(context, platformKey)
        if (list.isEmpty()) return emptyList()
        val all = mutableListOf<GameItem>()
        val seen = HashSet<String>()
        for (src in list) {
            val items = runCatching { load(context, src) }.getOrElse {
                Log.w(TAG, "source ${src.id} skipped: ${it.message}")
                emptyList()
            }
            items.forEach { if (seen.add(it.id)) all += it }
        }
        return all
    }

    private fun parse(src: PluginSource, xml: String): List<GameItem> {
        val entries = GamelistParser.parse(xml)
        return entries.map { e ->
            val (title, initials) = GamelistParser.splitName(
                e.sortname.ifEmpty { e.name }
            )
            val genre = GamelistParser.genreOf(e.path)
            // 拼音首字母来自站点自己塞在 <name> 里的 [xxx]，比我们自己算准
            val tags = listOfNotNull(
                genre.ifEmpty { null },
                if (src.playable) src.category.title else src.title
            ) + listOfNotNull(initials.takeIf { it.isNotEmpty() }?.let { "拼音:$it" })
            GameItem(
                id = src.idPrefix + java.util.UUID.nameUUIDFromBytes(
                    e.path.toByteArray()
                ).toString().take(12),
                name = title.ifEmpty { e.path.substringAfterLast('/') },
                coverUrl = joinBase(src.coverBase, e.image),
                playUrl = joinBase(src.romBase, e.path).orEmpty(),
                tags = tags,
                source = GameItem.SOURCE_PLUGIN
            )
        }
    }

    /** 相对路径拼 base；base 为空就返回 null（没有可用直链，别造假 URL） */
    private fun joinBase(base: String, rel: String): String? {
        if (base.isBlank() || rel.isBlank()) return null
        val encoded = rel.removePrefix("./").split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return base.trimEnd('/') + "/" + encoded
    }

    // ---------- 磁盘缓存 ----------

    private suspend fun readXml(context: Context, src: PluginSource): String {
        val cached = cacheFile(context, src.listUrl)
        if (cached.isFile && System.currentTimeMillis() - cached.lastModified() < CACHE_MS) {
            Log.i(TAG, "hit disk cache ${src.id}")
            return cached.readText()
        }
        val jar = cookieJars.getOrPut(src.id) { HashMap() }
        return AesChallenge.fetch(src.listUrl, jar)
    }

    private fun readCache(context: Context, url: String): String {
        val f = cacheFile(context, url)
        return if (f.isFile) f.readText() else ""
    }

    private suspend fun writeCache(context: Context, url: String, xml: String) {
        runCatching {
            withContext(Dispatchers.IO) { cacheFile(context, url).writeText(xml) }
        }.onFailure { Log.w(TAG, "cache write failed: ${it.message}") }
    }

    private fun cacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "plugins").apply { mkdirs() }
        return File(dir, PluginSource.deriveId(url) + ".xml")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}