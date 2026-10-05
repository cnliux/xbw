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
import com.xbw.tv.data.search.PinyinSearchIndexer
import com.xbw.tv.data.update.UpdateChecker
import com.xbw.tv.data.update.UpdatePrompt
import com.xbw.tv.data.update.UpdateSettings
import com.xbw.tv.databinding.ActivitySettingsBinding
import com.xbw.tv.input.GamepadManager
import com.xbw.tv.input.KeySettings
import com.xbw.tv.input.MotionKeyBridge
import com.xbw.tv.ui.common.Nav
import com.xbw.tv.ui.diagnose.DiagnoseActivity
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
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
        binding.rowPinyin.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_pinyin_index)
                .setMessage("重新抓取 FC/街机/SFC/GBA/MD 全站列表并重建拼音索引，后台进行约十几分钟，期间可正常用机。确定？")
                .setPositiveButton("重建") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            PinyinSearchIndexer.rebuild(application, AppDatabase.get(this@SettingsActivity))
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.rowAutoUpdate.setOnClickListener {
            val on = !UpdateSettings.isAutoCheck(this)
            UpdateSettings.setAutoCheck(this, on)
            refreshUpdateRows()
        }
        binding.rowCheckUpdate.setOnClickListener { UpdatePrompt.checkNow(this) }
        binding.rowAbout.setOnClickListener {
            val ver = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) { "?" }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_about))
                .setMessage(getString(R.string.settings_about_body, ver))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        gamepads.state
        refreshStatic()
        refreshGamepad()
        refreshCounters()
        refreshUpdateRows()
    }

    /** 升级开关状态 + 当前版本号（版本号来自 BuildConfig，CI 用 -PappVersion 注入） */
    private fun refreshUpdateRows() {
        binding.valAutoUpdate.setText(
            if (UpdateSettings.isAutoCheck(this)) R.string.settings_auto_update_on
            else R.string.settings_auto_update_off
        )
        binding.valCurrentVersion.text = getString(R.string.update_version_fmt, UpdateChecker.currentVersion)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PinyinSearchIndexer.progress.collect { renderPinyinStatus(it) }
            }
        }
        lifecycleScope.launch {
            val n = XbwApplication.repository(application).searchIndexCount()
            renderPinyinStatus(PinyinSearchIndexer.progress.value, forceCount = n)
        }
    }

    private fun renderPinyinStatus(
        p: PinyinSearchIndexer.Progress,
        forceCount: Int? = null
    ) {
        lifecycleScope.launch {
            val n = forceCount ?: XbwApplication.repository(application).searchIndexCount()
            binding.valPinyinCount.text = when {
                p.running -> "$n 条 · 同步中 ${p.category} p${p.page}"
                n == 0 -> "未构建"
                p.syncedAt > 0 -> {
                    val d = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
                        .format(java.util.Date(p.syncedAt))
                    "$n 条 · 更新于 $d"
                }
                else -> "$n 条"
            }
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
