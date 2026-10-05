package com.xbw.tv.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * ▶ libretro 核心的 Kotlin 门面（对应 native 层 libxbwcore.so / xbw_core.c）。
 *
 * 生命周期（调用方必须按此顺序）：
 *   loadCore() → avInfo() → 建 AudioTrack → attachAudio() → start()
 *   → setSurface()（SurfaceTexture 就绪时）→ …游戏循环…
 *   → saveState()/loadState() 任意次 → stop()（内部先落 SRAM）
 *
 * 核心 .so 运行时 dlopen（打进 APK 的 lib/<abi>/lib<core>.so），
 * 加新核心不需要重编 libxbwcore.so。
 */
class RetroCore {

    companion object {
        private const val TAG = "RetroCore"

        init {
            System.loadLibrary("xbwcore")
        }

        /** libretro JOYPAD 位定义（retro 1.10 ABI，retro.h）——手柄映射层与 native 的契约 */
        const val ID_B = 0
        const val ID_Y = 1
        const val ID_SELECT = 2
        const val ID_START = 3
        const val ID_UP = 4
        const val ID_DOWN = 5
        const val ID_LEFT = 6
        const val ID_RIGHT = 7
        const val ID_A = 8
        const val ID_X = 9
        const val ID_L = 10
        const val ID_R = 11
        const val ID_L3 = 12
        const val ID_R3 = 13
    }

    private external fun nativeLoadCore(coreLib: String, systemDir: String, romPath: String): Boolean
    private external fun nativeSetAudioTrack(track: Any?)
    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeSetInput(bits: Int)
    private external fun nativeStart(): Boolean
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeReset()
    private external fun nativeSaveState(): ByteArray?
    private external fun nativeLoadState(data: ByteArray): Boolean
    private external fun nativeSaveSram()
    private external fun nativeAvInfo(): IntArray
    private external fun nativeSetCheats(codes: Array<String>)
    private external fun nativeGetCoreOptions(): Array<String>?
    private external fun nativeSetCoreOption(key: String, index: Int)
    private external fun nativeLoadAlternativeRom(romPath: String): Boolean
    private external fun nativeStop()

    var loaded = false
        private set
    var running = false
        private set
    private var audioTrack: AudioTrack? = null

    /** 当前已下发的金手指（读档后会重放，见 loadState） */
    private var cheats: List<String> = emptyList()

    /**
     * @param coreName 核心名（"fceumm"/"fbneo"/"snes9x"/"mgba"/"genesis_plus_gx"，
     *                 取值来自 CoreRouter）；从 nativeLibraryDir
     *                 拼绝对路径 dlopen，比裸 soname 稳（不依赖命名空间搜索顺序）
     * @param systemDir 核心读写文件目录（BIOS/系统文件放这里）
     * @param romFile 已下载好的 ROM 文件
     * @return 核心初始化 + ROM 加载是否成功
     */
    fun loadCore(context: android.content.Context, coreName: String,
                 systemDir: File, romFile: File): Boolean {
        systemDir.mkdirs()
        val so = File(context.applicationInfo.nativeLibraryDir, "lib$coreName.so").absolutePath
        loaded = nativeLoadCore(so, systemDir.absolutePath, romFile.absolutePath)
        if (!loaded) Log.e(TAG, "loadCore failed core=$coreName rom=${romFile.name}")
        return loaded
    }

    /** [baseW, baseH, fps×100, sampleRate]（loadCore 成功后有效） */
    fun avInfo(): IntArray = nativeAvInfo()

