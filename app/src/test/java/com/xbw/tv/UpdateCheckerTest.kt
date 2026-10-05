package com.xbw.tv

import com.xbw.tv.data.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动升级的两块纯逻辑：版本号比较 + 下载源顺位。
 *
 * 都不碰 Android API（[UpdateChecker.versionOf] / [UpdateChecker.rankSources] 是纯函数），
 * 所以能直接上 JVM 单测。选错的后果很直观：版本比较错了该升的不升，
 * 选源错了表现为"下载巨慢"或"从镜像下到 HTML 错误页"。
 */
class UpdateCheckerTest {

    // ---------------- 版本号 ----------------

    @Test
    fun `语义化版本号派生的比较值与 versionCode 一致`() {
        // 0.0.1 → 1，0.0.14 → 14：CI 每发一版 patch+1，App 才能认得出"更高"
        assertEquals(1, UpdateChecker.versionOf("v0.0.1"))
        assertEquals(14, UpdateChecker.versionOf("v0.0.14"))
        assertEquals(1002003, UpdateChecker.versionOf("v1.2.3"))
        // 带不带 v 前缀都一样
        assertEquals(1, UpdateChecker.versionOf("0.0.1"))
    }

    @Test
    fun `版本号能正确比大小`() {
        assertTrue(UpdateChecker.versionOf("v0.0.2") > UpdateChecker.versionOf("v0.0.1"))
        assertTrue(UpdateChecker.versionOf("v0.1.0") > UpdateChecker.versionOf("v0.0.99"))
        assertTrue(UpdateChecker.versionOf("v1.0.0") > UpdateChecker.versionOf("v0.9.9"))
    }

    @Test
    fun `兼容旧的单数字 tag`() {
        // 老 Release 是 v13 / build-12 这种，不能解析成 0 才导致"永远提示更新"
        assertEquals(13, UpdateChecker.versionOf("v13"))
        assertEquals(12, UpdateChecker.versionOf("build-12"))
        assertEquals(0, UpdateChecker.versionOf("nightly"))
    }

    // ---------------- 下载源顺位 ----------------

    private fun src(name: String) = UpdateChecker.Source(name, "https://$name.example")

    @Test
    fun `按实测速度排序而不是固定偏好`() {
        val github = src("github")
        val fast = src("fast")
        val mid = src("mid")
        // github 直连能通但最慢，fast 最快：必须按数据排，直连不能因为"排前面"就赢
        val alive = listOf(github to 4200L, fast to 300L, mid to 1500L)
        val order = UpdateChecker.rankSources(alive, listOf(github, fast, mid))
        assertEquals(listOf("fast", "mid", "github"), order.map { it.name })
    }

    @Test
    fun `探不出来的源排最后当兜底`() {
        val dead = src("dead")
        val aliveOne = src("alive")
        val order = UpdateChecker.rankSources(listOf(aliveOne to 900L), listOf(dead, aliveOne))
        assertEquals(listOf("alive", "dead"), order.map { it.name })
    }

    @Test
    fun `全灭时仍保留全部源顺序`() {
        // 探活全挂也要有顺位可试：HEAD 不被支持的镜像真下载时可能成功
        val all = listOf(src("a"), src("b"))
        assertEquals(all, UpdateChecker.rankSources(emptyList(), all))
    }

    @Test
    fun `相同速度时保持声明顺序`() {
        val a = src("a")
        val b = src("b")
        assertEquals(listOf("a", "b"), UpdateChecker.rankSources(listOf(a to 100L, b to 100L), listOf(a, b)).map { it.name })
    }

    // ---------------- 落盘校验 ----------------

    @Test
    fun `镜像塞 HTML 错误页时不算下完`() {
        val html = File.createTempFile("bad", ".apk")
        try {
            html.writeText("<!DOCTYPE html><html><body>404 Not Found</body></html>")
            assertTrue(!UpdateChecker.looksLikeApk(html))
        } finally {
            html.delete()
        }
    }

    @Test
    fun `太小的文件不算 APK`() {
        val tiny = File.createTempFile("tiny", ".apk")
        try {
            tiny.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            assertTrue(!UpdateChecker.looksLikeApk(tiny))
        } finally {
            tiny.delete()
        }
    }

    @Test
    fun `PK 魔数且够大的文件算 APK`() {
        val apk = File.createTempFile("good", ".apk")
        try {
            val body = ByteArray(1024 * 1024 + 8)
            body[0] = 0x50; body[1] = 0x4B
            apk.writeBytes(body)
            assertTrue(UpdateChecker.looksLikeApk(apk))
        } finally {
            apk.delete()
        }
    }
}
