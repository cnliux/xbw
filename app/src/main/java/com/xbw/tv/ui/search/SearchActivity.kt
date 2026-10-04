package com.xbw.tv.ui.search

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xbw.tv.R
import com.xbw.tv.XbwApplication
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.YikmParser
import com.xbw.tv.databinding.ActivitySearchBinding
import com.xbw.tv.ui.common.Nav
import com.xbw.tv.ui.lobby.GameCardAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 搜索页：GET /search?name=关键词（实测无分页，一次返回全部结果）。
 *
 * TV 输入适配：
 *  - 进页面焦点落在输入框 → 直接弹系统 IME（蓝牙键盘/遥控器都能输入）；
 *  - IME 的"搜索"动作与「搜索」按钮等效；
 *  - 结果复用 GameCardAdapter，A 键直接进游戏。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: GameCardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 窗口级屏蔽输入法：搜狗 TV 对任何获焦 View 都会主动弹自己的界面，
        // 只有 FLAG_ALT_FOCUSABLE_IM 能让 IMMS 对本窗口完全忽略 IME 显隐
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = GameCardAdapter(onClick = { item -> Nav.openGame(this, item) })
        binding.rvResults.layoutManager = GridLayoutManager(this, spanCount())
        // 同大厅：item 动画与焦点动效冲突会崩，TV 网格直接关掉
        binding.rvResults.itemAnimator = null
        binding.rvResults.adapter = adapter

        binding.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(); true
            } else false
        }
        // 手柄 B = 返回，不让系统抢焦点
        binding.etKeyword.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP) {
                finish(); true
            } else false
        }
        binding.btnDoSearch.setOnClickListener { doSearch() }

        buildKeyboard()
        // 点搜索框/按确认 → 内置键盘获焦（TV 上系统 IME 仅作中文输入的备选入口）
        binding.etKeyword.setOnClickListener { focusKeyboard() }
        binding.etKeyword.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP) {
                finish(); return@setOnKeyListener true
            }
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                focusKeyboard(); true
            } else false
        }

        // 系统输入法彻底屏蔽：showSoftInputOnFocus 挡不住搜狗 TV 主动抢显，
        // keyListener=null 直接不给 IME 建输入连接（内置键盘用 setText 写入不受影响）
        binding.etKeyword.keyListener = null
        binding.etKeyword.isCursorVisible = true
        binding.etKeyword.showSoftInputOnFocus = false
        binding.etKeyword.requestFocus()

        // 自动化/调试入口：am start --es extra_keyword xxx 直接出结果，绕开输入法
        applySeedKeyword()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySeedKeyword()
    }

    /** 自动化/调试入口：am start --es extra_keyword xxx 直接出结果，绕开 TV 输入法 */
    private fun applySeedKeyword() {
        intent?.getStringExtra("extra_keyword")?.let {
            binding.etKeyword.setText(it)
            doSearch()
        }
    }

    // ── 内置虚拟键盘：方向键/手柄选字，不依赖系统输入法 ──────────────────
    private var firstKey: android.widget.Button? = null

    private val kbLetterRows = listOf(
        "qwertyuiop".toCharArray(),
        "asdfghjkl".toCharArray(),
        "zxcvbnm".toCharArray(),
        "1234567890".toCharArray()
    )

    private fun buildKeyboard() {
        val dp = resources.displayMetrics.density
        val panel = binding.keyboardPanel
        val textColor = androidx.core.content.ContextCompat.getColor(this, R.color.xbw_text_primary)

        fun addRow(keys: List<Pair<String, (() -> Unit)?>>) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            for ((label, action) in keys) {
                val b = android.widget.Button(this).apply {
                    text = label
                    isAllCaps = false
                    setTextColor(textColor)
                    textSize = 15f
                    stateListAnimator = null
                    setBackgroundResource(R.drawable.bg_setting_item)
                    minWidth = 0; minimumWidth = 0
                    minHeight = 0; minimumHeight = 0
                    setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
                    val w = 34 + 22 * label.length
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (w * dp).toInt(), (44 * dp).toInt(), 0f
                    ).apply { setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt()) }
                    if (action != null) setOnClickListener { action() }
                }
                if (firstKey == null) firstKey = b
                row.addView(b)
            }
            panel.addView(row)
        }

        for (row in kbLetterRows) {
            val keys = row.map { ch ->
                ch.toString() to ({ appendKey(ch); Unit } as (() -> Unit)?)
            }.toMutableList()
            when (String(row)) {
                "zxcvbnm" -> keys.add("退格" to { backspaceKey() })
                "1234567890" -> keys.add("清空" to { binding.etKeyword.setText("") })
            }
            addRow(keys)
        }
        addRow(listOf("搜索" to { doSearch() }))
    }

    private fun appendKey(ch: Char) {
        binding.etKeyword.append(ch.toString())
    }

    private fun backspaceKey() {
        val et = binding.etKeyword
        val s = et.text.toString()
        if (s.isNotEmpty()) et.setText(s.substring(0, s.length - 1))
        et.setSelection(et.text.length)
    }

    private fun focusKeyboard() {
        binding.keyboardPanel.visibility = android.view.View.VISIBLE
        firstKey?.requestFocus()
    }

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels
        val cardW = (148 + 16) * resources.displayMetrics.density
        return (width / cardW).toInt().coerceIn(4, 6)
    }

    private fun doSearch() {
        val kw = binding.etKeyword.text.toString().trim()
        if (kw.isEmpty()) return
        binding.progress.visibility = android.view.View.VISIBLE
        binding.emptyText.visibility = android.view.View.GONE
        binding.statusLine.text = getString(R.string.search_loading)

        lifecycleScope.launch {
            val repo = XbwApplication.repository(application)
            // 纯字母查询大概率是拼音首字母：索引为空/过期就后台静默重建，本次先用现有索引
            val letterQuery = kw.length >= 2 && kw.all { it in 'a'..'z' || it in 'A'..'Z' }
            if (letterQuery) {
                launch(Dispatchers.IO) { runCatching { repo.ensureSearchIndex(application) } }
            }
            runCatching { repo.searchEx(kw) }
                .onSuccess { r ->
                    binding.progress.visibility = android.view.View.GONE
                    adapter.submitList(r.items)
                    if (r.items.isEmpty()) {
                        binding.emptyText.visibility = android.view.View.VISIBLE
                        binding.statusLine.text = if (letterQuery) {
                            val n = runCatching { repo.searchIndexCount() }.getOrDefault(0)
                            if (n == 0) "拼音索引构建中，稍后再试" else ""
                        } else ""
                    } else {
                        // 出结果就让位给结果网格，点搜索框可再调出键盘
                        binding.keyboardPanel.visibility = android.view.View.GONE
                        binding.statusLine.text =
                            getString(R.string.search_result_fmt, r.items.size, kw)
                        // 结果出来后焦点进列表第一项
                        binding.rvResults.post {
                            binding.rvResults.layoutManager?.findViewByPosition(0)?.requestFocus()
                        }
                    }
                }
                .onFailure { e ->
                    binding.progress.visibility = android.view.View.GONE
                    val msg = when (e) {
                        is HttpFetcher.FetchException -> getString(R.string.error_network) + "：" + (e.message ?: "")
                        is YikmParser.ParseException -> getString(R.string.error_site_changed) + "：" + (e.message ?: "")
                        else -> e.message ?: getString(R.string.error_network)
                    }
                    binding.statusLine.text = msg
                    binding.emptyText.visibility = android.view.View.VISIBLE
                }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 焦点在内置键盘上：B 先回输入框，再退页面
        if (binding.keyboardPanel.visibility == android.view.View.VISIBLE) {
            var v: android.view.View? = currentFocus
            var inPanel = false
            while (v != null) {
                if (v === binding.keyboardPanel) { inPanel = true; break }
                v = v.parent as? android.view.View
            }
            if (inPanel) {
                binding.etKeyword.requestFocus()
                return
            }
        }
        // 焦点在输入框时 B 先清焦点，再退页面（TV 习惯）
        if (binding.etKeyword.hasFocus()) {
            binding.etKeyword.clearFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
