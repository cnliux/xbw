/*
 * xbw_core.c — libretro 宿主核心（自研，参考 Lemuroid/libretro-droid 结构，未复制其代码）。
 *
 * 设计：
 *  - dlopen("libfceumm.so")（核心名由调用方给）→ dlsym retro_*；
 *    核心之间互不依赖，加新核心 = 新增一个 .so 模块；
 *  - 视频：核心 retro_run() 回调 video_refresh → 最近邻缩放 + 黑边居中
 *    直接写 ANativeWindow（纯软件渲染，无 GL 依赖，Mali-450 老盒子也能跑）；
 *  - 音频：audio 回调 → SPSC 环形缓冲 → 仿真线程写入 Java AudioTrack
 *    （WRITE_BLOCKING，背压天然给主循环限速）；
 *  - 输入：Java 侧手柄事件推进原子位图，retro_input_state 直接读位；
 *  - 存档：retro_serialize/unserialize 暴露成字节数组；
 *    SRAM（电池存档）落 <rom>.sram 文件，启动时自动吃回。
 */
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "libretro.h"

#define TAG "XbwCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── 核心导出函数指针 ──────────────────────────────────────────── */
static struct {
    void *handle;
    unsigned (*api_version)(void);
    void (*init)(void);
    void (*deinit)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_av_info)(struct retro_system_av_info *);
    bool (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_controller_port_device)(unsigned, unsigned);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void (*run)(void);
    void (*reset)(void);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *);
    bool (*unserialize)(const void *, size_t);
    void *(*get_memory_data)(int);
    size_t (*get_memory_size)(int);
    /* 金手指：可选符号（fbneo 里是空实现，某些核心干脆不导出） */
    void (*cheat_reset)(void);
    void (*cheat_set)(unsigned, bool, const char *);
} core;

#define LOAD_SYM(field, name)                                          \
    do {                                                               \
        core.field = (void *)dlsym(core.handle, name);                 \
        if (!core.field) { LOGE("dlsym %s failed", name); return 0; }  \
    } while (0)

static int resolve_core_symbols(void) {
    LOAD_SYM(api_version, "retro_api_version");
    LOAD_SYM(init, "retro_init");
    LOAD_SYM(deinit, "retro_deinit");
    LOAD_SYM(get_system_info, "retro_get_system_info");
    LOAD_SYM(get_av_info, "retro_get_system_av_info");
    LOAD_SYM(set_environment, "retro_set_environment");
    LOAD_SYM(set_video_refresh, "retro_set_video_refresh");
    LOAD_SYM(set_input_poll, "retro_set_input_poll");
    LOAD_SYM(set_input_state, "retro_set_input_state");
    LOAD_SYM(set_audio_sample, "retro_set_audio_sample");
    LOAD_SYM(set_audio_sample_batch, "retro_set_audio_sample_batch");
    LOAD_SYM(set_controller_port_device, "retro_set_controller_port_device");
    LOAD_SYM(load_game, "retro_load_game");
    LOAD_SYM(unload_game, "retro_unload_game");
    LOAD_SYM(run, "retro_run");
    LOAD_SYM(reset, "retro_reset");
    LOAD_SYM(serialize_size, "retro_serialize_size");
    LOAD_SYM(serialize, "retro_serialize");
    LOAD_SYM(unserialize, "retro_unserialize");
    LOAD_SYM(get_memory_data, "retro_get_memory_data");
    LOAD_SYM(get_memory_size, "retro_get_memory_size");
    /* 金手指是可选能力：不能用 LOAD_SYM（缺失会直接 return 0 让核心加载失败） */
    core.cheat_reset = (void *)dlsym(core.handle, "retro_cheat_reset");
    core.cheat_set = (void *)dlsym(core.handle, "retro_cheat_set");
    if (!core.cheat_reset || !core.cheat_set)
        LOGI("core has no cheat support (reset=%p set=%p)", (void *)core.cheat_reset, (void *)core.cheat_set);
    return 1;
}

/* ── 全局会话状态 ──────────────────────────────────────────────── */
static JavaVM *g_jvm;
static char g_system_dir[1024];
static char g_rom_path[1024];
static void *g_rom_data_keep;         /* 内存加载时核心可能长期持有该指针 */

static volatile int g_running;
static volatile int g_paused;
static volatile int g_reset_request;

/* 输入位图：bit = RETRO_DEVICE_ID_JOYPAD_*（UI 线程写、仿真线程读） */
static volatile int32_t g_input_bits;

/* 金手指队列：UI 线程整表替换、仿真线程应用。
 * ⚠️ retro_cheat_set 会改核心内部的读处理器（FCEUmm: FCEUI_AddCheat），
 * 必须在仿真线程调用；UI 线程直调会和 retro_run 抢同一份状态。 */
static pthread_mutex_t g_cheat_lock = PTHREAD_MUTEX_INITIALIZER;
static char **g_cheat_codes;          /* 每条已展开的 libretro 码，strdup 持有 */
static int g_cheat_count;
static volatile int g_cheat_dirty;

