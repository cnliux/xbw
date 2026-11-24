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
 *  顶部 1P/2P/3P/4P 切玩家；每行 = 一个逻辑键（A/B/↑/Start…）：
 *   - 玩家行上左右方向键：循环切换该玩家该键注入网页的键盘码（KeyboardEvent.code）；
 *   - 按 A（确认）：进入「物理捕获」——再按一个手柄键，把这个物理键绑定给**当前玩家**；
 *   - 按 BACK：取消捕获 / 返回。
 *
 *  没单独设置过的键继承全局默认；1P→其他玩家可一键复制，也可只清当前玩家的独立设置。
 *  保存即时生效（写 SharedPreferences + 内存表，游戏页下一次按键就会按设备所属玩家查表）。
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
    private val playerTabs = mutableListOf<TextView>()

    /** 0 基下标，对应 1P~4P */
    private var selectedPlayer = 0
    private var physicalSnapshot: Map<Int, GameButton> = emptyMap()

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
        addActionRow(getString(R.string.mapping_copy_from_p1)) {
            if (selectedPlayer == 0) {
                Nav.toast(this, getString(R.string.mapping_is_p1))
            } else {
                KeySettings.copyPlayer(0, selectedPlayer)
                refreshAll()
                Nav.toast(this, getString(R.string.mapping_copied,
                    KeySettings.playerLabel(0), KeySettings.playerLabel(selectedPlayer)))
            }
        }
        addActionRow(getString(R.string.mapping_reset_player)) {
            KeySettings.resetPlayer(selectedPlayer)
            refreshAll()
            Nav.toast(this, getString(R.string.mapping_reset_done, KeySettings.playerLabel(selectedPlayer)))
        }
        refreshAll()

        // 第一物理行抢焦点，避免一进页面左右键就切玩家
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
        ll.addView(TextView(this).apply {
            text = getString(R.string.mapping_current_player)
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_secondary))
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        })
        ll.addView(makePlayerTabs())
        return ll
    }

    private fun makePlayerTabs(): View {
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        for (player in 0 until KeySettings.PLAYER_COUNT) {
            val tab = TextView(this).apply {
                text = KeySettings.playerLabel(player)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.xbw_text_secondary))
                textSize = 16f
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(10), dp(12), dp(10), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { if (player != KeySettings.PLAYER_COUNT - 1) it.rightMargin = dp(8) }
            }
            tab.setOnClickListener { selectPlayer(player) }
            tab.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        selectPlayer(selectedPlayer - 1, focusTab = true); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        selectPlayer(selectedPlayer + 1, focusTab = true); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BUTTON_START -> {
                        rows[GameButton.UP]?.root?.requestFocus(); true
                    }
                    else -> false
                }
            }
            playerTabs.add(tab)
            tabRow.addView(tab)
        }
        return tabRow
    }

    private fun selectPlayer(player: Int, focusTab: Boolean = false) {
        val count = KeySettings.PLAYER_COUNT
        selectedPlayer = ((player % count) + count) % count
        refreshAll()
        if (focusTab) playerTabs[selectedPlayer].requestFocus()
    }

    private fun addRow(btn: GameButton) {
        val row = makeFocusableRow()
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
                KeyEvent.KEYCODE_DPAD_LEFT -> { stepWebKey(selectedPlayer, btn, -1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { stepWebKey(selectedPlayer, btn, +1); true }
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

    private fun addActionRow(labelText: String, action: () -> Unit) {
        val row = makeFocusableRow()
        row.addView(TextView(this).apply {
            text = labelText
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_primary))
            textSize = 15f
        })
        container.addView(row)
        row.setOnKeyListener { _, keyCode, event ->
            if (capturingFor != null) return@setOnKeyListener true
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_START -> { action(); true }
                else -> false
            }
        }
        row.setOnClickListener { action() }
    }

    private fun makeFocusableRow(): LinearLayout = LinearLayout(this).apply {
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

    private fun stepWebKey(player: Int, btn: GameButton, delta: Int) {
        val cur = KeySettings.webKeyOf(player, btn)
        val idx = candidates.indexOfFirst { it.code == cur.code }
            .let { if (it < 0) candidates.indexOfFirst { c -> c.keyCode == cur.keyCode } else it }
            .let { if (it < 0) 0 else it }
        val next = candidates[(idx + delta + candidates.size) % candidates.size]
        KeySettings.setLogical(player, btn, next)
        refreshRow(btn)
        Nav.toast(this, getString(R.string.mapping_saved_hint, btn.label, next.code))
    }

    private fun startCapture(btn: GameButton) {
        capturingFor = btn
        captureArmedAt = SystemClock.uptimeMillis() + 280
        refreshAll()
        Nav.toast(this, getString(R.string.mapping_capture_hint,
            KeySettings.playerLabel(selectedPlayer), btn.label))
    }

    private fun acceptPhysical(code: Int, target: GameButton) {
        if (MotionKeyBridge.isIgnorableSystemKey(code)) return
        if (code == KeyEvent.KEYCODE_BACK) {
            stopCapture("已取消")
            return
        }
        if (SystemClock.uptimeMillis() < captureArmedAt) return
        KeySettings.setPhysical(selectedPlayer, code, target)
        stopCapture(getString(R.string.mapping_bound_hint, MotionKeyBridge.describeBind(code), target.label))
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

    private fun refreshAll() {
        refreshPlayerTabs()
        physicalSnapshot = KeySettings.snapshotPhysical(selectedPlayer)
        rows.keys.forEach { refreshRow(it) }
    }

    private fun refreshPlayerTabs() {
        for ((idx, tab) in playerTabs.withIndex()) {
            val active = idx == selectedPlayer
            tab.setBackgroundResource(if (active) R.drawable.btn_tv else R.drawable.bg_setting_item)
            tab.setTextColor(ContextCompat.getColor(this,
                if (active) R.color.xbw_text_primary else R.color.xbw_text_secondary))
        }
    }

    private fun refreshRow(btn: GameButton) {
        val holder = rows[btn] ?: return
        val web = KeySettings.webKeyOf(selectedPlayer, btn)
        // 物理来源：全局默认 + 当前玩家覆盖（含显式解绑）都在快照里
        val bound = physicalSnapshot.filterValues { it == btn }.keys
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
