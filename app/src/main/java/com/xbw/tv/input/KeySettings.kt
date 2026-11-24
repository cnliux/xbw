package com.xbw.tv.input

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 按键映射配置（内存镜像 + SharedPreferences 持久化）。
 *
 * 为什么用 SharedPreferences 而不是只用 Room：
 *   游戏页 onKeyDown 需要同步查表（连按时每帧多次），Room 是 suspend 的，会掉帧。
 *   Room 的 key 表由设置页写入并同步到这里；运行时热路径只读本类的内存 Map。
 *
 * 存储分两层：
 *   1. 全局映射 —— 老版本 `p_* / l_*`，仍是所有玩家的默认值，保证升级后手感不变；
 *   2. 玩家覆盖 —— `player1_p_* / player1_l_*`，只影响对应玩家（1P~4P）。
 *      玩家没改过的键继承全局；显式解绑用 [CLEAR] 标记，避免又落回全局。
 *
 * 三层映射设计见 [GameButton] 注释。
 */
object KeySettings {

    /** libretro 前端玩家上限，与 NativeGameActivity 的 MAX_PLAYER 保持一致 */
    const val PLAYER_COUNT = 4

    private const val PREF = "keymap"
    private const val PHYS_PREFIX = "p_"
    private const val LOG_PREFIX = "l_"
    private const val KEY_MENU_HOTKEY = "menu_hotkey"

    private const val PLAYER_KEY_PREFIX = "player"
    private const val PLAYER_PHYS_SUFFIX = "_p_"
    private const val PLAYER_LOG_SUFFIX = "_l_"

    /** SharedPreferences 不能存 null：该值表示「本玩家显式解除这个键的绑定」 */
    private const val CLEAR = "-"

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

    /** 玩家独立覆盖：键码 → GameButton.id 或 [CLEAR]（0 基下标，对应 1P~4P） */
    private val playerPhysical = Array(PLAYER_COUNT) { HashMap<Int, String>() }
    /** 玩家独立覆盖：逻辑键 → 序列化 WebKey（0 基下标，对应 1P~4P） */
    private val playerLogical = Array(PLAYER_COUNT) { HashMap<GameButton, String>() }

    private val _version = MutableStateFlow(0)
    /** 每次变更自增，设置页监听刷新 */
    val version: StateFlow<Int> get() = _version

    private var pref: android.content.SharedPreferences? = null

    private fun requirePrefs(): android.content.SharedPreferences =
        pref ?: error("KeySettings.init(context) must be called before use")

    private fun checkPlayer(player: Int): Int {
        require(player in 0 until PLAYER_COUNT) { "player must be 0..${PLAYER_COUNT - 1}" }
        return player
    }

    private fun playerPhysPrefix(player: Int): String =
        "$PLAYER_KEY_PREFIX${player + 1}$PLAYER_PHYS_SUFFIX"

    private fun playerLogPrefix(player: Int): String =
        "$PLAYER_KEY_PREFIX${player + 1}$PLAYER_LOG_SUFFIX"

    private fun serialize(web: GameButton.WebKey): String =
        "${web.code}|${web.key}|${web.keyCode}"

    private fun parseWebKey(raw: String?, fallback: GameButton.WebKey): GameButton.WebKey {
        val parts = raw?.split("|")
        return if (parts?.size == 3) {
            GameButton.WebKey(parts[0], parts[1], parts[2].toIntOrNull() ?: fallback.keyCode)
        } else {
            fallback
        }
    }

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        pref = p
        menuHotkey = p.getInt(KEY_MENU_HOTKEY, KeyEvent.KEYCODE_BUTTON_SELECT)

        physical.clear()
        physical.putAll(DEFAULT_PHYSICAL)
        logical.clear()
        for (btn in GameButton.entries) logical[btn] = btn.webKey()

        for ((key, value) in p.all) {
            when {
                key.startsWith(PHYS_PREFIX) -> {
                    val keyCode = key.removePrefix(PHYS_PREFIX).toIntOrNull() ?: continue
                    val btn = GameButton.fromId(value as? String)
                    if (btn != null) physical[keyCode] = btn else physical.remove(keyCode)
                }
                key.startsWith(LOG_PREFIX) -> {
                    val btn = GameButton.fromId(key.removePrefix(LOG_PREFIX)) ?: continue
                    logical[btn] = parseWebKey(value as? String, btn.webKey())
                }
            }
        }