/* ── core options（街机金手指走这条管线）──────────────────────────
 * FBNeo 的金手指不是 retro_cheat_set（它是空函数），而是把 cheat ini
 * 转成 core options 下发（key=fbneo-cheat-N-<drv>-<名称>），前端保存
 * 当前取值、核心在 retro_run 里 GET_VARIABLE_UPDATE 后重读并打补丁。
 * 这里只捕获 fbneo-cheat- 前缀的选项；DIP/IPS/系统项一律不存，
 * GET_VARIABLE 答不上来核心就用默认值 —— 其他核心零行为变化。 */
#define OPT_MAX 256
typedef struct {
    char *key;            /* fbneo-cheat-N-<drv>-<名称> */
    char *desc;           /* [Cheat][<ini文件>] <名称>，直接给 UI 显示 */
    char **values;        /* "N - 选项名" */
    int n_values;
    int current;
    int def_idx;
} core_option;
static core_option g_opts[OPT_MAX];
static int g_opt_count;
static pthread_mutex_t g_opt_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_opt_dirty;

static const char OPT_PREFIX[] = "fbneo-cheat-";

static void clear_options_locked(void) {
    for (int i = 0; i < g_opt_count; i++) {
        free(g_opts[i].key);
        free(g_opts[i].desc);
        for (int j = 0; j < g_opts[i].n_values; j++) free(g_opts[i].values[j]);
        free(g_opts[i].values);
    }
    g_opt_count = 0;
    g_opt_dirty = 0;
}

/* vals 为 v1/v2 definition 通用的 values 数组（元素布局相同） */
static void opt_add_locked(const char *key, const char *desc, const char *def,
                           const struct retro_core_option_value *vals) {
    if (!key || strncmp(key, OPT_PREFIX, sizeof(OPT_PREFIX) - 1) != 0) return;
    if (g_opt_count >= OPT_MAX) return;
    int n = 0;
    while (n < RETRO_NUM_CORE_OPTION_VALUES_MAX && vals[n].value) n++;
    if (n <= 0) return;
    core_option *o = &g_opts[g_opt_count];
    o->key = strdup(key);
    o->desc = strdup(desc && desc[0] ? desc : key);
    o->values = calloc((size_t)n, sizeof(char *));
    if (!o->key || !o->desc || !o->values) {
        free(o->key); free(o->desc); free(o->values);
        o->key = o->desc = NULL; o->values = NULL;
        return;
    }
    for (int i = 0; i < n; i++) o->values[i] = strdup(vals[i].value);
    o->n_values = n;
    o->current = o->def_idx = 0;
    for (int i = 0; i < n; i++) {
        if (def && strcmp(o->values[i], def) == 0) { o->def_idx = i; break; }
    }
    o->current = o->def_idx;
    g_opt_count++;
}

static core_option *find_opt_locked(const char *key) {
    for (int i = 0; i < g_opt_count; i++) {
        if (strcmp(g_opts[i].key, key) == 0) return &g_opts[i];
    }
    return NULL;
}

/* 视频 surface：UI 线程替换，锁保护仿真线程使用 */
static pthread_mutex_t g_surf_lock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *g_window;       /* 已 retain */
static uint32_t g_buf_format;         /* SET_PIXEL_FORMAT 协商结果 */
static unsigned g_win_req_w, g_win_req_h;
static int g_win_req_fmt;

/* AV 信息（load_game 成功后有效） */
static unsigned g_base_w, g_base_h;
static float g_aspect;
static double g_fps, g_sample_rate;

/* 音频 SPSC 环 */
#define AUDIO_RING_SAMPLES (128 * 1024)   /* int16 个数（交织立体声） */
static int16_t *g_audio_ring;
static volatile size_t g_audio_head, g_audio_tail;
static jobject g_audio_track;             /* GlobalRef */
static jmethodID g_write_mid;
static jshortArray g_audio_jbuf;          /* GlobalRef 复用拷贝缓冲 */

static void ring_write(const int16_t *src, size_t n) {
    for (size_t i = 0; i < n; i++) {
        size_t nh = (g_audio_head + 1) % AUDIO_RING_SAMPLES;
        if (nh == g_audio_tail) break;    /* 满了丢样本：宁可偶发爆音不死锁 */
        g_audio_ring[g_audio_head] = src[i];
        g_audio_head = nh;
    }
}

static size_t ring_read(int16_t *dst, size_t max) {
    size_t got = 0;
    while (got < max && g_audio_tail != g_audio_head) {
        dst[got++] = g_audio_ring[g_audio_tail];
        g_audio_tail = (g_audio_tail + 1) % AUDIO_RING_SAMPLES;
    }
    return got;
}

/* ── libretro 回调实现 ─────────────────────────────────────────── */

static int64_t now_ns(void);

