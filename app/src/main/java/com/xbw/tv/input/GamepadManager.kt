package com.xbw.tv.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 手柄连接管理器(方案第七步"手柄热插拔检测")。
 *
 * 要点:
 *  - InputManager.registerInputDeviceListener 感知增删,无需任何 USB/蓝牙权限;
 *  - 部分盒子 SOURCE_GAMEPAD 不置位,所以同时接受 SOURCE_JOYSTICK,
 *    再用"是否具备 BUTTON_A/B"区分真手柄与遥控器(遥控器只有 DPAD);
 *  - StateFlow 广播手柄列表,设置页与游戏页角标 collect 即可;
 *  - 可选振动反馈,失败静默(部分 TV 驱动不支持)。
 */
class GamepadManager(context: Context) {

    private val appContext = context.applicationContext
    private val inputManager: InputManager? =
        appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager

    data class PadInfo(
        val id: Int,
        val name: String,
        val vendor: Int,
        val product: Int,
        val hasVibrator: Boolean,
        val descriptor: String,
        val kinds: String = ""
    )

    private val _state = MutableStateFlow<List<PadInfo>>(emptyList())
    val state: StateFlow<List<PadInfo>> = _state

    /** 兼容方案文档的命名:主手柄名,无手柄 = null */
    val primaryName: String? get() = _state.value.firstOrNull()?.name

    private val handler = Handler(Looper.getMainLooper())

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        @Deprecated("API 以下兼容")
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    fun register() {
        refresh()
        try {
            inputManager?.registerInputDeviceListener(deviceListener, handler)
        } catch (ignore: Exception) {
        }
    }

    fun unregister() {
        try {
            inputManager?.unregisterInputDeviceListener(deviceListener)
        } catch (ignore: Exception) {
        }
    }

    /** 重新扫描全部输入设备 */
    fun refresh() {
        val im = inputManager ?: return
        val found = mutableListOf<PadInfo>()
        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (isGamepad(dev)) {
                found += PadInfo(
                    id = id,
                    name = dev.name ?: "未知手柄",
                    vendor = dev.vendorId,
                    product = dev.productId,
                    hasVibrator = dev.vibrator?.hasVibrator() == true,
                    descriptor = dev.descriptor ?: "",
                    kinds = describeKinds(dev)
                )
            }
        }
        _state.value = found.sortedBy { it.id }
    }

    /**
     * 判定“这是手柄吗”。
     *
     * 市面上的 USB 设备五花八门：Xbox/PS 原生、国产 HID 手柄、键盘模式手柄、
     * 无线 2.4G 接收器、飞控/赛车摇杆...它们上报的 sources 和按键各不相同。
     * 这里采用「宽进」策略：只要具备任一游戏输入特征就当作可选设备：
     *   - SOURCE_GAMEPAD / SOURCE_JOYSTICK：标准手柄/摇杆；
     *   - 声明了任意 BUTTON_A~THUMBR/START/SELECT：键盘模式柄也能识别；
     * 只排除触摸屏/鼠标（它们另有作用，但鼠标也允许当游戏输入，见 shouldCapture）。
     */
    fun isGamepad(device: InputDevice?): Boolean {
        if (device == null) return false
        val src = device.sources
        val isGamepad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val isJoystick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isGamepad || isJoystick) return true
        // 键盘模式 / 自定义 HID 的手柄：虽然没声明 GAMEPAD，但带标准游戏按键
        if ((src and InputDevice.SOURCE_KEYBOARD) != 0) {
            val keys = device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_BUTTON_4,
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V
            )
            if (keys != null && keys.any { it }) return true
        }
        // 蓝牙/USB 接收器有时只报 UNKNOWN，但有摇杆轴能力
        if (device.motionRanges != null && device.motionRanges.any {
                it.axis == MotionEvent.AXIS_HAT_X || it.axis == MotionEvent.AXIS_HAT_Y ||
                        it.axis == MotionEvent.AXIS_X || it.axis == MotionEvent.AXIS_Y
            }
        ) return true
        return false
    }

    /** 设备分类（调试页显示用） */
    fun describeKinds(device: InputDevice?): String {
        if (device == null) return "?"
        val src = device.sources
        val kinds = mutableListOf<String>()
        if ((src and InputDevice.SOURCE_GAMEPAD) != 0) kinds += "GAMEPAD"
        if ((src and InputDevice.SOURCE_JOYSTICK) != 0) kinds += "JOYSTICK"
        if ((src and InputDevice.SOURCE_KEYBOARD) != 0) kinds += "KEYBOARD"
        if ((src and InputDevice.SOURCE_MOUSE) != 0) kinds += "MOUSE"
        if ((src and InputDevice.SOURCE_DPAD) != 0) kinds += "DPAD"
        if ((src and InputDevice.SOURCE_TOUCHSCREEN) != 0) kinds += "TOUCH"
        return if (kinds.isEmpty()) "OTHER" else kinds.joinToString("+")
    }

    /** 调试页:所有输入设备一览 */
    fun snapshotAllDevices(): List<String> {
        val im = inputManager ?: return emptyList()
        return try {
            val ids = im.inputDeviceIds ?: IntArray(0)
            ids.toList().mapNotNull { id ->
                val d = im.getInputDevice(id) ?: return@mapNotNull null
                "${d.id} · ${d.name} · [${describeKinds(d)}] · src=0x${Integer.toHexString(d.sources)} · gamepad=${isGamepad(d)}"
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 手柄振动反馈;驱动不支持时静默 */
    @Suppress("DEPRECATION")
    fun vibrate(deviceId: Int, ms: Long = 30) {
        try {
            val dev = InputDevice.getDevice(deviceId) ?: return
            val vib: Vibrator = dev.vibrator ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(ms)
            }
        } catch (ignore: Exception) {
        }
    }

    companion object {
        @Volatile
        private var instance: GamepadManager? = null

        /** 全局单例(register 后监听热插拔) */
        fun get(context: Context): GamepadManager =
            instance ?: synchronized(this) {
                instance ?: GamepadManager(context).also {
                    it.register()
                    instance = it
                }
            }
    }
}
