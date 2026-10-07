package com.xbw.tv.ui.game

import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.xbw.tv.R
import com.xbw.tv.databinding.ActivityVideoGameBinding

/**
 * ▶ 视频类"游戏"播放：第三方插件源里那些没有 ROM、只有 `<video>` 的条目
 * （Emby/retroFE 清单常见的交互影像 / 演示视频）。
 *
 * 先用 cookie 下载到本地再播（[Nav.playPluginVideo]），所以这里只认本地文件路径。
 * 遥控器：DPAD_LEFT/RIGHT 快退/快进，BACK 退出。
 */
class VideoGameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "extra_video_path"
        const val EXTRA_TITLE = "extra_title"
        private const val SEEK_STEP_MS = 15_000
    }

    private lateinit var b: ActivityVideoGameBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVideoGameBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.videoTitle.text = intent.getStringExtra(EXTRA_TITLE)
        val path = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (path == null) {
            b.loading.visibility = View.GONE
            b.videoStatus.text = getString(R.string.video_play_error_fmt, "缺少视频文件")
            b.videoStatus.visibility = View.VISIBLE
            return
        }
        val controller = MediaController(this)
        controller.setAnchorView(b.video)
        b.video.setMediaController(controller)
        b.video.setOnPreparedListener {
            b.loading.visibility = View.GONE
            b.videoStatus.visibility = View.GONE
            b.video.start()
        }
        b.video.setOnErrorListener { _, what, _ ->
            b.loading.visibility = View.GONE
            b.videoStatus.text = getString(R.string.video_play_error_fmt, errorText(what))
            b.videoStatus.visibility = View.VISIBLE
            true
        }
        b.video.setVideoPath(path)
        b.video.requestFocus()
    }

    private fun errorText(what: Int): String = when (what) {
        MediaPlayer.MEDIA_ERROR_IO -> "IO 错误"
        MediaPlayer.MEDIA_ERROR_MALFORMED -> "文件损坏"
        MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "格式不支持"
        MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "读取超时"
        else -> "错误码 $what"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vw = b.video as VideoView
        if (vw.isPlaying) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { vw.seekTo((vw.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { vw.seekTo(vw.currentPosition + SEEK_STEP_MS); return true }
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }
}