static bool cb_environment(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format *f = (enum retro_pixel_format *)data;
            if (*f == RETRO_PIXEL_FORMAT_XRGB8888 || *f == RETRO_PIXEL_FORMAT_RGB565) {
                g_buf_format = (uint32_t)*f;
                return true;
            }
            return false;   /* 1555 不支持：核心会退回 8888/565 */
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            const char **out = (const char **)data;
            *out = g_system_dir;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *var = (struct retro_variable *)data;
            if (!var || !var->key) return false;
            pthread_mutex_lock(&g_opt_lock);
            core_option *o = find_opt_locked(var->key);
            const char *val = (o && o->current >= 0 && o->current < o->n_values)
                                  ? o->values[o->current] : NULL;
            pthread_mutex_unlock(&g_opt_lock);
            if (!val) return false;   /* 未捕获的选项（DIP/IPS/系统项）→ 核心走默认 */
            var->value = val;         /* 指向存储中的稳定串；核心只在本调用窗口内读 */
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            bool *b = (bool *)data;
            *b = (g_opt_dirty != 0);  /* UI 改了取值 → 下一帧核心重读并应用金手指 */
            g_opt_dirty = 0;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
            unsigned *v = (unsigned *)data;
            *v = 2;   /* 答 2 核心（retro_common）才发 v2 结构体，金手指选项才会来 */
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLE: {
            struct retro_variable *var = (struct retro_variable *)data;
            if (!var || !var->key || !var->value) return false;
            int hit = -1;
            pthread_mutex_lock(&g_opt_lock);
            core_option *o = find_opt_locked(var->key);
            if (o) {
                for (int i = 0; i < o->n_values; i++) {
                    if (strcmp(o->values[i], var->value) == 0) { hit = i; break; }
                }
                if (hit >= 0) o->current = hit;   /* 核心发起（复位默认值），不标 dirty */
            }
            pthread_mutex_unlock(&g_opt_lock);
            return hit >= 0;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            struct retro_core_options_v2 *co = (struct retro_core_options_v2 *)data;
            if (!co || !co->definitions) return false;
            pthread_mutex_lock(&g_opt_lock);
            clear_options_locked();
            for (struct retro_core_option_v2_definition *d = co->definitions; d->key; d++)
                opt_add_locked(d->key, d->desc, d->default_value, d->values);
            int n = g_opt_count;
            pthread_mutex_unlock(&g_opt_lock);
            LOGI("core options captured (v2): %d", n);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: {
            /* 旧式 v1（不做版本协商直接发的核心）；只捕金手指前缀，其余照旧忽略 */
            struct retro_core_option_definition *co = (struct retro_core_option_definition *)data;
            if (!co) return false;
            pthread_mutex_lock(&g_opt_lock);
            clear_options_locked();
            for (; co->key; co++)
                opt_add_locked(co->key, co->desc, co->default_value, co->values);
            int n = g_opt_count;
            pthread_mutex_unlock(&g_opt_lock);
            LOGI("core options captured (v1): %d", n);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
            return true;    /* 前端不在乎：接受即忽略 */
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            struct retro_message *m = (struct retro_message *)data;
            if (m && m->msg) LOGI("core: %s", m->msg);
            return true;
        }
        default:
            return false;   /* VFS/HW render/log 等接口不支持 → 核心退回默认路径 */
    }
}

static void cb_input_poll(void) { /* 位图由 UI 线程持续更新，无需拉取 */ }

static int16_t cb_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) return (int16_t)((g_input_bits >> id) & 1);
    return 0;
}

static void cb_audio_sample(int16_t left, int16_t right) {
    int16_t s[2] = {left, right};
    ring_write(s, 2);
}

static size_t cb_audio_sample_batch(const int16_t *data, size_t count) {
    ring_write(data, count * 2);
    return count;
}

/* 缓冲设成核心分辨率，由 SurfaceFlinger 硬件放大；禁止每帧软件拉伸到 1080p */
static int window_format_for_core(void) {
    return (g_buf_format == RETRO_PIXEL_FORMAT_RGB565)
        ? WINDOW_FORMAT_RGB_565 : WINDOW_FORMAT_RGBA_8888;
}

static void ensure_window_geometry(ANativeWindow *win, unsigned w, unsigned h) {
    int fmt = window_format_for_core();
    if ((int)w <= 0 || (int)h <= 0) return;
    if (g_win_req_w == w && g_win_req_h == h && g_win_req_fmt == fmt) return;
    ANativeWindow_setBuffersGeometry(win, (int)w, (int)h, fmt);
    g_win_req_w = w;
    g_win_req_h = h;
    g_win_req_fmt = fmt;
}

static void blit_frame(const void *src, unsigned w, unsigned h, size_t pitch,
                       ANativeWindow *win) {
    ensure_window_geometry(win, w, h);
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, NULL) < 0) return;

    const int copy_h = (int)h < buf.height ? (int)h : buf.height;
    const int copy_w = (int)w < buf.width ? (int)w : buf.width;
    const int xrgb = (g_buf_format == RETRO_PIXEL_FORMAT_XRGB8888);
    const int dst_rgb565 = (buf.format == WINDOW_FORMAT_RGB_565);

    if (!xrgb && dst_rgb565) {
        for (int y = 0; y < copy_h; y++) {
            const uint8_t *srow = (const uint8_t *)src + (size_t)y * pitch;
            uint8_t *drow = (uint8_t *)buf.bits + (size_t)y * (size_t)buf.stride * 2u;
            memcpy(drow, srow, (size_t)copy_w * 2u);
        }
    } else {
        /* WINDOW_FORMAT_RGBA_8888：内存 R,G,B,A */
        for (int y = 0; y < copy_h; y++) {
            const uint8_t *srow = (const uint8_t *)src + (size_t)y * pitch;
            uint32_t *drow = (uint32_t *)((char *)buf.bits + (size_t)y * (size_t)buf.stride * 4u);
            if (xrgb) {
                const uint32_t *s = (const uint32_t *)srow;
                for (int x = 0; x < copy_w; x++) {
                    uint32_t p = s[x];
                    uint32_t r = (p >> 16) & 0xFFu;
                    uint32_t g = (p >> 8) & 0xFFu;
                    uint32_t b = p & 0xFFu;
                    drow[x] = r | (g << 8) | (b << 16) | 0xFF000000u;
                }
            } else {
                const uint16_t *s = (const uint16_t *)srow;
                for (int x = 0; x < copy_w; x++) {
                    uint16_t p = s[x];
                    uint32_t r = ((p >> 11) & 0x1F) << 3,
                             g = ((p >> 5) & 0x3F) << 2,
                             b = (p & 0x1F) << 3;
                    drow[x] = r | (g << 8) | (b << 16) | 0xFF000000u;
                }
            }
        }
    }
    ANativeWindow_unlockAndPost(win);
}

