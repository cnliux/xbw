package com.xbw.tv.data.model

/**
 * 一条游戏条目。全部字段来自 yikm.net 实时解析，App 内不内置任何游戏数据。
 *
 * 真实站点结构（2025 年实测，见 docs/YIKM_SITE_STRUCTURE.md）：
 *   <div class="card card-blog">
 *     <a href="/play?id=4137"><div class="card-image"><img src="https://img.1990i.com/fcpic/sj/436a.png"></div></a>
 *     <div class="table">
 *       <span class="label ...">射击</span><span class="label ...">魂斗罗</span>
 *       <h4 class="card-caption"><a href="/play?id=4137">魂斗罗(美版)</a></h4>
 *     </div>
 *   </div>
 *
 * @param id        游戏 ID，取自 playUrl 的 query 参数 id（例："4137"）
 * @param name      游戏名（h4.card-caption a 的文本）
 * @param coverUrl  封面绝对地址（可能为 nopic.png 占位图）
 * @param playUrl   游戏详情页绝对地址（https://www.yikm.net/play?id=4137）
 * @param tags      卡片上的分类标签（例：["射击","魂斗罗"]），第一个通常是类型
 * @param source    该条目来自哪个入口，用于埋点与"最近玩过"分类展示
 */
data class GameItem(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val playUrl: String,
    val tags: List<String> = emptyList(),
    val source: String = SOURCE_HOME,
    /** 第三方插件条目自带的预览/正片视频（gamelist.xml 的 <video>，可选） */
    val videoUrl: String? = null,
    /** 第三方插件条目在清单里的原始相对路径（如 ./06文字/x.nes），
     *  下载时要拆目录+文件名拼站点专用网关的 query */
    val rawPath: String = "",
    /** 第三方插件条目在清单里的原始封面相对路径（如 ./06文字/x.png）；
     *  预取封面时按它重新拼 GBK/UTF-8 地址，而不是用 [coverUrl] 里那个会 404 的默认编码 */
    val rawCover: String = "",
    /** U盘条目的平台 key（由扩展名推断，fc/arcade/gba/sfc/md）；本地游戏用它选核心 */
    val platformKey: String = "",
    /** U盘条目的本地文件绝对路径；有值表示这是个本地 ROM 文件，不走网络下载 */
    val localPath: String = ""
) {
    /** 主分类（第一个标签），UI 角标用 */
    val primaryTag: String get() = tags.firstOrNull().orEmpty()

    override fun equals(other: Any?): Boolean =
        other is GameItem && other.id == id && other.playUrl == playUrl

    override fun hashCode(): Int = id.hashCode() * 31 + playUrl.hashCode()

    companion object {
        const val SOURCE_HOME = "home"
        const val SOURCE_CATEGORY = "category"
        const val SOURCE_SEARCH = "search"
        const val SOURCE_RECENT = "recent"
        const val SOURCE_FAVORITE = "favorite"
        /** 第三方源插件（gamelist.xml 等），见 data/plugin */
        const val SOURCE_PLUGIN = "plugin"
        /** U盘本地游戏（自动扫描挂载卷），见 data/usb/UsbScanner */
        const val SOURCE_USB = "usb"
    }
}
