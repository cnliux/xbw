package com.xbw.tv.ui.settings

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xbw.tv.R
import com.xbw.tv.input.GameButton
import com.xbw.tv.input.KeySettings
import com.xbw.tv.input.MotionKeyBridge
import com.xbw.tv.ui.common.Nav

/**
 * 按键映射页（方案 7.3「按键自定义」的两层编辑器，纯代码布局免 XML）：
 *
 *  每行 = 一个逻辑键（A/B/↑/Start…）：
 *   - 左右方向键：循环切换该键注入网页的键盘码（KeyboardEvent.code）；
 *   - 按 A（确认）：进入「物理捕获」——再按一个手柄键，把这个物理键绑定给该逻辑键；
 *   - 按 B：取消捕获 / 返回。
 *
 * 保存即时生效（写 SharedPreferences + 内存表，GameActivity 下一次按键就会用新映射）。
 */
class KeyMappingActivity : AppCompatActivity() {

    /** 可切换的网页键盘码候选（覆盖 FC/街机/h5 常用键位） */
    private val candidates = listOf(
        GameButton.WebKey("ArrowUp", "ArrowUp", 38),
        GameButton.WebKey("ArrowDown", "ArrowDown", 40),
        GameButton.WebKey("ArrowLeft", "ArrowLeft", 37),
        GameButton.WebKey("ArrowRight", "ArrowRight", 39),
        GameButton.WebKey("KeyX", "x", 88),
        GameButton.WebKey("KeyZ", "z", 90),
        GameButton.WebKey("KeyS", "s", 83),
        GameButton.WebKey("KeyA", "a", 65),
        GameButton.WebKey("KeyQ", "q", 81),
        GameButton.WebKey("KeyW", "w", 87),
        GameButton.WebKey("KeyE", "e", 69),
        GameButton.WebKey("Enter", "Enter", 13),
        GameButton.WebKey("ShiftLeft", "Shift", 16),
        GameButton.WebKey("Space", " ", 32),
        GameButton.WebKey("KeyC", "c", 67),
        GameButton.WebKey("KeyV", "v", 86),
        GameButton.WebKey("KeyF", "f", 70),
        GameButton.WebKey("KeyG", "g", 71),
        GameButton.WebKey("KeyT", "t", 84)
    )

    private lateinit var container: LinearLayout
    private val rows = mutableMapOf<GameButton, RowHolder>()

    /** 正在等待物理按键捕获的逻辑键 */
    private var capturingFor: GameButton? = null
    private var captureArmedAt = 0L
    private val hatX = intArrayOf(0)
    private val hatY = intArrayOf(0)
    private val stickX = intArrayOf(0)
    private val stickY = intArrayOf(0)

    private inner class RowHolder(val root: View, val labelTv: TextView, val valueTv: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.bg_lobby)
            isFillViewport = true
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(32))
        }
        scroll.addView(container)
        setContentView(scroll)

        container.addView(makeHeader())

        for (btn in GameButton.entries) {
            addRow(btn)
        }
        refreshAll()

        // 第一行抢焦点
        container.post {
            rows.values.firstOrNull()?.root?.requestFocus()
        }
    }

    private fun makeHeader(): View {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        ll.addView(TextView(this).apply {
            text = getString(R.string.settings_mapping)
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_primary))
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        ll.addView(TextView(this).apply {
            text = getString(R.string.mapping_hint)
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_disabled))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })
        return ll
    }

    private fun addRow(btn: GameButton) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.bg_setting_item)
            isFocusable = true
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        val label = TextView(this).apply {
            text = btn.label
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_primary))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_secondary))
            textSize = 14f
        }
        row.addView(label)
        row.addView(value)
        container.addView(row)

        // 捕获期间不在行上处理按键，避免焦点系统把手柄键吃掉
        row.setOnKeyListener { _, keyCode, event ->
            if (capturingFor != null) return@setOnKeyListener true
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { stepWebKey(btn, -1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { stepWebKey(btn, +1); true }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_START -> {
                    startCapture(btn)
                    true
                }
                else -> false
            }
        }
        row.setOnClickListener {
            if (capturingFor == btn) stopCapture("已取消") else startCapture(btn)
        }
        rows[btn] = RowHolder(row, label, value)
    }

    private fun stepWebKey(btn: GameButton, delta: Int) {
        val cur = KeySettings.webKeyOf(btn)
        val idx = candidates.indexOfFirst { it.code == cur.code }
            .let { if (it < 0) candidates.indexOfFirst { c -> c.keyCode == cur.keyCode } else it }
            .let { if (it < 0) 0 else it }
        val next = candidates[(idx + delta + candidates.size) % candidates.size]
        KeySettings.setLogical(btn, next)
        refreshRow(btn)
        Nav.toast(this, getString(R.string.mapping_saved_hint, btn.label, next.code))
    }

    private fun startCapture(btn: GameButton) {
        capturingFor = btn
        captureArmedAt = SystemClock.uptimeMillis() + 280
        refreshAll()
        Nav.toast(this, "请按下要绑定给「${btn.label}」的键（手柄/USB 两种模式均可）")
    }

    private fun acceptPhysical(code: Int, target: GameButton) {
        if (MotionKeyBridge.isIgnorableSystemKey(code)) return
        if (code == KeyEvent.KEYCODE_BACK) {
            stopCapture("已取消")
            return
        }
        if (SystemClock.uptimeMillis() < captureArmedAt) return
        KeySettings.setPhysical(code, target)
        stopCapture("已绑定：${MotionKeyBridge.describeBind(code)} → ${target.label}")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = capturingFor
        if (target != null) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (event.repeatCount > 0) return true
            acceptPhysical(MotionKeyBridge.bindCode(event), target)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val target = capturingFor
        if (target != null) {
            MotionKeyBridge.dispatchMotion(event, hatX, hatY, stickX, stickY) { code, down ->
                if (down) acceptPhysical(code, target)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (capturingFor != null) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun stopCapture(msg: String) {
        capturingFor = null
        refreshAll()
        Nav.toast(this, msg)
    }

    private fun refreshAll() = rows.keys.forEach { refreshRow(it) }

    private fun refreshRow(btn: GameButton) {
        val holder = rows[btn] ?: return
        val web = KeySettings.webKeyOf(btn)
        // 物理来源：默认表 + 用户覆盖表都看
        val bound = KeySettings.snapshotPhysical().filterValues { it == btn }.keys
            .joinToString("/") { MotionKeyBridge.describeBind(it) }
            .ifBlank { "未绑定" }
        val capturing = capturingFor == btn
        holder.valueTv.text = if (capturing) "请按键…" else "网页:${web.code}  ⇐  $bound"
        holder.root.setBackgroundResource(
            if (capturing) R.drawable.btn_tv else R.drawable.bg_setting_item
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
