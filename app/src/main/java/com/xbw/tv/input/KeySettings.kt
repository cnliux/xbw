package com.xbw.tv.input

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 按键映射的全局配置（内存镜像 + SharedPreferences 持久化）。
 *
 * 为什么用 SharedPreferences 而不是只用 Room：
 *   游戏页 onKeyDown 需要同步查表（连按时每帧多次），Room 是 suspend 的，会掉帧。
 *   Room 的 key 表由设置页写入并同步到这里；运行时热路径只读本类的内存 Map。
 *
 * 三层映射设计见 [GameButton] 注释。
 */
object KeySettings {

    private const val PREF = "keymap"
    private const val PHYS_PREFIX = "p_"
    private const val LOG_PREFIX = "l_"
    private const val KEY_MENU_HOTKEY = "menu_hotkey"

    // ------------------------------------------------------------------
    // 默认物理映射：Android KeyEvent → GameButton
    // 覆盖 Xbox / PS4/PS5 / 通用 Android 手柄；设置页可逐键覆盖。
    // ------------------------------------------------------------------
    val DEFAULT_PHYSICAL: Map<Int, GameButton> = buildMap {
        // 面键（Android 标准手柄语义：A=确认 B=取消）
        put(KeyEvent.KEYCODE_BUTTON_A, GameButton.A)      // Xbox A / PS ✕
        put(KeyEvent.KEYCODE_BUTTON_B, GameButton.B)      // Xbox B / PS ○
        put(KeyEvent.KEYCODE_BUTTON_X, GameButton.X)      // Xbox X / PS □
        put(KeyEvent.KEYCODE_BUTTON_Y, GameButton.Y)      // Xbox Y / PS △
        // 肩键（L2/R2 默认也映射到 L/R，FC 只有 8 键，多余键合并更顺手）
        put(KeyEvent.KEYCODE_BUTTON_L1, GameButton.L)
        put(KeyEvent.KEYCODE_BUTTON_R1, GameButton.R)
        put(KeyEvent.KEYCODE_BUTTON_L2, GameButton.L)
        put(KeyEvent.KEYCODE_BUTTON_R2, GameButton.R)
        put(KeyEvent.KEYCODE_BUTTON_THUMBL, GameButton.L3)
        put(KeyEvent.KEYCODE_BUTTON_THUMBR, GameButton.R3)
        // 功能键
        put(KeyEvent.KEYCODE_BUTTON_START, GameButton.START)
        put(KeyEvent.KEYCODE_BUTTON_SELECT, GameButton.SELECT)
        // 遥控器/键盘的确认键当 A
        put(KeyEvent.KEYCODE_DPAD_CENTER, GameButton.A)
        put(KeyEvent.KEYCODE_ENTER, GameButton.START)
        // 十字键
        put(KeyEvent.KEYCODE_DPAD_UP, GameButton.UP)
        put(KeyEvent.KEYCODE_DPAD_DOWN, GameButton.DOWN)
        put(KeyEvent.KEYCODE_DPAD_LEFT, GameButton.LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, GameButton.RIGHT)
        // 部分国产手柄把方向映射成小键盘
        put(KeyEvent.KEYCODE_NUMPAD_8, GameButton.UP)
        put(KeyEvent.KEYCODE_NUMPAD_2, GameButton.DOWN)
        put(KeyEvent.KEYCODE_NUMPAD_4, GameButton.LEFT)
        put(KeyEvent.KEYCODE_NUMPAD_6, GameButton.RIGHT)
        // USB 键盘直连盒子时可用
        put(KeyEvent.KEYCODE_W, GameButton.UP)
        put(KeyEvent.KEYCODE_S, GameButton.DOWN)
        put(KeyEvent.KEYCODE_A, GameButton.LEFT)
        put(KeyEvent.KEYCODE_D, GameButton.RIGHT)
        put(KeyEvent.KEYCODE_Z, GameButton.B)
        put(KeyEvent.KEYCODE_X, GameButton.A)
        put(KeyEvent.KEYCODE_C, GameButton.X)
        put(KeyEvent.KEYCODE_V, GameButton.Y)
        put(KeyEvent.KEYCODE_SPACE, GameButton.START)
        put(KeyEvent.KEYCODE_TAB, GameButton.SELECT)

        // ── 兼容市面各类 USB 手柄的“别名键” ──
        // 部分国产 HID 手柄/接收器把面键上报为 UNKNOWN 或 1~4 数字键，
        // 这里给出常见等价映射；玩家也可在设置页逐键覆盖。
        put(KeyEvent.KEYCODE_BUTTON_1, GameButton.A)
        put(KeyEvent.KEYCODE_BUTTON_2, GameButton.B)
        put(KeyEvent.KEYCODE_BUTTON_3, GameButton.X)
        put(KeyEvent.KEYCODE_BUTTON_4, GameButton.Y)
        put(KeyEvent.KEYCODE_BUTTON_5, GameButton.L)
        put(KeyEvent.KEYCODE_BUTTON_6, GameButton.R)
        // 数字键盘/主键盘回退（部分手柄键盘模式把面键上报为数字键）
        put(KeyEvent.KEYCODE_1, GameButton.A)
        put(KeyEvent.KEYCODE_2, GameButton.B)
        put(KeyEvent.KEYCODE_3, GameButton.X)
        put(KeyEvent.KEYCODE_4, GameButton.Y)
        put(KeyEvent.KEYCODE_5, GameButton.L)
        put(KeyEvent.KEYCODE_6, GameButton.R)
        // 鼠标左/右/中键（USB 鼠标当输入设备）
        put(KeyEvent.KEYCODE_DPAD_CENTER, GameButton.A)
    }

