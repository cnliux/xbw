package com.xbw.tv.input

/**
 * 逻辑游戏按键。
 *
 * 引入这一层的目的是把「物理键位千奇百怪」和「网页只认几个固定键盘键」解耦：
 *
 *   物理 KeyEvent(KEYCODE_BUTTON_A)
 *        │  PhysicalMap（设置页可改，解决手柄键位错乱）
 *        ▼
 *   GameButton.A  ── LogicalKey（设置页可改，解决站点默认键位不合手）──▶ 网页 KeyboardEvent
 *
 * 站点模拟器的按键表比较的是 KeyboardEvent.code（pclib.js 里能看到 'KeyZ'/'KeyX'/'KeyS'/'Enter'
 * 等字符串常量），所以 [webCode] 是主判据，[webKey]/[webKeyCode] 是给老页面的兜底。
 */
enum class GameButton(
    val id: String,
    val label: String,
    /** KeyboardEvent.code —— 站点主判据 */
    val webCode: String,
    /** KeyboardEvent.key */
    val webKey: String,
    /** legacy keyCode */
    val webKeyCode: Int,
    /** 是否参与「方向键连发/长按」逻辑 */
    val isDpad: Boolean = false
) {
    UP("up", "↑ 方向上", "ArrowUp", "ArrowUp", 38, true),
    DOWN("down", "↓ 方向下", "ArrowDown", "ArrowDown", 40, true),
    LEFT("left", "← 方向左", "ArrowLeft", "ArrowLeft", 37, true),
    RIGHT("right", "→ 方向右", "ArrowRight", "ArrowRight", 39, true),

    /** 默认 → 网页 X（方案第十节的映射：手柄 A → 88/X） */
    A("a", "A 键（确认/跳）", "KeyX", "x", 88),
    /** 默认 → 网页 Z */
    B("b", "B 键（辅助）", "KeyZ", "z", 90),
    X("x", "X 键", "KeyA", "a", 65),
    Y("y", "Y 键", "KeyS", "s", 83),

    L("l", "L / L2", "KeyQ", "q", 81),
    R("r", "R / R2", "KeyW", "w", 87),
    L3("l3", "左摇杆按下", "KeyE", "e", 69),
    R3("r3", "右摇杆按下", "KeyT", "t", 84),

    /** NES Select → Shift(16) */
    SELECT("select", "Select（投币/功能）", "ShiftLeft", "Shift", 16),
    /** NES Start → Enter(13)。注意：菜单热键与它分开，见 KeySettings.menuHotkey */
    START("start", "Start（开始/暂停）", "Enter", "Enter", 13);

    /** 注入网页用的最小描述 */
    data class WebKey(val code: String, val key: String, val keyCode: Int)

    fun webKey() = WebKey(webCode, webKey, webKeyCode)

    companion object {
        fun fromId(id: String?): GameButton? = entries.firstOrNull { it.id == id }

        /** 逻辑键 → 默认网页键 */
        val defaultLogicalMap: Map<GameButton, WebKey>
            get() = entries.associateWith { it.webKey() }
    }
}