        for (player in 0 until PLAYER_COUNT) {
            val physPrefix = playerPhysPrefix(player)
            val logPrefix = playerLogPrefix(player)
            val phys = playerPhysical[player]
            val log = playerLogical[player]
            phys.clear()
            log.clear()
            for ((key, value) in p.all) {
                when {
                    key.startsWith(physPrefix) -> {
                        val keyCode = key.removePrefix(physPrefix).toIntOrNull() ?: continue
                        val raw = value as? String ?: continue
                        if (raw == CLEAR || GameButton.fromId(raw) != null) phys[keyCode] = raw
                    }
                    key.startsWith(logPrefix) -> {
                        val btn = GameButton.fromId(key.removePrefix(logPrefix)) ?: continue
                        val raw = value as? String ?: continue
                        log[btn] = raw
                    }
                }
            }
        }
        _version.value++
    }

    /** 全局物理键 → 逻辑键；保留旧接口，未指定玩家时仍按全局表解释 */
    fun logicalOf(keyCode: Int): GameButton? = physical[keyCode]

    /**
     * 指定玩家物理键 → 逻辑键。
     * 玩家覆盖优先；没改过的键继承全局；[CLEAR] 表示本玩家显式不用这个键。
     */
    fun logicalOf(player: Int, keyCode: Int): GameButton? {
        val p = checkPlayer(player)
        return when (val raw = playerPhysical[p][keyCode]) {
            null -> physical[keyCode]
            CLEAR -> null
            else -> GameButton.fromId(raw) ?: physical[keyCode]
        }
    }

    /** 是否有任意玩家把该键码当游戏键；游戏页用它避免遥控杂键白占玩家槽 */
    fun isMappedForAnyPlayer(keyCode: Int): Boolean {
        // logicalOf 内部已处理全局回落与玩家显式解绑，不能在这里用 physical 提前返回，
        // 否则「所有玩家都解绑了某个默认键」时仍会误认为有效。
        for (player in 0 until PLAYER_COUNT) {
            if (logicalOf(player, keyCode) != null) return true
        }
        return false
    }

    /** 全局逻辑键 → 网页键盘描述 */
    fun webKeyOf(btn: GameButton): GameButton.WebKey = logical[btn] ?: btn.webKey()

    /** 指定玩家逻辑键 → 网页键盘描述（网页码未来若恢复多玩家注入也直接可用） */
    fun webKeyOf(player: Int, btn: GameButton): GameButton.WebKey {
        val p = checkPlayer(player)
        val raw = playerLogical[p][btn] ?: return webKeyOf(btn)
        return parseWebKey(raw, webKeyOf(btn))
    }

    fun snapshotPhysical(): Map<Int, GameButton> = physical.toMap()

    /** 指定玩家的**有效**物理表（全局 + 覆盖），设置页据此展示绑定 */
    fun snapshotPhysical(player: Int): Map<Int, GameButton> {
        val p = checkPlayer(player)
        val out = HashMap(physical)
        for ((keyCode, raw) in playerPhysical[p]) {
            if (raw == CLEAR) out.remove(keyCode)
            else GameButton.fromId(raw)?.let { out[keyCode] = it }
        }
        return out
    }

    fun snapshotLogical(): Map<GameButton, GameButton.WebKey> = logical.toMap()

    fun snapshotLogical(player: Int): Map<GameButton, GameButton.WebKey> {
        val p = checkPlayer(player)
        val out = HashMap(logical)
        for ((btn, raw) in playerLogical[p]) {
            out[btn] = parseWebKey(raw, out[btn] ?: btn.webKey())
        }
        return out
    }

    /** 修改全局默认（兼容旧接口；玩家已有独立覆盖时不受影响） */
    fun setPhysical(keyCode: Int, button: GameButton?) {
        val store = requirePrefs()
        if (button == null) physical.remove(keyCode) else physical[keyCode] = button
        store.edit().apply {
            if (button == null) remove(PHYS_PREFIX + keyCode)
            else putString(PHYS_PREFIX + keyCode, button.id)
        }.apply()
        _version.value++
    }

    /** 修改指定玩家的物理键；传 null = 本玩家显式解绑（不再回落到全局） */
    fun setPhysical(player: Int, keyCode: Int, button: GameButton?) {
        val p = checkPlayer(player)
        val raw = button?.id ?: CLEAR
        playerPhysical[p][keyCode] = raw
        requirePrefs().edit().putString(playerPhysPrefix(p) + keyCode, raw).apply()
        _version.value++
    }

    fun setLogical(button: GameButton, web: GameButton.WebKey) {
        val raw = serialize(web)
        logical[button] = web
        requirePrefs().edit().putString(LOG_PREFIX + button.id, raw).apply()
        _version.value++
    }

    fun setLogical(player: Int, button: GameButton, web: GameButton.WebKey) {
        val p = checkPlayer(player)
        val raw = serialize(web)
        playerLogical[p][button] = raw
        requirePrefs().edit().putString(playerLogPrefix(p) + button.id, raw).apply()
        _version.value++
    }

    /** 清掉某玩家的独立设置，恢复继承全局默认（其他玩家不动） */
    fun resetPlayer(player: Int) {
        val p = checkPlayer(player)
        val store = requirePrefs()
        val physPrefix = playerPhysPrefix(p)
        val logPrefix = playerLogPrefix(p)
        store.edit().apply {
            store.all.keys
                .filter { it.startsWith(physPrefix) || it.startsWith(logPrefix) }
                .forEach { remove(it) }
        }.apply()
        playerPhysical[p].clear()
        playerLogical[p].clear()
        _version.value++
    }

    /** 把源玩家的有效配置完整复制给目标玩家（含显式解绑），之后两者各自独立 */
    fun copyPlayer(from: Int, to: Int) {
        val src = checkPlayer(from)
        val dst = checkPlayer(to)
        if (src == dst) return
        val store = requirePrefs()

        val phys = HashMap<Int, String>()
        for ((keyCode, btn) in physical) phys[keyCode] = btn.id
        for ((keyCode, raw) in playerPhysical[src]) phys[keyCode] = raw

        val log = HashMap<GameButton, String>()
        for ((btn, web) in logical) log[btn] = serialize(web)
        for ((btn, raw) in playerLogical[src]) log[btn] = raw

        val dstPhysPrefix = playerPhysPrefix(dst)
        val dstLogPrefix = playerLogPrefix(dst)
        store.edit().apply {
            store.all.keys
                .filter { it.startsWith(dstPhysPrefix) || it.startsWith(dstLogPrefix) }
                .forEach { remove(it) }
            for ((keyCode, raw) in phys) putString(dstPhysPrefix + keyCode, raw)
            for ((btn, raw) in log) putString(dstLogPrefix + btn.id, raw)
        }.apply()

        playerPhysical[dst].clear()
        playerPhysical[dst].putAll(phys)
        playerLogical[dst].clear()
        playerLogical[dst].putAll(log)
        _version.value++
    }

    fun setMenuHotkey(keyCode: Int) {
        menuHotkey = keyCode
        requirePrefs().edit().putInt(KEY_MENU_HOTKEY, keyCode).apply()
        _version.value++
    }

    /** 恢复出厂映射：全局与 1P~4P 独立设置一起清空 */
    fun resetAll() {
        pref?.edit()?.clear()?.apply()
        physical.clear()
        physical.putAll(DEFAULT_PHYSICAL)
        logical.clear()
        for (btn in GameButton.entries) logical[btn] = btn.webKey()
        for (player in 0 until PLAYER_COUNT) {
            playerPhysical[player].clear()
            playerLogical[player].clear()
        }
        menuHotkey = KeyEvent.KEYCODE_BUTTON_SELECT
        _version.value++
    }

    /** 展示用玩家名：0→1P */
    fun playerLabel(player: Int): String = "${checkPlayer(player) + 1}P"

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