static double g_video_ms, g_run_ms, g_audio_ms;

/*
 * 视频交接池：仿真线程只把帧 memcpy 进槽位就返回，真正阻塞在
 * ANativeWindow_lock 上的活儿丢给渲染线程。
 * 之前在仿真线程里直接 lock/post，一帧要等 SurfaceFlinger 放行 ~14ms，
 * 整个循环被显示管线的 ~50Hz 拖死，音频喂不饱 → 游戏慢 17%、音调偏低。
 */
#define VIDEO_SLOTS 3
typedef struct { uint8_t *px; size_t cap; unsigned w, h; } vslot;
static vslot g_vpool[VIDEO_SLOTS];
static volatile int g_vhead, g_vtail, g_vdropped;
static pthread_t g_render_thread;
static volatile int g_render_run;

static void vpool_free(void) {
    for (int i = 0; i < VIDEO_SLOTS; i++) { free(g_vpool[i].px); g_vpool[i].px = NULL; g_vpool[i].cap = 0; }
}

static void cb_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (g_reset_request) { g_reset_request = 0; core.reset(); }
    if (!data || !width || !height) return;
    int64_t t0 = now_ns();

    int next = (g_vhead + 1) % VIDEO_SLOTS;
    if (next == g_vtail) {          /* 渲染线程跟不上：丢掉这一帧，绝不阻塞仿真 */
        g_vdropped++;
        return;
    }
    vslot *s = &g_vpool[g_vhead];
    size_t need = (size_t)width * height * 4;
    if (s->cap < need) {
        uint8_t *np = realloc(s->px, need);
        if (!np) return;
        s->px = np;
        s->cap = need;
    }
    const uint8_t *src = (const uint8_t *)data;
    for (unsigned y = 0; y < height; y++)
        memcpy(s->px + (size_t)y * width * 4, src + (size_t)y * pitch, (size_t)width * 4);
    s->w = width;
    s->h = height;
    g_vhead = next;
    g_video_ms += (double)(now_ns() - t0) / 1e6;
}

/* 渲染线程：只拿最新的帧 present，中间积压的丢掉。
 * 之所以必须跟仿真解耦：在仿真线程里 lock/post 会等 SurfaceFlinger 放行，
 * 整个循环被显示管线拖到 ~50Hz，音频喂不饱 → 游戏慢 17%、音调偏低。 */
static void *render_thread(void *arg) {
    (void)arg;
    int64_t win = now_ns();
    uint32_t blits = 0, nowin = 0;
    while (g_render_run) {
        if (g_vhead == g_vtail) {
            struct timespec s = {0, 2 * 1000 * 1000};
            nanosleep(&s, NULL);
            continue;
        }
        int pick = (g_vhead - 1 + VIDEO_SLOTS) % VIDEO_SLOTS;   /* 最新一帧 */
        while (g_vtail != pick) {                                /* 积压的全丢 */
            int nx = (g_vtail + 1) % VIDEO_SLOTS;
            if (nx == g_vhead) break;
            g_vdropped++;
            g_vtail = nx;
        }
        g_vtail = (pick + 1) % VIDEO_SLOTS;     /* pick 这帧也算消费掉了 */
        vslot *s = &g_vpool[pick];
        pthread_mutex_lock(&g_surf_lock);
        if (g_window && s->px) { blit_frame(s->px, s->w, s->h, (size_t)s->w * 4, g_window); blits++; }
        else nowin++;
        pthread_mutex_unlock(&g_surf_lock);

        int64_t t = now_ns();
        if (t - win >= 1000000000LL) {
            LOGI("render blits/s=%u nowindow=%u head=%d tail=%d", blits, nowin, g_vhead, g_vtail);
            win = t;
            blits = nowin = 0;
        }
    }
    return NULL;
}

/* ── 仿真线程 ──────────────────────────────────────────────────── */
static uint64_t g_samples_written;

