package com.xbw.tv.data.plugin

import android.content.Context
import android.util.Log
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.net.CdnPicker
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.usb.UsbScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 *
 * Cookie：这类网盘源的 ROM / 图片 / 视频直链多半也要带解出的 cookie 才回 200
 * （实测 186317 的 `.nes` 带 cookie 是 200、不带是挑战页）。所以下载资源时
 * 统一走 [downloadAsset]，先确保当前源的 cookie 已解出，再带上 `Cookie` 头下载，
 * 并对"下到 HTML 挑战页"的结果做一次失效重下。
 */
object PluginRepository {

    private const val TAG = "PluginRepo"
    private const val PREFS = "xbw_prefs"
    private const val KEY_SOURCES = "plugin_sources_v1"

    /** 被用户"删除"的内置源 id（内置源定义在代码里删不掉，只能记隐藏）。 */
    private const val KEY_HIDDEN_BUILTINS = "plugin_builtin_hidden_v1"
    private const val CACHE_MS = 24 * 3600_000L

    private val mutex = Mutex()
    /** 并发容器：搜索会在**不持 [mutex]** 的情况下快照读取（加载协程持锁联网期间也要能读），
     *  普通 HashMap 并发读写会破坏内部结构。 */
    private val memCache = java.util.concurrent.ConcurrentHashMap<String, List<GameItem>>()
    private val cookieJars = HashMap<String, MutableMap<String, String>>()

    /** 目录引导源解析出的真实字节目录（src.id → 绝对目录 URL，带尾部 `/`） */
    private val resolvedDirs = HashMap<String, String>()

    /** PHP 目录浏览源 gamelist.xml 所在的站点目录（src.id → **原始编码**的
     *  `path=` 参数值，如 "nes"、"nes%2F01%B6%AF%D7%F7"，根目录是 ""）。
     *  保留原始字节不解码，拼 ROM 下载按钮 URL 时原样回填。 */
    private val resolvedXmlDir = HashMap<String, String>()

    // ---------- 源的管理 ----------

    fun sources(context: Context): List<PluginSource> {
        val hidden = hiddenBuiltins(context)
        // 内置源可被用户删除：删除只是记进隐藏集合，代码里的定义不动
        val builtin = PluginSource.builtinSources().filterNot { it.id in hidden }
        // 用户源按 id/url 与**可见**内置去重（隐藏的内置不参与，否则编辑后的
        // 用户副本会被原内置顶掉，改出来的名字就白改了）
        val user = PluginSource.parseList(
            prefs(context).getString(KEY_SOURCES, "") ?: ""
        ).filter { u -> builtin.none { it.id == u.id || it.listUrl == u.listUrl } }
        return builtin + user
    }

