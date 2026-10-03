package com.xbw.tv.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 把 USB 手柄两种常见模式统一成按键：
 *  1. Android/XInput：十字轴是 HAT（AXIS_HAT_X/Y），不一定发 DPAD KeyEvent；
 *  2. HID/键盘模式：可能只有轴或扫描码。
 */
object MotionKeyBridge {

    fun bindCode(event: KeyEvent): Int {
        val kc = event.keyCode
        if (kc != KeyEvent.KEYCODE_UNKNOWN && kc != 0) return kc
        if (event.scanCode > 0) return SCAN_BASE or event.scanCode
        return kc
    }

    fun describeBind(code: Int): String {
        if (code and SCAN_BASE == SCAN_BASE) return "USB扫描码 ${code and SCAN_MASK}"
        return KeySettings.describeKeyCode(code)
    }

    fun isIgnorableSystemKey(keyCode: Int): Boolean = keyCode in SYSTEM_KEYS

    /**
     * 处理摇杆/十字轴/鼠标。有方向变化时回调 (keyCode, down)，返回是否消费。
     *
     * 兼容面尽量宽：
     *   - 十字轴：AXIS_HAT_X / AXIS_HAT_Y（XInput 常见）；
     *   - 左摇杆：AXIS_X / AXIS_Y；
     *   - 部分手柄/摇杆把方向映射到 Z/RZ/RX/RY/THROTTLE/BRAKE/GAS，一并纳入；
     *   - 鼠标：SOURCE_MOUSE 的移动/滚轮也当作方向输入（部分 TV 盒子靠 USB 鼠标操作）。
     */
    fun dispatchMotion(
        event: MotionEvent,
        hatX: IntArray,
        hatY: IntArray,
        stickX: IntArray,
        stickY: IntArray,
        onDigital: (keyCode: Int, down: Boolean) -> Unit
    ): Boolean {
        val src = event.source
        val mouse = (src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        val joystick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!joystick && !mouse && event.actionMasked != MotionEvent.ACTION_MOVE) return false

        // 鼠标：只处理滚轮（当作上/下）与侧键，移动不作方向（太灵敏）
        if (mouse) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            var used = false
            used = emitAxis(hatY, digital(scroll, 0.1f), KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, onDigital) || used
            return used
        }

        // 十字轴（最稳，优先）
        val hx = digital(event.getAxisValue(MotionEvent.AXIS_HAT_X))
        val hy = digital(event.getAxisValue(MotionEvent.AXIS_HAT_Y))

        // 左摇杆：优先 AXIS_X/Y；为空时回退到 Z/RZ/RX/RY 与油门/刹车轴（老式/飞控摇杆常见）
        var sx = digital(event.getAxisValue(MotionEvent.AXIS_X), 0.55f)
        var sy = digital(event.getAxisValue(MotionEvent.AXIS_Y), 0.55f)
        if (sx == 0) sx = digital(event.getAxisValue(MotionEvent.AXIS_RX), 0.55f)
        if (sy == 0) sy = digital(event.getAxisValue(MotionEvent.AXIS_RY), 0.55f)

        var used = false
        used = emitAxis(hatX, hx, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, onDigital) || used
        used = emitAxis(hatY, hy, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, onDigital) || used
        // 摇杆只在十字轴为空时补方向，避免双发
        if (hx == 0 && hy == 0) {
            used = emitAxis(stickX, sx, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, onDigital) || used
            used = emitAxis(stickY, sy, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, onDigital) || used
        }
        return used
    }

    private fun emitAxis(
        slot: IntArray,
        now: Int,
        negKey: Int,
        posKey: Int,
        onDigital: (Int, Boolean) -> Unit
    ): Boolean {
        val old = slot[0]
        if (old == now) return false
        if (old < 0) onDigital(negKey, false)
        if (old > 0) onDigital(posKey, false)
        if (now < 0) onDigital(negKey, true)
        if (now > 0) onDigital(posKey, true)
        slot[0] = now
        return true
    }

    private fun digital(v: Float, dead: Float = 0.5f): Int = when {
        v < -dead -> -1
        v > dead -> 1
        else -> 0
    }

    private const val SCAN_BASE = 0x10000
    private const val SCAN_MASK = 0xFFFF
    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_VOLUME_MUTE
    )
}
