package com.xbw.tv.ui.search

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
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

        binding.etKeyword.requestFocus()
        // 延迟弹键盘，等窗口获得焦点
        binding.etKeyword.postDelayed({ showKeyboard() }, 400)
    }

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels
        val cardW = (148 + 16) * resources.displayMetrics.density
        return (width / cardW).toInt().coerceIn(4, 6)
    }

    private fun showKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etKeyword, InputMethodManager.SHOW_IMPLICIT)
        } catch (ignore: Exception) {
        }
    }

    private fun doSearch() {
        val kw = binding.etKeyword.text.toString().trim()
        if (kw.isEmpty()) return
        hideKeyboard()
        binding.progress.visibility = android.view.View.VISIBLE
        binding.emptyText.visibility = android.view.View.GONE
        binding.statusLine.text = getString(R.string.search_loading)

        lifecycleScope.launch {
            val repo = XbwApplication.repository(application)
            runCatching { repo.search(kw) }
                .onSuccess { r ->
                    binding.progress.visibility = android.view.View.GONE
                    adapter.submitList(r.items)
                    if (r.items.isEmpty()) {
                        binding.emptyText.visibility = android.view.View.VISIBLE
                        binding.statusLine.text = ""
                    } else {
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

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etKeyword.windowToken, 0)
        } catch (ignore: Exception) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 焦点在输入框时 B 先清焦点/键盘，再退页面（TV 习惯）
        if (binding.etKeyword.hasFocus()) {
            hideKeyboard()
            binding.etKeyword.clearFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