    /** 用户删掉的内置源 id 集合 */
    private fun hiddenBuiltins(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_HIDDEN_BUILTINS, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** 把内置源记入隐藏集合（等价于用户删除） */
    private fun hideBuiltin(context: Context, id: String) {
        val hidden = hiddenBuiltins(context) + id
        prefs(context).edit()
            .putString(KEY_HIDDEN_BUILTINS, JSONArray(hidden.toList()).toString())
            .apply()
        memCache.remove(id)
    }

    /** 按条目 id（plug-<源id>-…）反查所属源；找不到（源已删）返回 null */
    fun sourceById(context: Context, itemId: String): PluginSource? {
        val fixed = itemId.removePrefix("plug-")
        val srcId = fixed.substringBeforeLast('-')
        return sources(context).firstOrNull { it.id == srcId }
    }

    fun save(context: Context, list: List<PluginSource>) {
        prefs(context).edit()
            .putString(KEY_SOURCES, PluginSource.toJsonList(list.filter { !it.builtin }))
            .apply()
        memCache.keys.removeAll { id -> list.none { it.id == id } }
    }

    /** 新增或覆盖（按 id / url 去重），返回生效后的源列表 */
    fun upsert(context: Context, src: PluginSource): List<PluginSource> {
        // 改的正好是一个内置源：把原内置隐藏，改动另存为用户副本（内置定义改不了，
        // 不隐藏的话它会在 sources() 里把同 id/url 的用户副本去重掉）
        if (PluginSource.builtinSources().any { it.id == src.id }) hideBuiltin(context, src.id)
        val merged = sources(context).filterNot {
            it.id == src.id || it.listUrl == src.listUrl
        } + src
        save(context, merged)
        return merged
    }

    fun remove(context: Context, id: String) {
        val src = sources(context).firstOrNull { it.id == id } ?: return
        if (src.builtin) {
            // 内置源删不掉：记进隐藏集合即可
            hideBuiltin(context, id)
        } else {
            save(context, sources(context).filterNot { it.id == id })
        }
    }

    // ---------- 加载 ----------

    /**
     * 某个源的全部条目；失败时回落到磁盘缓存，都拿不到就抛。
     * XML 方式和目录引导方式在这里分叉，外部无感知。
     *
     * @param force true = **每次都要最新**：跳过内存缓存与磁盘的 24h 缓存，直接联网重下；
     *              只有联网失败时才回落到磁盘缓存兜底（离线仍能出列表）。
     */
    suspend fun load(context: Context, src: PluginSource, force: Boolean = false): List<GameItem> =
        mutex.withLock {
            if (!force) memCache[src.id]?.let { return@withLock it } else memCache.remove(src.id)
            val app = context.applicationContext
            val items = if (src.dirBootstrap) {
                runCatching { loadDir(app, src) }.getOrElse {
                    Log.w(TAG, "dir bootstrap ${src.id} failed: ${it.message}")
                    loadDirFromCache(app, src)
                }
            } else {
                val xml = runCatching { readXml(app, src, force) }.getOrElse {
                    Log.w(TAG, "fetch ${src.id} failed: ${it.message}")
                    readCache(app, src.listUrl)
                }
                parse(src, xml)
            }
            if (items.isNotEmpty()) {
                memCache[src.id] = items
            } else {
                throw IllegalStateException("第三方源没有返回任何条目")
            }
            items
        }

    /**
     * 目录引导：抓 `game/` 索引 → 按 [PluginSource.dirMatch] 找到目标子目录
     * （href 自带服务器真实字节）→ 抓它自己的索引 → 有 gamelist.xml 就用 XML
     * 出名字+封面、下载 base 用真实字节目录；没有就按文件行直接出条目。
     */
    private suspend fun loadDir(context: Context, src: PluginSource): List<GameItem> {
        val jar = cookieJars.getOrPut(src.id) { HashMap() }
        val root = src.effectiveListUrl.trimEnd('/') + "/"
        val rootHtml = AesChallenge.fetch(root, jar)
        val rootEntries = DirectoryIndexParser.parse(rootHtml, root)
        Log.i(TAG, "dir ${src.id}: root html=${rootHtml.length}B parsed=${rootEntries.size} dirs=" +
            rootEntries.filter { it.isDir }.joinToString(",") { it.name })
        val resolved = if (src.dirMatch.isNotBlank()) {
            rootEntries.firstOrNull { it.isDir && it.name.contains(src.dirMatch, ignoreCase = true) }?.url
                ?: rootEntries.firstOrNull { it.isDir && it.url.contains(src.dirMatch, ignoreCase = true) }?.url
                ?: run {
                    Log.w(TAG, "dir ${src.id}: dirMatch '${src.dirMatch}' matched nothing (${rootEntries.size} entries), fall back to baseDir")
                    src.baseDir
                }
        } else src.baseDir
        val dirBase = resolved.trimEnd('/') + "/"
        val dirHtml = AesChallenge.fetch(dirBase, jar)
        val dirEntries = DirectoryIndexParser.parse(dirHtml, dirBase)
        val xmlEntry = dirEntries.firstOrNull {
            it.name.equals("gamelist.xml", ignoreCase = true) ||
                it.url.endsWith("gamelist.xml", ignoreCase = true)
        }
        Log.i(TAG, "dir ${src.id}: base=$dirBase listing=${dirEntries.size} xml=${xmlEntry?.url}")
        val items: List<GameItem>
        if (xmlEntry != null) {
            val xml = runCatching { AesChallenge.fetch(xmlEntry.url, jar) }.getOrElse {
                Log.w(TAG, "gamelist fetch failed ${src.id}: ${it.message}")
                readCache(context, src.listUrl)
            }
            if (xml.isBlank()) {
                items = fileEntriesToItems(src, dirEntries, dirBase)
            } else {
                writeCache(context, src.listUrl, xml)
                items = parse(src, xml, dirBase)
            }
        } else {
            items = fileEntriesToItems(src, dirEntries, dirBase)
        }
        resolvedDirs[src.id] = dirBase
        return items
    }

    /** 目录引导源的离线兜底：磁盘缓存里只有最终内容（XML），按它解析 */
    private fun loadDirFromCache(context: Context, src: PluginSource): List<GameItem> {
        val xml = readCache(context, src.listUrl)
        return if (xml.isBlank()) emptyList() else parse(src, xml, src.baseDir)
    }

    /** 把目录索引里的**文件行**直接变成条目（纯目录站、或没有 gamelist.xml 时） */
    private fun fileEntriesToItems(
        src: PluginSource,
        entries: List<DirectoryIndexParser.Entry>,
        base: String
    ): List<GameItem> {
        val out = ArrayList<GameItem>()
        val cat = src.category
        val platformKey = src.platform
        for (e in entries) {
            if (e.isDir) continue
            val rel = e.url.removePrefix(base.trimEnd('/') + "/")
            if (rel.isBlank()) continue
            val stem = e.name.substringBeforeLast('.').trim()
            out += GameItem(
                id = src.idPrefix + uuid12(rel),
                name = stem.ifEmpty { e.name.trim() },
                coverUrl = null,
                playUrl = e.url,
                tags = listOf(if (cat.playable) cat.title else src.title),
                source = GameItem.SOURCE_PLUGIN,
                rawPath = rel,
                rawCover = "",
                platformKey = platformKey,
            )
        }
        return out
    }

    /** 全部第三方源的所有条目合并去重（搜索用） */
    suspend fun loadAll(context: Context): List<GameItem> =
        loadMany(context, sources(context))

    /** 指定一批源的所有条目合并去重（大厅分类 / 搜索用） */
    suspend fun loadMany(context: Context, list: List<PluginSource>): List<GameItem> {
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

    /**
     * 搜索语料：内存缓存 + 磁盘缓存里的**全部**源条目，**不联网**。
     *
     * 插件源就是一份 gamelist.xml（几千条），直接在内存里线性扫，成本可忽略，
     * 所以搜索不需要官方站那种拼音索引库 —— 任何当前或未来的插件，只要大厅加载过
     * （写进内存/磁盘缓存）就自动可搜。站点把拼音首字母塞在 `<name>` 里
     * （`[mfzdyh]`），[parse] 已把它变成 `拼音:xxx` 标签，因此中文和首字母都能命中。
     */
    suspend fun searchCorpus(context: Context): List<GameItem> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val out = ArrayList<GameItem>()
        val seen = HashSet<String>()
        for (src in sources(app)) {
            val items = memCache[src.id] ?: readCachedItems(app, src)
            items.forEach { if (seen.add(it.id)) out += it }
        }
        out
    }

    /** 磁盘缓存里的源条目（与 [readCache] 同一份文件）；没有/损坏返回空 */
    private fun readCachedItems(context: Context, src: PluginSource): List<GameItem> {
        val f = cacheFile(context, src.listUrl)
        if (!f.isFile || f.length() == 0L) return emptyList()
        return runCatching { parse(src, f.readText(), src.baseDir) }.getOrDefault(emptyList())
    }

    /**
     * 后台补拉：把既不在内存、也没有磁盘缓存的源联网加载一遍。
     * @return 是否真的加载了新源（调用方据此决定要不要重跑一次搜索）
     */
    suspend fun warmSources(context: Context): Boolean {
        val app = context.applicationContext
        var loaded = 0
        var failed = 0
        for (src in sources(app)) {
            if (memCache.containsKey(src.id)) continue
            val f = cacheFile(app, src.listUrl)
            if (f.isFile && f.length() > 0L) continue
            runCatching { load(app, src) }
                .onSuccess { loaded++ }
                .onFailure { failed++; Log.w(TAG, "warm ${src.id} failed: ${it.message}") }
        }
        Log.i(TAG, "warmSources: loaded=$loaded failed=$failed")
        return loaded > 0
    }

    internal fun parse(src: PluginSource, xml: String, effectiveBase: String? = null): List<GameItem> {
        val entries = GamelistParser.parse(xml)
        // 目录引导源下载/封面的基准目录必须是服务器给的真实字节目录；
        // XML 源用 [PluginSource.baseDir]（清单所在目录）。
        val base = effectiveBase ?: src.baseDir
        val cat = src.category
        val platformKey = src.platform
        return entries.mapNotNull { e ->
            // 「合并清单」过滤（filterByPlatform，如 186317 的 game/gamelist.xml）：
            // 扩展名能判出另一平台的条目直接丢（街机视口里的 .nes FC ROM 不混进来）。
            // 独立清单不过滤 —— FC 里 zip/7z 打包的 ROM 不能被扩展名误导成街机。
            // 注意：这只影响"留哪些条目"，条目的运行核心始终是用户在编辑页选的那个。
            if (src.filterByPlatform && src.playable) {
                val file = e.path.substringAfterLast('/').substringBefore('?')
                val ext = UsbScanner.platformOf(file)
                if (ext != null && ext != platformKey) return@mapNotNull null
            }
            val (title, _) = GamelistParser.splitName(
                e.sortname.ifEmpty { e.name }
            )
            // 拼音首字母站点塞在 <name> 的 [xxx] 里，而 <sortname> 常常**不带**这个标记
            // （例："S-三国志 II（…）[sgzsjb]" vs sortname "S-三国志 II（…）"）。
            // 标题优先用 sortname，首字母必须从 <name> 取，否则搜 sgz 永远不中。
            val initials = GamelistParser.initialsOf(e.name)
                .ifEmpty { GamelistParser.initialsOf(e.sortname) }
            val genre = GamelistParser.genreOf(e.path)
            val tags = listOfNotNull(
                genre.ifEmpty { null },
                if (cat.playable) cat.title else src.title
            ) + listOfNotNull(initials.takeIf { it.isNotEmpty() }?.let { "拼音:$it" })
            GameItem(
                // UUID 自带连字符，必须先剥掉再取短哈希，否则 sourceById 按
                // "plug-<源id>-<hash>" 反解析时会把哈希里的 '-' 误当作分隔符
                id = src.idPrefix + uuid12(e.path),
                name = title.ifEmpty { e.path.substringAfterLast('/') },
                coverUrl = joinBase(src.coverBase.ifBlank { base }, e.image),
                playUrl = joinBase(src.romBase.ifBlank { base }, e.path).orEmpty(),
                tags = tags,
                source = GameItem.SOURCE_PLUGIN,
                // 视频：优先 romBase（同盘），再 coverBase，最后基准目录
                videoUrl = e.video.ifBlank { null }
                    ?.let { joinBase((src.romBase.ifBlank { src.coverBase }).ifBlank { base }, it) },
                // 原始相对路径：下载时按百分比编码（GBK 站再补一条 GBK 编码候选）
                rawPath = e.path,
                rawCover = e.image,
                platformKey = platformKey,
            )
        }
    }

    private fun uuid12(bytesOf: String): String =
        java.util.UUID.nameUUIDFromBytes(bytesOf.toByteArray())
            .toString().replace("-", "").take(12)

    /** 相对路径拼 base；base 为空就返回 null（没有可解析的直链）。
     *  [rel] 本身是绝对 http(s) 地址（xml 直接给 ROM/图片/视频直链）时原样返回，
     *  不去拼 base、也不编码。 */
    private fun joinBase(base: String, rel: String): String? {
        if (rel.isBlank()) return null
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel.trim()
        if (base.isBlank()) return null
        val encoded = rel.removePrefix("./").split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return base.trimEnd('/') + "/" + encoded
    }

    // ---------- 下载候选地址 ----------

    /**
     * 按顺序给出一条条候选 ROM 直链，下载时逐个试到"拿到真文件"为止：
     *  - PHP 目录浏览站（[PluginSource.phpDir]，186317 的 22web/xo.je）：
     *      1. index.php "下载按钮"地址（?path=<GBK目录>&download=<GBK文件名>），
     *         站点只保证这条路能下载；
     *      2. GBK 编码直链（站点磁盘名是 GBK 字节，实测多数文件也放行）；
     *      3. 通用直链（[GameItem.playUrl]，UTF-8 编码，兜底）。
     *  - 目录引导（[PluginSource.dirBootstrap]，如街机源）：
     *      基准 = 服务器 href 解析出的真实字节目录；直链 = 基准 + 相对路径。
     *  - XML 方式：
     *      [PluginSource.gbkUris] 的站（186317：磁盘名是 GBK 保留字节）：
     *        1. 按 GBK 百分号编码的直链（实测可用）；
     *        2. 通用直链（[GameItem.playUrl]，UTF-8 编码，GBK 盘上是 404 兜底）。
     *      其它源就一个通用直链。
     * 已去掉 down.php 网关 —— 站点把 `/game/down.php` 改成了目录 `/game/`，
     * 下载统一走目录直链。去重去空。
     */
    fun romCandidates(src: PluginSource, item: GameItem): List<String> {
        if (item.rawPath.isBlank()) {
            return listOfNotNull(item.playUrl.takeIf { it.isNotBlank() })
        }
        val out = LinkedHashSet<String>()
        if (src.phpDir) {
            phpButtonUrl(src, item.rawPath)?.let { out += it }
        } else {
            val base = if (src.dirBootstrap) resolvedDirs[src.id] ?: src.baseDir else src.baseDir
            val direct = if (src.gbkUris) encodeGbkPath(base, item.rawPath)
                else joinBase(base, item.rawPath)
            direct?.let { out += it }
        }
        item.playUrl.takeIf { it.isNotBlank() }?.let { out += it }
        return out.toList()
    }

    /** 相对路径 → 按 GBK 逐段百分号编码的绝对 URL（本机 GBK 磁盘名的站专用）。
     *  [rel] 是绝对 http(s) 地址时原样返回（xml 直链不需要编码）。 */
    private fun encodeGbkPath(base: String, rel: String): String? = runCatching {
        val t = rel.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        val gbk = java.nio.charset.Charset.forName("GBK")
        val encoded = rel.trim().removePrefix("./").trimStart('/').split('/').joinToString("/") { seg ->
            val hex = StringBuilder()
            for (b in seg.toByteArray(gbk)) {
                hex.append('%').append(HEX[(b.toInt() ushr 4) and 0xF])
                    .append(HEX[b.toInt() and 0xF])
            }
            hex.toString()
        }
        base.trimEnd('/') + "/" + encoded
    }.getOrNull()

    private val HEX = "0123456789ABCDEF"

    // ---------- cookie 与资源下载 ----------

    /**
     * 确保源 cookie 已解出并返回它（`__test=<hex>` 头值）；没有就现场解一次。
     * 解完存在会话级 jar 里，同一个源的所有资源请求复用。
     */
    suspend fun cookieFor(context: Context, src: PluginSource): String? {
        val jar = cookieJars.getOrPut(src.id) { HashMap() }
        jar[AesChallenge.COOKIE]?.let { return it }
        runCatching {
            AesChallenge.fetch(src.effectiveListUrl, jar)
        }.onFailure { Log.w(TAG, "cookie solve failed for ${src.id}: ${it.message}") }
        // 清单（尤其 http 直链 xml）经常免挑战，ROM/zip 仍要 __test。
        // 对源目录发一次探活，逼出挑战页并写入 jar；探活 404 也没关系。
        if (jar[AesChallenge.COOKIE] == null) {
            val probe = src.baseDir.trimEnd('/') + "/.xbw_cookie"
            runCatching {
                AesChallenge.fetch(probe, jar)
            }.onFailure { Log.w(TAG, "cookie probe failed for ${src.id}: ${it.message}") }
        }
        return jar[AesChallenge.COOKIE]
    }

    private fun cookieHeader(src: PluginSource, jar: MutableMap<String, String>?): Map<String, String> =
        jar?.get(AesChallenge.COOKIE)?.let { mapOf("Cookie" to "${AesChallenge.COOKIE}=$it") } ?: emptyMap()

    /**
     * 带 cookie 下载资源（ROM / 视频 / 封面），失败原因一目了然：
     *  - 服务端下回 HTML 挑战页 → 判定"没带对 cookie"，解一次再下
     *  - 404 / 网络错 → 直接抛，调用方转成用户可见的提示
     */
    suspend fun downloadAsset(context: Context, src: PluginSource, url: String, dest: File) {
        val app = context.applicationContext
        var jar = cookieJars[src.id]
        if (jar?.get(AesChallenge.COOKIE) == null) {
            cookieFor(app, src)
            jar = cookieJars[src.id]
        }
        val destForAttempt = dest
        runCatching {
            HttpFetcher.downloadToFile(url, destForAttempt, headers = cookieHeader(src, jar))
        }.getOrElse { firstError ->
            // 可能 cookie 过期/被换：重新解一次再试，再不行就把原错误抛出去
            jar?.clear()
            cookieJars.remove(src.id)
            val fresh = cookieFor(app, src)
            val headers = fresh?.let { mapOf("Cookie" to "${AesChallenge.COOKIE}=$it") } ?: emptyMap()
            HttpFetcher.downloadToFile(url, destForAttempt, headers = headers)
        }
        if (looksLikeHtml(destForAttempt)) {
            val html = runCatching { destForAttempt.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            val solved = if (AesChallenge.isChallenge(html)) AesChallenge.solve(html) else null
            destForAttempt.delete()
            if (solved != null) {
                cookieJars.getOrPut(src.id) { HashMap() }[AesChallenge.COOKIE] = solved
                HttpFetcher.downloadToFile(
                    url, dest,
                    headers = mapOf("Cookie" to "${AesChallenge.COOKIE}=$solved")
                )
                if (!looksLikeHtml(dest)) return
                dest.delete()
            }
            throw IllegalArgumentException("$url 返回的不是文件（可能是挑战页/登录页）")
        }
    }

    private fun looksLikeHtml(f: File): Boolean {
        if (!f.isFile || f.length() == 0L) return true
        if (f.length() >= 64_000) return false
        val head = f.inputStream().use { ins ->
            val buf = ByteArray(128)
            val n = ins.read(buf)
            String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
        }
        return head.trimStart().startsWith("<") &&
                (head.contains("<html", true) || head.contains("toNumbers") || head.contains("<!doctype", true))
    }

    // ---------- 封面预取 ----------

    /** 封面下载归属目录（按源分开，避免两个源同一文件名互相顶掉） */
    private fun coverDir(context: Context, srcId: String): File =
        File(context.applicationContext.cacheDir, "plugins-covers/$srcId").apply { mkdirs() }

    /**
     * 把一页条目的封面预取到本地，返回 coverUrl=本地路径 的副本。
     *
     * 这些封面也挂在同一个 GBK 网盘上：裸 UTF-8 URL 会 404、不带 cookie 会回挑战页，
     * Glide 无法带自定义头，所以列表页切片后由这里主动下载落盘，Glide 只读本地文件。
     *
     * 失败策略：**单次尝试、失败就跳过**（保持原有远程 URL → Glide 落到占位图），
     * 不走 [downloadAsset] 的 6 次重试——一页 18 张里有一两张坏图不该拖慢整页。
     *
     * @param items 通常是一整页（18 条）；内部按源分组，cookie 每源只解一次
     */
    suspend fun prefetchCovers(context: Context, items: List<GameItem>): List<GameItem> {
        if (items.isEmpty()) return items
        val app = context.applicationContext
        val result = items.toMutableList()
        // 每个源先确认 cookie（一次），再并发下这源的封面
        val grouped = items.mapIndexedNotNull { idx, item ->
            sourceById(app, item.id)?.let { src -> Triple(idx, item, src) }
        }.groupBy { it.third }
        for ((src, group) in grouped) {
            val srcId = src.id
            val jar = cookieJars.getOrPut(srcId) { HashMap() }
            if (jar[AesChallenge.COOKIE] == null) runCatching {
                AesChallenge.fetch(src.effectiveListUrl, jar)
            }.onFailure { Log.w(TAG, "cover cookie solve failed $srcId: ${it.message}") }
            val cookie = jar[AesChallenge.COOKIE]
            val headers = cookie?.let { mapOf("Cookie" to "${AesChallenge.COOKIE}=$it") } ?: emptyMap()

            // 并发下载，但各协程只产出 (索引, 本地路径)，全部 join 后再单线程写回 result。
            // 原先在 IO 协程里直接 result[i]=…，是对同一个 ArrayList 的并发写，存在竞态。
            val updates = coroutineScope {
                group.map { (idx, item, _) ->
                    async(Dispatchers.IO) {
                        val url = coverCandidateUrl(src, item) ?: return@async null
                        prefetchOne(app, srcId, url, headers)?.let { local -> idx to local.absolutePath }
                    }
                }.mapNotNull { it.await() }
            }
            updates.forEach { (i, path) -> result[i] = result[i].copy(coverUrl = path) }
        }
        Log.i(TAG, "prefetched covers for ${items.size} items")
        return result
    }

    /** 封面下载地址：PHP 目录站优先"下载按钮"地址；GBK 站优先按 GBK 编码原始
     *  相对路径；其它源用解析出的 URL */
    private fun coverCandidateUrl(src: PluginSource, item: GameItem): String? {
        if (src.phpDir && item.rawCover.isNotBlank()) {
            phpButtonUrl(src, item.rawCover)?.let { return it }
        }
        val base = if (src.dirBootstrap) resolvedDirs[src.id] ?: src.baseDir else src.baseDir
        if (item.rawCover.isNotBlank()) {
            if (src.gbkUris) encodeGbkPath(base, item.rawCover)?.let { return it }
            joinBase(base, item.rawCover)?.let { return it }
        }
        return item.coverUrl?.takeIf { it.isNotBlank() }
    }

    /** 下封面到本地缓存（命中即复用，失败返回 null 保持远程 URL/占位图） */
    private suspend fun prefetchOne(
        app: Context,
        srcId: String,
        url: String,
        headers: Map<String, String>
    ): File? {
        val dest = File(coverDir(app, srcId), PluginSource.deriveId(url) + ".img")
        if (dest.isFile && dest.length() > 100) { Log.i(TAG, "cover cache-hit $url"); return dest }
        if (dest.exists()) dest.delete()
        val ok = HttpFetcher.tryGetFile(url, dest, headers = headers)
        if (!ok || dest.length() <= 100 || looksLikeHtml(dest)) {
            Log.w(TAG, "cover failed ${if (ok) "html/empty" else "http"} $url (${dest.length()}B)")
            dest.delete()
            return null
        }
        Log.i(TAG, "cover ok ${dest.length()}B $url")
        return dest
    }

    // ---------- 磁盘缓存 ----------

    private suspend fun readXml(context: Context, src: PluginSource, force: Boolean = false): String {
        val cached = cacheFile(context, src.listUrl)
        if (!force && cached.isFile && System.currentTimeMillis() - cached.lastModified() < CACHE_MS) {
            Log.i(TAG, "hit disk cache ${src.id}")
            return cached.readText()
        }
        // GitHub raw 清单（内置 FC 等）：用启动实测的镜像顺位**并发竞速**，
        // 谁先回真 XML 用谁（直链源如 cnliux.dpdns.org 不含 raw，不在此列）。
        src.githubRawUrl?.let { raw ->
            fetchGithubRaw(raw)?.let { raced ->
                Log.i(TAG, "github raw raced ${src.id}: ${raced.length}B")
                writeCache(context, src.listUrl, raced)
                return raced
            }
            Log.w(TAG, "github raw race all failed ${src.id}, fall back")
        }
        val jar = cookieJars.getOrPut(src.id) { HashMap() }
        var body = AesChallenge.fetch(src.effectiveListUrl, jar)
        // 免费主机有时对裸请求先回一页"JS 广告跳转"（无 toNumbers、无正文）。
        // 浏览器走的是 ?i=1 这条路，我们也补上 i=1 再抓一次（挑战由 fetch 内部解）。
        if (isJunkRedirect(body)) {
            val retryUrl = src.effectiveListUrl +
                (if (src.effectiveListUrl.contains('?')) "&" else "?") + "i=1"
            Log.i(TAG, "php site ad page on $src.id, retry with i=1")
            body = AesChallenge.fetch(retryUrl, jar)
        }
        if (looksLikeXml(body)) {
            // 用户把"下载按钮"地址直接贴成 listUrl（…?path=nes&download=gamelist.xml）：
            // 它本身就是 XML，但 ROM 候选仍要按 PHP 站拼按钮 URL
            if (src.listUrl.contains("download=gamelist.xml")) {
                resolvedXmlDir[src.id] = phpPathParam(src.listUrl)
                markPhpDir(context, src)
            }
            writeCache(context, src.listUrl, body)
            return body
        }
        // PHP 目录浏览页（186317 的 22web / xo.je）：清单不在直链上，只能从目录页的
        // "下载"按钮 href 拿 —— `?path=<站点目录>&download=gamelist.xml`。
        val href = phpGamelistHref(body) ?: return body   // 不是这类站 → 交给上层报错
        val xmlUrl = runCatching {
            java.net.URI(src.effectiveListUrl).resolve(href).toString()
        }.getOrElse {
            Log.w(TAG, "resolve gamelist href failed: ${href}")
            return body
        }
        val xml = AesChallenge.fetch(xmlUrl, jar)
        if (!looksLikeXml(xml)) return body
        // path 参数保留原始编码字节（站点是 GBK 落盘），拼 ROM 按钮时直接回填
        resolvedXmlDir[src.id] = phpPathParam(href)
        markPhpDir(context, src)
        writeCache(context, src.listUrl, xml)
        return xml
    }

    /**
     * GitHub raw 清单竞速：把 [raw] 拼在 [CdnPicker] 实测顺位的每个镜像前缀后
     * （含空串直连兜底），**并发**取文本，第一个通过 [looksLikeXml] 的胜出，
     * 其余取消。镜像挂了/回 HTML 错误页都算失败，交给下一个。
     */
    private suspend fun fetchGithubRaw(raw: String): String? = coroutineScope {
        val urls = CdnPicker.ranked().map { it + raw }.distinct()
        val done = Channel<String?>(urls.size)
        val jobs = urls.map { url ->
            launch(Dispatchers.IO) {
                val body = runCatching { HttpFetcher.fetchText(url) }
                    .getOrNull()?.takeIf { looksLikeXml(it) }
                done.send(body)
            }
        }
        var winner: String? = null
        var settled = 0
        while (winner == null && settled < urls.size) {
            val r = done.receive()
            settled++
            if (r != null) winner = r
        }
        jobs.forEach { it.cancel() }
        winner
    }

    /** 广告/纯跳转页特征：只有一段导航脚本，没有任何正文标记 */
    private fun isJunkRedirect(html: String): Boolean =
        html.contains("<script", ignoreCase = true) &&
            !html.contains("toNumbers(") &&
            !looksLikeXml(html) &&
            !html.contains("download=gamelist.xml", ignoreCase = true) &&
            !html.contains("<a ", ignoreCase = true)

    private fun looksLikeXml(s: String): Boolean {
        val t = s.trimStart()
        return t.startsWith("<?xml") || t.contains("<gameList", ignoreCase = true)
    }

    /** 目录页里 gamelist.xml "下载"按钮的 href（`?path=…&download=gamelist.xml`） */
    private fun phpGamelistHref(html: String): String? =
        Regex("href\\s*=\\s*[\"']([^\"']*[?&]download=gamelist\\.xml[^\"']*)[\"']",
            RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    /** 从 `?path=a%2Fb&download=x` 里取出**原始编码**的 path 参数值（可能为空串） */
    private fun phpPathParam(hrefOrUrl: String): String =
        Regex("[?&]path=([^&\"']*)").find(hrefOrUrl)?.groupValues?.get(1).orEmpty()

    /** PHP 站"下载按钮"URL：`<baseDir>/?path=<GBK目录，/→%2F>&download=<GBK文件名>`。
     *  站点磁盘名是 GBK 字节，站点自己的 href 就是这么写的，照抄格式。 */
    private fun phpButtonUrl(src: PluginSource, rel: String): String? = runCatching {
        val clean = rel.trim().removePrefix("./").trimStart('/')
        if (clean.isBlank()) return null
        val file = clean.substringAfterLast('/')
        val dir = clean.substringBeforeLast('/', "")
        val dirRaw = resolvedXmlDir[src.id] ?: phpPathParam(src.listUrl)
        val segs = listOf(dirRaw, encodeGbkSegment(dir)).filter { it.isNotBlank() }
        val base = phpBase(src)
        "$base/?path=${segs.joinToString("%2F")}&download=${encodeGbkSegment(file)}"
    }.getOrNull()

    /** PHP 目录站的 index.php 所在目录（scheme://host + listUrl 的 path 部分，
     *  带尾部 `/`）。不能用 [PluginSource.baseDir]：它按"最后一个斜杠"截断，
     *  对 `…/game/` 会丢掉 `/game`，对 `…/game/?path=nes` 会把 query 里的
     *  `%2F` 当路径分隔符算错。 */
    private fun phpBase(src: PluginSource): String = runCatching {
        val u = java.net.URI(src.listUrl.trim())
        val path = u.rawPath ?: ""
        val dir = if (path.endsWith("/")) path else path.substringBeforeLast('/', "") + "/"
        "${u.scheme}://${u.authority}$dir".trimEnd('/')
    }.getOrElse { src.baseDir.trimEnd('/') }

    /** 非空路径段按 GBK 百分号编码；已有的 %XX 不二次编码 */
    private fun encodeGbkSegment(seg: String): String {
        if (seg.all { it.code < 0x80 }) return seg
        val gbk = java.nio.charset.Charset.forName("GBK")
        val sb = StringBuilder()
        var i = 0
        while (i < seg.length) {
            val c = seg[i]
            if (c.code < 0x80) {
                if (c == '%' && i + 2 < seg.length &&
                    seg[i + 1].isHexDig() && seg[i + 2].isHexDig()
                ) {
                    sb.append(seg, i, i + 3); i += 3
                    continue
                }
                sb.append(c); i++
            } else {
                for (b in seg.substring(i, i + 1).toByteArray(gbk)) {
                    sb.append('%').append(HEX[(b.toInt() ushr 4) and 0xF])
                        .append(HEX[b.toInt() and 0xF])
                }
                i++
            }
        }
        return sb.toString()
    }

    private fun Char.isHexDig(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** 识别为 PHP 目录站后写回源配置（romCandidates/封面预取要按按钮方式拼 URL） */
    private fun markPhpDir(context: Context, src: PluginSource) {
        if (src.builtin || src.phpDir) return
        runCatching { upsert(context, src.copy(phpDir = true)) }
            .onFailure { Log.w(TAG, "persist phpDir failed: ${it.message}") }
        Log.i(TAG, "source ${src.id} marked as phpDir site")
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
