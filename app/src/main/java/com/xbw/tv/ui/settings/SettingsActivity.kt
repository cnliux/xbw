package com.xbw.tv.ui.settings

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xbw.tv.R
import com.xbw.tv.XbwApplication
import com.xbw.tv.data.local.AppDatabase
import com.xbw.tv.databinding.ActivitySettingsBinding
import com.xbw.tv.input.GamepadManager
import com.xbw.tv.input.KeySettings
import com.xbw.tv.input.MotionKeyBridge
import com.xbw.tv.ui.common.Nav
import com.xbw.tv.ui.diagnose.DiagnoseActivity
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * 设置页（方案模块 4）：
 *  - 手柄状态（热插拔实时刷新：GamepadManager StateFlow + 焦点行 click 时再扫一次）；
 *  - 菜单呼出键捕获：点行后进入"捕获模式"，下一次按任意手柄键即成为菜单键（方案 7.3）；
 *  - 注入方式切换（合成 / 直通）；
 *  - 缓存与最近玩过管理；按键映射重置。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var gamepads: GamepadManager
    private var capturingHotkey = false
    private var hotkeyArmedAt = 0L
    private val hatX = intArrayOf(0)
    private val hatY = intArrayOf(0)
    private val stickX = intArrayOf(0)
    private val stickY = intArrayOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gamepads = GamepadManager.get(this)

        binding.rowGamepad.setOnClickListener { gamepads.refresh(); refreshGamepad() }
        binding.rowHotkey.setOnClickListener { startHotkeyCapture() }
        binding.rowMapping.setOnClickListener {
            startActivity(Intent(this, KeyMappingActivity::class.java))
        }
        binding.rowDiagnose.setOnClickListener {
            startActivity(Intent(this, DiagnoseActivity::class.java))
        }
        binding.rowClearCache.setOnClickListener {
            lifecycleScope.launch {
                XbwApplication.repository(application).clearCache()
                Nav.toast(this@SettingsActivity, getString(R.string.toast_cleared))
                refreshCounters()
            }
        }
        binding.rowClearRecent.setOnClickListener {
            lifecycleScope.launch {
                AppDatabase.get(this@SettingsActivity).recentPlayDao().clear()
                Nav.toast(this@SettingsActivity, getString(R.string.toast_cleared_recent))
                refreshCounters()
            }
        }
        binding.rowResetMap.setOnClickListener {
            KeySettings.resetAll()
            Nav.toast(this@SettingsActivity, getString(R.string.toast_reset_done))
            refreshStatic()
        }
        binding.rowAbout.setOnClickListener {
            Nav.toast(this@SettingsActivity, getString(R.string.settings_about_body))
        }

        gamepads.state
        refreshStatic()
        refreshGamepad()
        refreshCounters()
    }

    private fun refreshStatic() {
        binding.valHotkey.text = KeySettings.describeKeyCode(KeySettings.menuHotkey)
    }

    private fun refreshGamepad() {
        val pads = gamepads.state.value
        if (pads.isEmpty()) {
            binding.valGamepad.text = getString(R.string.gamepad_none)
        } else {
            val names = pads.joinToString(" / ") { it.name }
            binding.valGamepad.text = getString(R.string.gamepad_connected_fmt, pads.size, names)
        }
    }

    private fun refreshCounters() {
        lifecycleScope.launch {
            val (games, recents) = XbwApplication.repository(application).cacheStats()
            binding.valCacheCount.text = "$games 条"
            binding.valRecentCount.text = "$recents 款"
        }
    }

    // ------------------------------------------------------------------
    // 菜单热键捕获：进入捕获模式后，onKeyDown 抓第一个非 BACK 的物理键
    // ------------------------------------------------------------------

    private fun startHotkeyCapture() {
        capturingHotkey = true
        hotkeyArmedAt = SystemClock.uptimeMillis() + 280
        binding.hotkeyCaptureHint.visibility = View.VISIBLE
        binding.hotkeyCaptureHint.text = "请按下菜单键（USB 手柄/键盘两种模式均可，BACK 取消）"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capturingHotkey) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (event.repeatCount > 0) return true
            applyHotkey(MotionKeyBridge.bindCode(event))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (capturingHotkey) {
            MotionKeyBridge.dispatchMotion(event, hatX, hatY, stickX, stickY) { code, down ->
                if (down) applyHotkey(code)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun applyHotkey(keyCode: Int) {
        if (MotionKeyBridge.isIgnorableSystemKey(keyCode)) return
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            capturingHotkey = false
            binding.hotkeyCaptureHint.visibility = View.GONE
            return
        }
        if (SystemClock.uptimeMillis() < hotkeyArmedAt) return
        KeySettings.setMenuHotkey(keyCode)
        capturingHotkey = false
        binding.hotkeyCaptureHint.visibility = View.GONE
        Nav.toast(this, getString(R.string.hotkey_saved, MotionKeyBridge.describeBind(keyCode)))
        refreshStatic()
    }
}