    /** 建流式 AudioTrack 并交给 native 线程写（WRITE_BLOCKING 做帧限速背压）。
     *  ⚠️ 用老式构造函数而非 Builder：实测某些定制 TV ROM（飞象 p230）把
     *  AudioTrack.Builder.setPerformanceMode 从 framework 里删了，
     *  SDK_INT 检查挡不住"方法被阉割"，NoSuchMethodError 直接崩。 */
    @Suppress("DEPRECATION")
    fun startAudio(sampleRate: Int) {
        release()
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        // 小缓冲 = 低延迟；native 阻塞写自动限速
        val bufBytes = (min * 2).coerceAtMost(1 shl 20)
        audioTrack = try {
            AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
                AudioTrack.MODE_STREAM
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack create failed, audio disabled", e)
            null
        }
        audioTrack?.let {
            if (it.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized, audio disabled")
                it.release()
                audioTrack = null
                return
            }
            it.play()
            nativeSetAudioTrack(it)
        }
    }

    fun start(): Boolean {
        running = nativeStart()
        return running
    }

    fun setSurface(surface: Surface?) = nativeSetSurface(surface)
    fun setInput(bits: Int) = nativeSetInput(bits)
    fun setPaused(p: Boolean) = nativeSetPaused(p)
    fun reset() = nativeReset()

    /** 即时存档 → 状态字节（存 Room/文件均可） */
    fun saveState(): ByteArray? = if (running) nativeSaveState() else null

    fun loadState(data: ByteArray): Boolean {
        val ok = running && nativeLoadState(data)
        // 读档会重建核心内存状态，已注册的金手指需要重新下发才继续生效
        if (ok) setCheats(cheats)
        return ok
    }

    /**
     * 下发金手指：**整表替换**（传空列表 = 全部关闭）。
     *
     * 这里只是排队；真正的 retro_cheat_reset/set 由 native 的仿真线程执行
     * （核心的读处理器只能从仿真线程改），所以本方法可以从 UI 线程安全调用。
     */
    fun setCheats(codes: List<String>) {
        cheats = codes.toList()
        if (loaded) nativeSetCheats(cheats.toTypedArray())
    }

    /** 街机（FBNeo）金手指选项：cheat ini 转成的 core option，值按序环切 */
    class CoreOption(
        val key: String,
        val desc: String,
        val values: List<String>,
        internal var current: Int,
        val defaultIndex: Int,
    ) {
        val currentIndex: Int get() = current
        val currentValue: String? get() = values.getOrNull(current)
    }

    /**
     * 读取核心下发的金手指选项（仅 fbneo-cheat- 前缀会被 native 捕获）。
     * 须在 loadCore 成功后调用；没有金手指 ini 时返回空列表。
     */
    fun coreOptions(): List<CoreOption> {
        if (!loaded) return emptyList()
        val raw = nativeGetCoreOptions() ?: return emptyList()
        return raw.mapNotNull { line ->
            val p = line.split('\u001F')
            if (p.size < 5) return@mapNotNull null
            val cur = p[2].toIntOrNull()?.coerceAtLeast(0) ?: 0
            val def = p[3].toIntOrNull()?.coerceAtLeast(0) ?: 0
            CoreOption(p[0], p[1], p.drop(4), cur, def)
        }
    }

    /** 环切选项取值：native 改 current 并标 dirty，下一帧核心重读应用 */
    fun setCoreOption(option: CoreOption, index: Int) {
        option.current = index
        if (loaded) nativeSetCoreOption(option.key, index)
    }

    /**
     * 同一核心实例上换装另一份 ROM（修改版驱动名不在数据表时的基础版兜底）。
     * 仅在 loadCore 失败后、start() 之前调用；native 侧会校验核心可用性。
     */
    fun loadAlternativeRom(romFile: File): Boolean {
        val ok = nativeLoadAlternativeRom(romFile.absolutePath)
        if (ok && !loaded) loaded = true   // loadCore 失败时 loaded=false，兜底成功后补正
        return ok
    }

    /** 拆核心并释放音频（幂等）。内部先写 SRAM 到 <rom>.sram。 */
    fun stop() {
        if (!running && !loaded) return
        nativeStop()
        running = false
        loaded = false
        release()
    }

    private fun release() {
        try {
            audioTrack?.let { it.stop(); it.release() }
        } catch (ignore: Exception) {
        }
        audioTrack = null
    }
}