static void drain_audio(JNIEnv *env, int16_t *scratch, size_t scratch_n) {
    if (!env || !g_audio_track || !g_write_mid || !g_audio_jbuf) return;
    size_t n;
    /* 每帧最多排约 2 帧音频，避免积压后 WRITE_BLOCKING 卡死一整段 */
    size_t budget = scratch_n;
    if (g_sample_rate > 0.0 && g_fps > 1.0) {
        size_t two_frames = (size_t)(g_sample_rate / g_fps * 2.0 * 2.0); /* 立体声样本数 */
        if (two_frames < budget) budget = two_frames;
        if (budget < 256) budget = 256;
    }
    size_t written = 0;
    while (written < budget && (n = ring_read(scratch, budget - written)) > 0) {
        (*env)->SetShortArrayRegion(env, g_audio_jbuf, 0, (jsize)n, scratch);
        /* AudioTrack.write(short[], int, int, int)：mode 0 = WRITE_BLOCKING */
        (*env)->CallIntMethod(env, g_audio_track, g_write_mid, g_audio_jbuf, 0, (jint)n, 0);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); break; }
        written += n;
    }
    g_samples_written += written;
}

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* 在仿真线程里应用待下发的金手指：整表替换语义
 * （retro_cheat_reset 清空 → 逐条 set，避免多次切换后重复叠加）。 */
static void apply_cheats_if_dirty(void) {
    if (!g_cheat_dirty) return;
    if (!core.cheat_reset || !core.cheat_set) { g_cheat_dirty = 0; return; }
    pthread_mutex_lock(&g_cheat_lock);
    g_cheat_dirty = 0;
    core.cheat_reset();
    for (int i = 0; i < g_cheat_count; i++) {
        if (g_cheat_codes[i]) core.cheat_set((unsigned)i, true, g_cheat_codes[i]);
    }
    int n = g_cheat_count;
    pthread_mutex_unlock(&g_cheat_lock);
    LOGI("cheats applied: %d", n);
}

static void *emu_thread(void *arg) {
    (void)arg;
    JNIEnv *env = NULL;
    /* 仿真线程必须 Attach：GetEnv 在未附着线程上永远失败，音频环永远排不空 */
    if (!g_jvm || (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
        LOGE("emu_thread AttachCurrentThread failed");
        return NULL;
    }
    const int64_t frame_ns = (int64_t)(1e9 / (g_fps > 1.0 ? g_fps : 60.0));
    int16_t *scratch = malloc(4096 * sizeof(int16_t));
    int64_t stat_win = now_ns();
    uint32_t stat_frames = 0;
    uint64_t stat_samples = 0;

    while (g_running) {
        /* 金手指在暂停判定之前应用：暂停中开关金手指也要能立刻生效 */
        apply_cheats_if_dirty();
        if (g_paused) {
            struct timespec s = {0, 20 * 1000 * 1000};
            nanosleep(&s, NULL);
            continue;
        }
        int64_t t0 = now_ns();
        core.run();                     /* 内部回调出视频+音频 */
        int64_t t1 = now_ns();
        drain_audio(env, scratch, 4096);
        stat_frames++;
        g_run_ms += (double)(t1 - t0) / 1e6;
        g_audio_ms += (double)(now_ns() - t1) / 1e6;

        /* 有 AudioTrack 时 WRITE_BLOCKING 就是时钟；再 nanosleep 会叠成半速卡顿 */
        if (!g_audio_track) {
            int64_t spent = now_ns() - t0;
            if (spent < frame_ns) {
                struct timespec s = {0, (long)(frame_ns - spent)};
                nanosleep(&s, NULL);
            }
        }

        int64_t stat_now = now_ns();
        if (stat_now - stat_win >= 1000000000LL) {
            double measured = stat_frames * 1e9 / (double)(stat_now - stat_win);
            double rate = (double)(g_samples_written - stat_samples) / 2.0 * 1e9 / (double)(stat_now - stat_win);
            LOGI("fps measured=%.2f target=%.2f audio=%d frames/s=%.0f/%.0f run=%.1f vid=%.1f aud=%.1f drop=%d",
                 measured, g_fps, g_audio_track ? 1 : 0, rate, g_sample_rate / 2.0,
                 g_run_ms / stat_frames, g_video_ms / stat_frames, g_audio_ms / stat_frames, g_vdropped);
            stat_win = stat_now;
            stat_frames = 0;
            stat_samples = g_samples_written;
            g_run_ms = g_video_ms = g_audio_ms = 0;
            g_vdropped = 0;
        }
    }
    free(scratch);
    (*g_jvm)->DetachCurrentThread(g_jvm);
    return NULL;
}

/* SRAM（电池存档）落盘 / 吃回 */
static void sram_file_path(char *out, size_t cap) {
    snprintf(out, cap, "%s.sram", g_rom_path);
}

static void save_sram_to_disk(void) {
    if (!core.get_memory_data || !core.get_memory_size) return;
    void *sram = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t sz = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!sram || !sz) return;
    char path[1300];
    sram_file_path(path, sizeof(path));
    FILE *f = fopen(path, "wb");
    if (!f) { LOGE("sram open failed %s", path); return; }
    fwrite(sram, 1, sz, f);
    fclose(f);
    LOGI("sram saved %zu bytes", sz);
}

static void load_sram_from_disk(void) {
    if (!core.get_memory_data || !core.get_memory_size) return;
    void *sram = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t sz = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!sram || !sz) return;
    char path[1300];
    sram_file_path(path, sizeof(path));
    FILE *f = fopen(path, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n > 0 && (size_t)n <= sz && fread(sram, 1, (size_t)n, f) == (size_t)n)
        LOGI("sram restored %ld bytes", n);
    fclose(f);
}

