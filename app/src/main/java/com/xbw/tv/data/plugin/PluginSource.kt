package com.xbw.tv.data.plugin

import com.xbw.tv.data.model.GameCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个第三方游戏源（插件）。
 *
 * 目前只支持一种清单格式：**retroFE/Emby 风格的 gamelist.xml**，实测样本
 * `https://186317.22web.org/nes/gamelist.xml`（592 条 FC）。之所以先做它：
 * 一份 XML 就带标题、题材、封面路径和拼音首字母，比抓 HTML 稳得多。
 *
 * 该站是"免费网盘"，gamelist.xml 只是其中一个公开文件，XML 里的 `./01动作/x.nes`
 * 这类相对路径在站点上**取不到**（实测 /nes/、/nes/roms/、/roms/ 全 404）。
 * 所以 [coverBase] / [romBase] 做成可配置：留空就只出元数据列表，
 * 用户哪天拿到能访问的直链规则，填进去同一个插件立刻能出图能下载。
 *
 * @param id         稳定 id，内置源用固定值，用户自填的用 url 派生
 * @param title      分类 chip 上显示的名字（例："FC第三方"）
 * @param platform   归属平台（GameCategory.key）。决定这些条目能不能进搜索结果、
 *                   走哪个原生核心；XML 本身不声明平台，所以由用户指定
 * @param listUrl    gamelist.xml 地址
 * @param coverBase  封面 base，可空。`<image>` 的相对路径拼在它后面
 * @param romBase    ROM base，可空。为空时条目只读不玩
 * @param builtin    内置源不允许删除
 */
data class PluginSource(
    val id: String,
    val title: String,
    val platform: String,
    val listUrl: String,
    val coverBase: String = "",
    val romBase: String = "",
    val builtin: Boolean = false
) {
    /** 归属的可玩平台分类；填了没核心的平台（java/nds…）就退化成纯元数据 */
    val category: GameCategory get() = GameCategory.fromKey(platform)

    val playable: Boolean get() = category.playable

    /** 条目 id 前缀，避免和官方站 id 撞车（官方是纯数字） */
    val idPrefix: String get() = "plug-$id-"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("platform", platform)
        put("listUrl", listUrl)
        put("coverBase", coverBase)
        put("romBase", romBase)
        put("builtin", builtin)
    }

    companion object {
        /** 内置：186317 网盘上的 FC 清单（用户给的第一个源） */
        val BUILTIN_FC_186317 = PluginSource(
            id = "fc186317",
            title = "FC第三方",
            platform = GameCategory.FC.key,
            listUrl = "https://186317.22web.org/nes/gamelist.xml",
            builtin = true
        )

        fun fromJson(o: JSONObject): PluginSource? {
            val url = o.optString("listUrl").trim()
            if (url.isEmpty()) return null
            return PluginSource(
                id = o.optString("id").ifEmpty { deriveId(url) },
                title = o.optString("title").ifEmpty { "第三方源" },
                platform = o.optString("platform").ifEmpty { GameCategory.FC.key },
                listUrl = url,
                coverBase = o.optString("coverBase"),
                romBase = o.optString("romBase"),
                builtin = o.optBoolean("builtin")
            )
        }

        /** 同一条 URL 只能有一个源，重复添加直接顶掉旧的 */
        fun deriveId(url: String): String {
            val md = java.security.MessageDigest.getInstance("MD5")
            val hex = md.digest(url.trim().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return hex.substring(0, 10)
        }

        fun parseList(text: String): List<PluginSource> = runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())

        fun toJsonList(list: List<PluginSource>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}