package com.xbw.tv.ui.lobby

import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.plugin.PluginSource

/**
 * 大厅顶部分类芯片的两种来源：
 *  - [BuiltIn]：内置分类（全部游戏 / FC / 街机 / GBA / SFC / MD / U盘游戏 / 我的收藏 / 最近玩过）
 *  - [Plugin]：用户自建的一个第三方源，芯片标题就是用户在编辑页写的「显示名称」，
 *    进游戏只走用户在该源选的运行核心（[PluginSource.platform]）。
 */
sealed class LobbyCategory(val key: String, val title: String) {

    /** 内置分类，[GameCategory.key] 就是芯片标识 */
    class BuiltIn(val category: GameCategory) : LobbyCategory(category.key, category.title)

    /** 用户自建源；删掉源后对应芯片自动消失（key = 源 id） */
    class Plugin(val source: PluginSource) : LobbyCategory(source.id, source.title)
}