/* 加载 ROM：need_fullpath=false 的核心优先喂内存（不落盘路线），
 * 这类核心可能整局持有 data 指针 → 缓冲区存全局，stop 时释放。 */
static bool try_load_rom(void) {
    struct retro_game_info gi;
    memset(&gi, 0, sizeof(gi));
    gi.path = g_rom_path;

    struct retro_system_info si;
    memset(&si, 0, sizeof(si));
    core.get_system_info(&si);

    if (!si.need_fullpath) {
        FILE *f = fopen(g_rom_path, "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            long sz = ftell(f);
            fseek(f, 0, SEEK_SET);
            void *buf = (sz > 0) ? malloc((size_t)sz) : NULL;
            if (buf && fread(buf, 1, (size_t)sz, f) == (size_t)sz) {
                struct retro_game_info mi = gi;
                mi.data = buf;
                mi.size = (size_t)sz;
                if (core.load_game(&mi)) {
                    fclose(f);
                    free(g_rom_data_keep);
                    g_rom_data_keep = buf;
                    return true;
                }
            }
            free(buf);
            fclose(f);
        }
    }
    return core.load_game(&gi);
}

/* ── JNI 入口 ──────────────────────────────────────────────────── */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_xbw_tv_core_RetroCore_nativeLoadCore(JNIEnv *env, jclass cls, jstring coreLib,
                                              jstring systemDir, jstring romPath) {
    (void)cls;
    const char *lib = (*env)->GetStringUTFChars(env, coreLib, NULL);
    core.handle = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, coreLib, lib);
    if (!core.handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_FALSE;
    }
    if (!resolve_core_symbols()) return JNI_FALSE;
    if (core.api_version() != RETRO_API_VERSION) {
        LOGE("retro api mismatch: core=%u frontend=%u",
             core.api_version(), RETRO_API_VERSION);
        return JNI_FALSE;
    }

    const char *sd = (*env)->GetStringUTFChars(env, systemDir, NULL);
    snprintf(g_system_dir, sizeof(g_system_dir), "%s", sd);
    (*env)->ReleaseStringUTFChars(env, systemDir, sd);
    const char *rp = (*env)->GetStringUTFChars(env, romPath, NULL);
    snprintf(g_rom_path, sizeof(g_rom_path), "%s", rp);
    (*env)->ReleaseStringUTFChars(env, romPath, rp);

    g_audio_ring = malloc(AUDIO_RING_SAMPLES * sizeof(int16_t));
    g_audio_head = g_audio_tail = 0;
    g_buf_format = RETRO_PIXEL_FORMAT_XRGB8888;
    g_input_bits = 0;
    g_paused = 0;
    g_reset_request = 0;
    pthread_mutex_lock(&g_opt_lock);
    clear_options_locked();   /* 新会话：上一局的金手指选项全部作废 */
    pthread_mutex_unlock(&g_opt_lock);

    /* libretro 生命周期规定 set_environment 必须先于 init：
     * FCEUmm 的 retro_init 里会立刻调 environ_cb(GET_LOG_INTERFACE)，
     * 先 init 会 blr NULL 直接段错误（实测崩溃栈 retro_init+84）。 */
    core.set_environment(cb_environment);
    core.init();
    core.set_video_refresh(cb_video_refresh);
    core.set_input_poll(cb_input_poll);
    core.set_input_state(cb_input_state);
    core.set_audio_sample(cb_audio_sample);
    core.set_audio_sample_batch(cb_audio_sample_batch);
    core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);

    if (!try_load_rom()) {
        LOGE("retro_load_game failed: %s", g_rom_path);
        return JNI_FALSE;
    }

    struct retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    core.get_av_info(&av);
    g_base_w = av.geometry.base_width;
    g_base_h = av.geometry.base_height;
    g_aspect = av.geometry.aspect_ratio;
    g_fps = av.timing.fps;
    g_sample_rate = av.timing.sample_rate;
    LOGI("loaded ok: base=%ux%u fps=%.3f rate=%.0f fmt=%u",
         g_base_w, g_base_h, g_fps, g_sample_rate, g_buf_format);
    return JNI_TRUE;
}

/* AudioTrack（Java 侧创建，WRITE_BLOCKING 模式）注入给仿真线程排空音频环 */
JNIEXPORT void JNICALL
Java_com_xbw_tv_core_RetroCore_nativeSetAudioTrack(JNIEnv *env, jclass cls, jobject track) {
    (void)cls;
    if (g_audio_track) { (*env)->DeleteGlobalRef(env, g_audio_track); g_audio_track = NULL; }
    g_write_mid = NULL;
    if (!track) return;
    g_audio_track = (*env)->NewGlobalRef(env, track);
    jclass c = (*env)->GetObjectClass(env, track);
    g_write_mid = (*env)->GetMethodID(env, c, "write", "([SIII)I");
    if (!g_audio_jbuf) {
        jshortArray a = (*env)->NewShortArray(env, 4096);
        g_audio_jbuf = (*env)->NewGlobalRef(env, a);
        (*env)->DeleteLocalRef(env, a);
    }
}

