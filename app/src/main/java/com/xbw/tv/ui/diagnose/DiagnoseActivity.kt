package com.xbw.tv.ui.diagnose

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xbw.tv.R
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.SiteConfig
import com.xbw.tv.data.net.YikmParser
import kotlinx.coroutines.launch

/**
 * 站点结构诊断页（对应交付文档「选择器维护指南」的自助工具）。
 *
 * 真实抓取 4 类关键页面并报告：
 *  ① HTTP 状态 ② 响应长度 ③ 原始 HTML 中 card-blog 出现次数
 *  ④ Jsoup 用当前 SiteConfig 选择器实际解析出的卡片数/标题缺失数
 * 任何一项为 0 → 顶部红字提示按 docs/YIKM_SITE_STRUCTURE.md 更新 SiteConfig。
 */
class DiagnoseActivity : AppCompatActivity() {

    private lateinit var output: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.bg_lobby)
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(32))
        }
        ll.addView(TextView(this).apply {
            text = getString(R.string.diagnose_title)
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_primary))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        ll.addView(TextView(this).apply {
            text = getString(R.string.diagnose_hint)
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_disabled))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(12))
        })
        output = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.xbw_text_secondary))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        ll.addView(output)
        scroll.addView(ll)
        setContentView(scroll)

        runDiagnose()
    }

    private fun runDiagnose() {
        output.text = getString(R.string.diagnose_running)
        lifecycleScope.launch {
            val sb = StringBuilder()
            var anyBroken = false

            // ── 1. 首页 ──
            sb.appendLine("【1】首页  ${SiteConfig.HOME_URL}")
            anyBroken = probeAndReport(sb, SiteConfig.HOME_URL) || anyBroken

            // ── 2. 全部列表第 1 页 ──
            val listUrl = SiteConfig.listUrl(1)
            sb.appendLine("\n【2】游戏列表  $listUrl")
            anyBroken = probeAndReport(sb, listUrl) || anyBroken

            // ── 3. 街机分类 ──
            val arcade = SiteConfig.listUrl(1, tag = "9")
            sb.appendLine("\n【3】街机分类  $arcade")
            anyBroken = probeAndReport(sb, arcade) || anyBroken

            // ── 4. 搜索 ──
            val search = SiteConfig.searchUrl("魂斗罗")
            sb.appendLine("\n【4】搜索「魂斗罗」  $search")
            anyBroken = probeAndReport(sb, search) || anyBroken

            // ── 5. 游戏页（用列表抓到的第一个真实 id）──
            sb.appendLine("\n【5】游戏页")
            try {
                val html = HttpFetcher.fetchHtml(listUrl)
                val first = YikmParser.parseList(html, 1).items.firstOrNull()
                if (first == null) {
                    sb.appendLine("  ✘ 列表页没有可用卡片，无法诊断游戏页")
                    anyBroken = true
                } else {
                    val play = SiteConfig.playUrl(first.id)
                    sb.appendLine("  目标：《${first.name}》 $play")
                    try {
                        val ph = HttpFetcher.fetchHtml(play)
                        val hasCanvas = ph.contains("id=\"canvas\"")
                        val hasPause = ph.contains("GamePauseOrPlay")
                        val hasReset = ph.contains("resetButton")
                        val hasLoading = ph.contains("loading-screen")
                        line(sb, hasCanvas && hasPause && hasReset,
                            "canvas=√${hasCanvas} 暂停按钮=${hasPause} 重置按钮=${hasReset} loading屏=${hasLoading}")
                        if (!(hasPause && hasReset)) anyBroken = true
                    } catch (e: Exception) {
                        sb.appendLine("  ✘ 抓取失败：${e.message}")
                        anyBroken = true
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("  ✘ ${e.message}")
                anyBroken = true
            }

            sb.appendLine("\n──── 结论 ────")
            sb.appendLine(if (anyBroken)
                "存在失配项：请按 docs/YIKM_SITE_STRUCTURE.md 更新 SiteConfig 后重新构建。"
            else
                "全部通过：当前 SiteConfig 选择器与站点结构匹配。")

            output.text = sb
        }
    }

    /** 抓取 + Jsoup 解析双重校验，返回是否失配 */
    private suspend fun probeAndReport(sb: StringBuilder, url: String): Boolean {
        return try {
            val html = HttpFetcher.fetchHtml(url)
            val probe = HttpFetcher.probe(url)
            // parseList 第二参数是"期望页码"（Int），诊断场景一律按第 1 页
            val parsed = runCatching { YikmParser.parseList(html, 1) }
            sb.appendLine("  HTTP ${probe.code} · 长度 ${html.length} · 原始 card-blog×${probe.cardMentions}")
            val r = parsed.getOrNull()
            if (r == null) {
                sb.appendLine("  ✘ 解析异常：${parsed.exceptionOrNull()?.message}")
                sb.appendLine("  HTML 头部：${html.take(160).replace('\n', ' ')}")
                return true
            }
            val pass = r.items.isNotEmpty()
            line(sb, pass, "Jsoup 解析卡片 ${r.items.size}（原文 ${r.rawCardCount}）· 第 ${r.currentPage} 页 / 共 ${r.maxPage} 页")
            r.warnings.take(3).forEach { sb.appendLine("    ⚠ $it") }
            pass
        } catch (e: Exception) {
            sb.appendLine("  ✘ 抓取失败：${e.message}")
            true
        }
    }

    private fun line(sb: StringBuilder, ok: Boolean, msg: String) {
        sb.appendLine("  ${if (ok) "✔" else "✘"} $msg")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