    /**
     * 菜单热键：呼出 App 原生工具条的按键。
     *
     * ⚠️ 刻意**不**用 START 做默认值：NES 的 Start 本身就是"开始/暂停"游戏功能键，
     *   若同时当菜单键，玩家按 Start 开始游戏时菜单会被顶出来。
     *   默认用 **SELECT（Select/Back 键）**，并保留两条备用路径：
     *   长按 START 呼出菜单、遥控器 BACK 呼出菜单（见 NativeGameActivity）。
     */
    var menuHotkey: Int = KeyEvent.KEYCODE_BUTTON_SELECT
        private set

    // 运行时热路径映射（同步读）
    private val physical = HashMap(DEFAULT_PHYSICAL)
    private val logical = HashMap<GameButton, GameButton.WebKey>()

    private val _version = MutableStateFlow(0)
    /** 每次变更自增，设置页监听刷新 */
    val version: StateFlow<Int> get() = _version

    private var pref: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        pref = p
        menuHotkey = p.getInt(KEY_MENU_HOTKEY, KeyEvent.KEYCODE_BUTTON_SELECT)

        physical.clear()
        physical.putAll(DEFAULT_PHYSICAL)
        for ((key, value) in p.all) {
            if (!key.startsWith(PHYS_PREFIX)) continue
            val keyCode = key.removePrefix(PHYS_PREFIX).toIntOrNull() ?: continue
            val btn = GameButton.fromId(value as? String)
            if (btn != null) physical[keyCode] = btn else physical.remove(keyCode)
        }

        logical.clear()
        for (btn in GameButton.entries) {
            val raw = p.getString(LOG_PREFIX + btn.id, null)
            val parts = raw?.split("|")
            logical[btn] = if (parts != null && parts.size == 3) {
                GameButton.WebKey(parts[0], parts[1], parts[2].toIntOrNull() ?: btn.webKeyCode)
            } else {
                btn.webKey()
            }
        }
        _version.value++
    }

    /** 物理键 → 逻辑键；未映射返回 null（该键交给系统/UI 焦点系统） */
    fun logicalOf(keyCode: Int): GameButton? = physical[keyCode]

    /** 逻辑键 → 网页键盘描述 */
    fun webKeyOf(btn: GameButton): GameButton.WebKey = logical[btn] ?: btn.webKey()

    fun snapshotPhysical(): Map<Int, GameButton> = physical.toMap()
    fun snapshotLogical(): Map<GameButton, GameButton.WebKey> = logical.toMap()

    fun setPhysical(keyCode: Int, button: GameButton?) {
        if (button == null) physical.remove(keyCode) else physical[keyCode] = button
        pref!!.edit().apply {
            if (button == null) remove(PHYS_PREFIX + keyCode)
            else putString(PHYS_PREFIX + keyCode, button.id)
        }.apply()
        _version.value++
    }

    fun setLogical(button: GameButton, web: GameButton.WebKey) {
        logical[button] = web
        pref!!.edit().putString(LOG_PREFIX + button.id, "${web.code}|${web.key}|${web.keyCode}")
            .apply()
        _version.value++
    }

    fun setMenuHotkey(keyCode: Int) {
        menuHotkey = keyCode
        pref!!.edit().putInt(KEY_MENU_HOTKEY, keyCode).apply()
        _version.value++
    }

    /** 恢复出厂映射 */
    fun resetAll() {
        pref?.edit()?.clear()?.apply()
        physical.clear()
        physical.putAll(DEFAULT_PHYSICAL)
        logical.clear()
        for (btn in GameButton.entries) logical[btn] = btn.webKey()
        menuHotkey = KeyEvent.KEYCODE_BUTTON_SELECT
        _version.value++
    }

    /** 物理键可读名（设置页展示） */
    fun describeKeyCode(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A / ✕"
        KeyEvent.KEYCODE_BUTTON_B -> "B / ○"
        KeyEvent.KEYCODE_BUTTON_X -> "X / □"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y / △"
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB / L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "RB / R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "LT / L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "RT / R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "左摇杆 L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "右摇杆 R3"
        KeyEvent.KEYCODE_BUTTON_START -> "Start / Options"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select / Back"
        KeyEvent.KEYCODE_DPAD_UP -> "方向 ↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "方向 ↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "方向 ←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "方向 →"
        KeyEvent.KEYCODE_DPAD_CENTER -> "方向中心 / OK"
        KeyEvent.KEYCODE_BACK -> "遥控器 BACK"
        KeyEvent.KEYCODE_MENU -> "遥控器 MENU"
        KeyEvent.KEYCODE_HOME -> "系统 HOME"
        KeyEvent.KEYCODE_BUTTON_MODE -> "手柄 MODE/HOME"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}