JNIEXPORT void JNICALL
Java_com_xbw_tv_core_RetroCore_nativeSetSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    ANativeWindow *nw = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    pthread_mutex_lock(&g_surf_lock);
    if (g_window) ANativeWindow_release(g_window);
    g_window = nw;
    g_win_req_w = g_win_req_h = 0;
    g_win_req_fmt = -1;
    if (nw) {
        unsigned bw = g_base_w ? g_base_w : 256;
        unsigned bh = g_base_h ? g_base_h : 240;
        ensure_window_geometry(nw, bw, bh);
    }
    pthread_mutex_unlock(&g_surf_lock);
}

JNIEXPORT void JNICALL
Java_com_xbw_tv_core_RetroCore_nativeSetInput(JNIEnv *env, jclass cls, jint bits) {
    (void)env; (void)cls;
    g_input_bits = (int32_t)bits;
}

JNIEXPORT jboolean JNICALL Java_com_xbw_tv_core_RetroCore_nativeStart(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    g_running = 1;
    g_paused = 0;
    g_vhead = g_vtail = 0;
    g_vdropped = 0;
    g_render_run = 1;
    if (pthread_create(&g_render_thread, NULL, render_thread, NULL) != 0) g_render_run = 0;
    pthread_t th;
    if (pthread_create(&th, NULL, emu_thread, NULL) != 0) {
        g_running = 0;
        return JNI_FALSE;
    }
    pthread_detach(th);
    load_sram_from_disk();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_xbw_tv_core_RetroCore_nativeSetPaused(JNIEnv *env, jclass cls, jboolean p) {
    (void)env; (void)cls;
    g_paused = p ? 1 : 0;
}

JNIEXPORT void JNICALL Java_com_xbw_tv_core_RetroCore_nativeReset(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    g_reset_request = 1;
}

JNIEXPORT jbyteArray JNICALL Java_com_xbw_tv_core_RetroCore_nativeSaveState(JNIEnv *env, jclass cls) {
    (void)cls;
    size_t sz = core.serialize_size();
    if (!sz) return NULL;
    void *buf = malloc(sz);
    if (!buf) return NULL;
    jbyteArray out = NULL;
    if (core.serialize(buf)) {
        out = (*env)->NewByteArray(env, (jsize)sz);
        if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)sz, (jbyte *)buf);
    }
    free(buf);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_xbw_tv_core_RetroCore_nativeLoadState(JNIEnv *env, jclass cls, jbyteArray data) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *p = (*env)->GetByteArrayElements(env, data, NULL);
    bool ok = core.unserialize(p, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* 退出前调用：SRAM 落盘（在 stop 拆核心之前！） */
JNIEXPORT void JNICALL Java_com_xbw_tv_core_RetroCore_nativeSaveSram(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    save_sram_to_disk();
}

JNIEXPORT void JNICALL
Java_com_xbw_tv_core_RetroCore_nativeSetCheats(JNIEnv *env, jclass cls, jobjectArray codes) {
    (void)cls;
    int n = codes ? (*env)->GetArrayLength(env, codes) : 0;
    char **arr = NULL;
    if (n > 0) {
        arr = calloc((size_t)n, sizeof(char *));
        if (!arr) return;
        for (int i = 0; i < n; i++) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, codes, i);
            if (!js) continue;
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            if (s) {
                arr[i] = strdup(s);
                (*env)->ReleaseStringUTFChars(env, js, s);
            }
            (*env)->DeleteLocalRef(env, js);   /* 条数可能上百，及时回收局部引用 */
        }
    }
    pthread_mutex_lock(&g_cheat_lock);
    char **old = g_cheat_codes;
    int oldn = g_cheat_count;
    g_cheat_codes = arr;
    g_cheat_count = n;
    g_cheat_dirty = 1;
    if (old) {
        for (int i = 0; i < oldn; i++) free(old[i]);
        free(old);
    }
    pthread_mutex_unlock(&g_cheat_lock);
    LOGI("cheats set: %d", n);
}

/* ── core options 的 JNI 桥（街机金手指 UI 用）────────────────────
 * 导出格式：key␟desc␟currentIdx␟defaultIdx␟value0␟value1…（␟=0x1F 分隔） */
JNIEXPORT jobjectArray JNICALL
Java_com_xbw_tv_core_RetroCore_nativeGetCoreOptions(JNIEnv *env, jclass cls) {
    (void)cls;
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    pthread_mutex_lock(&g_opt_lock);
    jobjectArray out = (*env)->NewObjectArray(env, g_opt_count, str_cls, NULL);
    for (int i = 0; i < g_opt_count; i++) {
        core_option *o = &g_opts[i];
        size_t len = strlen(o->key) + strlen(o->desc) + 40;
        for (int j = 0; j < o->n_values; j++) len += strlen(o->values[j]) + 2;
        char *buf = (char *)malloc(len);
        if (!buf) continue;
        char *p = buf;
        p += sprintf(p, "%s\x1F%s\x1F%d\x1F%d", o->key, o->desc, o->current, o->def_idx);
        for (int j = 0; j < o->n_values; j++) p += sprintf(p, "\x1F%s", o->values[j]);
        jstring js = (*env)->NewStringUTF(env, buf);
        if ((*env)->ExceptionCheck(env)) {
            /* ini 里若混入非法 UTF-8 别让整个面板崩掉，退化为只显示 key */
            (*env)->ExceptionClear(env);
            js = (*env)->NewStringUTF(env, o->key);
        }
        if (js) {
            (*env)->SetObjectArrayElement(env, out, i, js);
            (*env)->DeleteLocalRef(env, js);
        }
        free(buf);
    }
    pthread_mutex_unlock(&g_opt_lock);
    return out;
}

/* UI 环切某个选项的取值：改 current 并标 dirty，下一帧核心自己重读应用 */
JNIEXPORT void JNICALL
Java_com_xbw_tv_core_RetroCore_nativeSetCoreOption(JNIEnv *env, jclass cls, jstring key, jint index) {
    (void)cls;
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    if (!k) return;
    pthread_mutex_lock(&g_opt_lock);
    core_option *o = find_opt_locked(k);
    if (o && index >= 0 && index < o->n_values && index != o->current) {
        o->current = index;
        g_opt_dirty = 1;
    }
    pthread_mutex_unlock(&g_opt_lock);
    (*env)->ReleaseStringUTFChars(env, key, k);
}

/* 修改版 zip 的驱动名可能不在本版 FBNeo 数据表里 → loadCore 失败；
 * 此时核心已 init（dlopen/init/set_environment 都完成了），在同一实例上
 * 卸载后换装基础版 zip 重载，省掉整套核心重建。仅在仿真线程启动前调用。 */
JNIEXPORT jboolean JNICALL
Java_com_xbw_tv_core_RetroCore_nativeLoadAlternativeRom(JNIEnv *env, jclass cls, jstring romPath) {
    (void)cls;
    if (!core.handle || !core.unload_game || !core.load_game) return JNI_FALSE;
    if (g_running) return JNI_FALSE;
    core.unload_game();   /* 上次 load 失败时是幂等的（nBurnDrvActive 守卫） */
    const char *rp = (*env)->GetStringUTFChars(env, romPath, NULL);
    snprintf(g_rom_path, sizeof(g_rom_path), "%s", rp);
    (*env)->ReleaseStringUTFChars(env, romPath, rp);
    pthread_mutex_lock(&g_opt_lock);
    clear_options_locked();   /* 旧驱动的金手指选项全部作废 */
    pthread_mutex_unlock(&g_opt_lock);
    if (!try_load_rom()) {
        LOGE("alternative rom load failed: %s", g_rom_path);
        return JNI_FALSE;
    }
    /* 分辨率/帧率可能随驱动变化，重读一遍 */
    struct retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    core.get_av_info(&av);
    g_base_w = av.geometry.base_width;
    g_base_h = av.geometry.base_height;
    g_aspect = av.geometry.aspect_ratio;
    g_fps = av.timing.fps;
    g_sample_rate = av.timing.sample_rate;
    LOGI("alternative rom loaded: %s base=%ux%u fps=%.3f", g_rom_path, g_base_w, g_base_h, g_fps);
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL Java_com_xbw_tv_core_RetroCore_nativeAvInfo(JNIEnv *env, jclass cls) {
    (void)cls;
    jintArray a = (*env)->NewIntArray(env, 4);
    jint v[4] = {(jint)g_base_w, (jint)g_base_h, (jint)(g_fps * 100), (jint)g_sample_rate};
    (*env)->SetIntArrayRegion(env, a, 0, 4, v);
    return a;
}

JNIEXPORT void JNICALL Java_com_xbw_tv_core_RetroCore_nativeStop(JNIEnv *env, jclass cls) {
    (void)cls;
    if (!g_running && !core.handle) return;
    save_sram_to_disk();
    g_running = 0;
    struct timespec s = {0, 150 * 1000 * 1000};   /* 等仿真线程收尾 */
    nanosleep(&s, NULL);
    g_render_run = 0;
    pthread_join(g_render_thread, NULL);
    vpool_free();
    if (core.unload_game) core.unload_game();
    if (core.deinit) core.deinit();
    if (g_audio_track) { (*env)->DeleteGlobalRef(env, g_audio_track); g_audio_track = NULL; }
    if (g_audio_jbuf) { (*env)->DeleteGlobalRef(env, g_audio_jbuf); g_audio_jbuf = NULL; }
    g_write_mid = NULL;
    pthread_mutex_lock(&g_surf_lock);
    if (g_window) { ANativeWindow_release(g_window); g_window = NULL; }
    pthread_mutex_unlock(&g_surf_lock);
    free(g_audio_ring);
    g_audio_ring = NULL;
    free(g_rom_data_keep);
    g_rom_data_keep = NULL;
    pthread_mutex_lock(&g_cheat_lock);
    if (g_cheat_codes) {
        for (int i = 0; i < g_cheat_count; i++) free(g_cheat_codes[i]);
        free(g_cheat_codes);
        g_cheat_codes = NULL;
    }
    g_cheat_count = 0;
    g_cheat_dirty = 0;
    pthread_mutex_unlock(&g_cheat_lock);
    pthread_mutex_lock(&g_opt_lock);
    clear_options_locked();
    pthread_mutex_unlock(&g_opt_lock);
    if (core.handle) { dlclose(core.handle); core.handle = NULL; }
    memset(&core, 0, sizeof(core));
    (void)env;
}
